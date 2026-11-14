package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class LoraAsset(val id: String, val name: String, val size: Long)

@Immutable
data class LoraSelection(val id: String, val name: String, val weight: Float = 1f, val enabled: Boolean = true)

/** Content-addressed files and per-model selections, separate from the legacy model store. */
class LoraRepository private constructor(private val context: Context) {
    val assets = MutableStateFlow<List<LoraAsset>>(emptyList())
    val revisions = MutableStateFlow(0)
    private val mutex = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val directory = File(context.filesDir, "loras").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("lora_models", Context.MODE_PRIVATE)

    init {
        assets.value = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                val data = JSONObject(file.readText())
                val id = data.getString("id")
                require(ID.matches(id) && File(directory, "$id.safetensors").isFile)
                LoraAsset(id, data.getString("name"), data.getLong("size"))
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    fun selections(modelId: String): List<LoraSelection> = decode(preferences.getString(modelId, "[]") ?: "[]")

    fun persistOnExit(modelId: String, selected: List<LoraSelection>) {
        background.launch { save(modelId, selected) }
    }

    suspend fun save(modelId: String, selected: List<LoraSelection>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(selected.size <= MAX_COUNT && selected.map { it.id }.distinct().size == selected.size)
            selected.forEach { require(ID.matches(it.id) && it.weight.isFinite() && it.weight in -2f..2f) }
            check(preferences.edit().putString(modelId, encode(selected)).commit())
            revisions.value++
        }
    }

    suspend fun importFile(uri: Uri): LoraAsset = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "LoRA.safetensors"
        require(name.endsWith(".safetensors", true)) { "请选择标准 safetensors LoRA 文件" }
        val temporary = File(directory, ".import-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取文件" }
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            inspect(temporary)
            val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            mutex.withLock {
                assets.value.firstOrNull { it.id == id }?.let { return@withContext it }
                val asset = LoraAsset(id, name, temporary.length())
                val target = File(directory, "$id.safetensors")
                check(temporary.renameTo(target)) { "无法保存 LoRA 文件" }
                val metadata = File(directory, "$id.json")
                try {
                    metadata.writeText(JSONObject().put("id", id).put("name", name).put("size", asset.size).toString())
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
                assets.value = (assets.value + asset).sortedBy { it.name.lowercase() }
                asset
            }
        } finally {
            temporary.delete()
        }
    }

    fun effective(selected: List<LoraSelection>): List<LoraSelection> = selected.filter { it.enabled && it.weight != 0f }

    fun validate(selected: List<LoraSelection>) {
        require(selected.size <= MAX_COUNT) { "最多加载 3 个 LoRA" }
        effective(selected).forEach {
            require(ID.matches(it.id) && it.weight.isFinite() && it.weight in -2f..2f) { "LoRA 参数无效" }
            require(File(directory, "${it.id}.safetensors").isFile) { "LoRA 文件缺失：${it.name}" }
        }
    }

    companion object {
        const val MAX_COUNT = 3
        private val ID = Regex("[0-9a-f]{64}")

        @Volatile private var instance: LoraRepository? = null
        fun get(context: Context): LoraRepository = instance ?: synchronized(this) {
            instance ?: LoraRepository(context.applicationContext).also { instance = it }
        }
        fun encode(selected: List<LoraSelection>): String = JSONArray().apply {
            selected.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("weight", it.weight.toDouble()).put("enabled", it.enabled)) }
        }.toString()
        fun decode(json: String): List<LoraSelection> = runCatching {
            val array = JSONArray(json)
            require(array.length() <= MAX_COUNT)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                val id = entry.getString("id")
                val weight = entry.optDouble("weight", 1.0).toFloat()
                require(ID.matches(id) && weight.isFinite() && weight in -2f..2f)
                LoraSelection(id, entry.optString("name", id), weight, entry.optBoolean("enabled", true))
            }.also { require(it.map { item -> item.id }.distinct().size == it.size) }
        }.getOrDefault(emptyList())

        private fun inspect(file: File) {
            RandomAccessFile(file, "r").use { input ->
                require(input.length() >= 10) { "文件不完整" }
                val length = java.lang.Long.reverseBytes(input.readLong())
                require(length in 2..(16L * 1024 * 1024) && length <= input.length() - 8) { "无效的 safetensors 文件头" }
                val bytes = ByteArray(length.toInt()).also { input.readFully(it) }
                val tensors = JSONObject(bytes.toString(Charsets.UTF_8))
                val names = tensors.keys().asSequence().filter { it != "__metadata__" }.toList()
                require(names.none { name -> listOf("lora_magnitude", "dora_scale", "hada_", "lokr_", "oft_").any { name.contains(it, true) } }) { "首版仅支持标准 LoRA，不支持 DoRA / LyCORIS 扩展" }
                val downs = names.filter { it.endsWith(".lora_down.weight") || it.endsWith(".lora_A.weight") || it.endsWith(".lora_A.default.weight") }
                require(downs.isNotEmpty()) { "文件中没有标准 LoRA 权重" }
                downs.forEach { down ->
                    val up = down.replace(".lora_down.weight", ".lora_up.weight").replace(".lora_A.", ".lora_B.")
                    require(tensors.has(up)) { "LoRA 缺少配对权重：$up" }
                    listOf(down, up).forEach { name ->
                        val tensor = tensors.getJSONObject(name)
                        val shape = tensor.getJSONArray("shape")
                        require(shape.length() == 2 || shape.length() == 4) { "不支持的 LoRA 张量形状：$name" }
                        var elements = 1L
                        repeat(shape.length()) { axis ->
                            val dim = shape.getLong(axis)
                            require(dim > 0)
                            elements = Math.multiplyExact(elements, dim)
                        }
                        val size = when (tensor.getString("dtype")) {
                            "F32" -> 4
                            "F16", "BF16" -> 2
                            else -> error("不支持的 LoRA 数据类型")
                        }
                        val offsets = tensor.getJSONArray("data_offsets")
                        require(offsets.length() == 2 && offsets.getLong(0) >= 0 && offsets.getLong(1) - offsets.getLong(0) == Math.multiplyExact(elements, size.toLong()) && offsets.getLong(1) <= input.length() - 8 - length) { "LoRA 张量数据不完整：$name" }
                    }
                }
            }
        }
    }
}
