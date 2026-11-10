package io.github.xororz.localdream.data

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

enum class DitImportKind(val backend: String, val marker: String, val steps: Int, val needsVision: Boolean = false) {
    KLEIN("klein", "KLEIN", 4),
    ZIMAGE("zimage", "ZIMAGE", 8),
    QWEN21("qwen21", "QWEN_IMAGE_2_1", 20, true),
    KREA2("krea2turbo", "KREA_2_TURBO", 8, true),
}

enum class DitImportComponent(val filename: String) {
    DIFFUSION("dit.safetensors"),
    TEXT_ENCODER("llm.gguf"),
    VAE("vae.safetensors"),
    TOKENIZER("tokenizer.json"),
    VISION("llm_vision.gguf"),
}

data class DitImportSource(val size: Long, val open: () -> InputStream)

/** Streaming, staged import. No Android dependencies, model conversion, or existing-model replacement. */
object DitModelImport {
    const val IMPORT_MARKER = "DIT_IMPORTED"
    private const val MAX_HEADER_BYTES = 16 * 1024 * 1024

    fun modelId(name: String): String {
        val id = name.trim().replace(" ", "")
        require(id.isNotEmpty() && id.length <= 100 && !id.startsWith(".")) { "Enter a model name (up to 100 characters)" }
        require(id.all { it.isLetterOrDigit() || it in "-_." }) { "Model name may contain letters, numbers, dots, hyphens and underscores" }
        return id
    }

    fun requiredComponents(kind: DitImportKind): List<DitImportComponent> = DitImportComponent.entries.filter {
        it != DitImportComponent.VISION || kind.needsVision
    }

