package io.github.xororz.localdream.data

import android.content.Context
import android.content.Intent
import io.github.xororz.localdream.service.BackendService
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

object LoraRuntime {
    const val CORE_LIB = "libstable_diffusion_lora_core.so"
    const val ENGINE_LIB = "libdit_lora_engine.so"

    suspend fun select(context: Context, model: Model, width: Int, height: Int, selected: List<LoraSelection>) {
        val repository = LoraRepository.get(context)
        repository.validate(selected)
        val effective = repository.effective(selected)
        val json = LoraRepository.encode(effective)
        val matches = BackendService.servingLoraConfig.value == json &&
            BackendService.servingModelId.value == model.id &&
            BackendService.servingResolution.value == (width to height) &&
            BackendService.backendState.value is BackendService.BackendState.Running
        if (matches) return
        if (effective.isNotEmpty()) {
            check(File(context.applicationInfo.nativeLibraryDir, CORE_LIB).isFile) { "LoRA 推理引擎未安装" }
            if (!model.isDit) {
                val dir = File(Model.getModelsDir(context), "${model.id}/lora_runtime")
                check(File(dir, "lora_manifest.json").isFile || File(dir, "variants/${width}x$height/lora_manifest.json").isFile) { "这个模型需要安装 LoRA 兼容包，请在 LoRA 页面导入" }
            }
        }
        val previousState = BackendService.backendState.value
        context.startForegroundService(
            Intent(context, BackendService::class.java).apply {
                putExtra("modelId", model.id)
                putExtra("backendType", model.backendType)
                putExtra("width", width)
                putExtra("height", height)
                putExtra("lora_config", json)
            },
        )
        try {
            withTimeout(600_000) {
                BackendService.backendState.first {
                    (it is BackendService.BackendState.Error && it !== previousState) || (it is BackendService.BackendState.Running && BackendService.servingLoraConfig.value == json && BackendService.servingModelId.value == model.id && BackendService.servingResolution.value == (width to height))
                }.also { if (it is BackendService.BackendState.Error) error(it.message) }
                val client = OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                while (true) {
                    val ready = withContext(Dispatchers.IO) {
                        runCatching { client.newCall(Request.Builder().url("http://127.0.0.1:8081/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
                    }
                    if (ready) break
                    val state = BackendService.backendState.value
                    if (state is BackendService.BackendState.Error) error(state.message)
                    delay(250)
                }
            }
        } catch (e: CancellationException) {
            context.startService(Intent(context, BackendService::class.java).setAction(BackendService.ACTION_STOP))
            throw e
        }
    }

    suspend fun importPackage(context: Context, modelId: String, uri: android.net.Uri) = withContext(Dispatchers.IO) {
        val model = File(Model.getModelsDir(context), modelId).canonicalFile
        check(model.isDirectory) { "主模型尚未安装" }
        val temporary = File(model, ".lora-import-${java.util.UUID.randomUUID()}")
        check(temporary.mkdir())
        try {
            val prefix = temporary.canonicalPath + File.separator
            var entries = 0
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw)
                java.util.zip.ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        require(++entries <= 20000) { "适配包文件过多" }
                        val target = File(temporary, entry.name).canonicalFile
                        require(target.path.startsWith(prefix)) { "适配包包含非法路径" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                            continue
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                check(temporary.usableSpace > count + 32L * 1024 * 1024) { "存储空间不足" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            val manifests = temporary.walkTopDown().filter { it.name == "lora_manifest.json" }.toList()
            require(manifests.isNotEmpty()) { "未找到 lora_manifest.json" }
            manifests.forEach { file ->
                require(file.length() <= 16 * 1024 * 1024) { "适配包清单过大" }
                val manifest = org.json.JSONObject(file.readText())
                require(manifest.getInt("format_version") == 1)
                val fingerprints = manifest.getJSONObject("base_files")
                require(fingerprints.length() > 0) { "适配包缺少基模标识" }
                fingerprints.keys().forEach { name ->
                    val base = File(model, name).canonicalFile
                    require(base.path.startsWith(model.path + File.separator) && base.isFile) { "适配包与当前模型不匹配" }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    base.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(hash == fingerprints.getString(name)) { "适配包与当前基模不匹配：$name" }
                }
            }
            val target = File(model, "lora_runtime")
            val backup = File(model, ".lora-backup-${java.util.UUID.randomUUID()}")
            if (target.exists()) check(target.renameTo(backup))
            if (!temporary.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装 LoRA 兼容包")
            }
            backup.deleteRecursively()
        } finally {
            temporary.deleteRecursively()
        }
    }
}