    fun isComplete(dir: File, kind: DitImportKind): Boolean = File(dir, kind.marker).isFile &&
        requiredComponents(kind).all { component ->
            if (component == DitImportComponent.DIFFUSION) {
                listOf("dit.safetensors", "dit.gguf").any { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
            } else {
                File(dir, component.filename).let { it.isFile && it.length() > 0 }
            }
        }

    fun importModel(
        root: File,
        name: String,
        kind: DitImportKind,
        sources: Map<DitImportComponent, DitImportSource>,
        experimental: Boolean,
        ignoreCase: Boolean = false,
        isReserved: (String) -> Boolean = { false },
        checkCancelled: () -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val id = modelId(name)
        require(!isReserved(id)) { "This name is reserved for a built-in model" }
        require(!experimental || kind == DitImportKind.KLEIN) { "SM8650 currently supports Klein 4B imports only" }
        val required = requiredComponents(kind)
        require(required.all { it in sources }) { "Select all required model components" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create model storage directory" }
        fun destinationExists() = root.listFiles().orEmpty().any { it.name.equals(id, ignoreCase) }
        require(!destinationExists()) { "A model with this name already exists; choose a new name" }
        val sizes = required.map { sources.getValue(it).size }
        val total = if (sizes.all { it > 0 }) sizes.fold(0L, Math::addExact) else -1L
        require(total < 0 || root.usableSpace >= total + 64L * 1024 * 1024) { "Not enough free storage to copy the model" }
        val staging = File(root, ".dit-import-${UUID.randomUUID()}")
        check(staging.mkdir()) { "Cannot create import staging directory" }
        var copied = 0L
        try {
            val buffer = ByteArray(1024 * 1024)
            var lastProgress = 0L
            required.forEach { component ->
                checkCancelled()
                val source = sources.getValue(component)
                val output = File(staging, component.filename)
                var componentBytes = 0L
                source.open().use { input ->
                    output.outputStream().buffered().use { target ->
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            copied += count
                            componentBytes += count
                            if (copied - lastProgress >= 4L * 1024 * 1024) {
                                onProgress(copied, total)
                                lastProgress = copied
                            }
                        }
                    }
                }
                require(componentBytes > 0 && (source.size < 0 || source.size == componentBytes)) { "Incomplete ${component.filename}" }
                when (component) {
                    DitImportComponent.DIFFUSION -> {
                        if (isGguf(output)) {
                            require(!experimental) { "For SM8650, select the Klein 4B FP8 safetensors diffusion model" }
                            validateGguf(output)
                            check(output.renameTo(File(staging, "dit.gguf"))) { "Cannot store GGUF diffusion model" }
                        } else {
                            val header = validateSafetensors(output)
                            if (experimental) validateKlein4b(header)
                        }
                    }

                    DitImportComponent.TEXT_ENCODER, DitImportComponent.VISION -> validateGguf(output)

                    DitImportComponent.VAE -> {
                        val header = validateSafetensors(output)
                        require(header.keys().asSequence().any { it.contains("decoder.") }) { "VAE file does not contain decoder weights" }
                    }

                    DitImportComponent.TOKENIZER -> {
                        require(output.length() <= 64L * 1024 * 1024) { "Tokenizer JSON is too large" }
                        require(JSONObject(output.readText()).optJSONObject("model") != null) { "Select tokenizer.json, not tokenizer_config.json" }
                    }
                }
            }
            checkCancelled()
            File(staging, "config.json").writeText(
                JSONObject().put("default_prompt", "a lovely cat").put("default_negative_prompt", "")
                    .put("default_steps", kind.steps).put("default_cfg", 1).put("default_scheduler", "euler").toString(),
            )
            File(staging, kind.marker).writeText("")
            File(staging, IMPORT_MARKER).writeText(kind.backend)
            require(!destinationExists()) { "A model with this name already exists; choose a new name" }
            val destination = File(root, id)
            check(staging.renameTo(destination)) { "Cannot finalize model import" }
            onProgress(copied, total)
            return destination
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun isGguf(file: File): Boolean = file.inputStream().use { input ->
        val magic = ByteArray(4)
        input.read(magic) == 4 && magic.contentEquals("GGUF".toByteArray(Charsets.US_ASCII))
    }

    private fun validateGguf(file: File) {
        require(isGguf(file) && file.length() > 24) { "${file.name}: expected a complete GGUF model" }
        RandomAccessFile(file, "r").use {
            it.seek(4)
            val version = Integer.reverseBytes(it.readInt())
            val tensors = java.lang.Long.reverseBytes(it.readLong())
            require(version in 2..3 && tensors > 0) { "${file.name}: invalid GGUF header" }
        }
    }

    private fun validateSafetensors(file: File): JSONObject = RandomAccessFile(file, "r").use { input ->
        require(file.length() > 8) { "${file.name}: empty safetensors file" }
        val length = java.lang.Long.reverseBytes(input.readLong())
        require(length in 2..MAX_HEADER_BYTES.toLong() && length < file.length() - 8) { "${file.name}: invalid safetensors header" }
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        val header = JSONObject(bytes.toString(Charsets.UTF_8))
        val keys = header.keys().asSequence().filter { it != "__metadata__" }.toList()
        require(keys.isNotEmpty()) { "${file.name}: no model weights" }
        val payload = file.length() - 8 - length
        keys.forEach { key ->
            val tensor = header.getJSONObject(key)
            val offsets = tensor.getJSONArray("data_offsets")
            require(offsets.length() == 2 && offsets.getLong(0) >= 0 && offsets.getLong(1) >= offsets.getLong(0) && offsets.getLong(1) <= payload) {
                "${file.name}: incomplete tensor $key"
            }
            val itemBytes = when (tensor.getString("dtype")) {
                "BOOL", "U8", "I8", "F8_E4M3", "F8_E5M2" -> 1L
                "U16", "I16", "F16", "BF16" -> 2L
                "U32", "I32", "F32" -> 4L
                "U64", "I64", "F64" -> 8L
                else -> throw IllegalArgumentException("${file.name}: unsupported safetensors dtype")
            }
            val shape = tensor.getJSONArray("shape")
            var elements = 1L
            for (i in 0 until shape.length()) {
                val dimension = shape.getLong(i)
                require(dimension >= 0) { "${file.name}: invalid tensor dimensions" }
                elements = Math.multiplyExact(elements, dimension)
            }
            require(Math.multiplyExact(elements, itemBytes) == offsets.getLong(1) - offsets.getLong(0)) { "${file.name}: tensor size mismatch for $key" }
        }
        header
    }

    internal fun validateKlein4b(header: JSONObject) {
        val keys = header.keys().asSequence().toList()
        val textInput = keys.firstOrNull { it.endsWith("txt_in.weight") }?.let { header.getJSONObject(it).getJSONArray("shape") }
        require(textInput != null && textInput.length() == 2 && textInput.getInt(0) == 3072 && textInput.getInt(1) == 7680) {
            "SM8650 import requires Klein 4B weights (3072 hidden channels, 7680 text channels)"
        }
        require(keys.any { it.endsWith("single_blocks.19.linear1.weight") } && keys.none { it.contains("single_blocks.20.") }) {
            "Select the Klein 4B diffusion model, not another FLUX variant"
        }
        require(keys.any { header.optJSONObject(it)?.optString("dtype") == "F8_E4M3" }) { "Select FP8 E4M3 weights for the SM8650 experimental backend" }
    }
}
