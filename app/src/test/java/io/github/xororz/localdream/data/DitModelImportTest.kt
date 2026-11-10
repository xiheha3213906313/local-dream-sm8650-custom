package io.github.xororz.localdream.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DitModelImportTest {
    private fun tensor(shape: List<Int> = listOf(1), size: Long = 4) = JSONObject()
        .put("dtype", "F32").put("shape", JSONArray(shape)).put("data_offsets", JSONArray(listOf(0L, size)))

    private fun safetensors(key: String): ByteArray {
        val header = JSONObject().put(key, tensor()).toString().toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array() + header + ByteArray(4)
    }

    private fun source(bytes: ByteArray) = DitImportSource(bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun components(): Map<DitImportComponent, DitImportSource> {
        val gguf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).put("GGUF".toByteArray()).putInt(3).putLong(1).putLong(0).array()
        return mapOf(
            DitImportComponent.DIFFUSION to source(safetensors("img_in.weight")),
            DitImportComponent.TEXT_ENCODER to source(gguf),
            DitImportComponent.VAE to source(safetensors("decoder.conv_in.weight")),
            DitImportComponent.TOKENIZER to source("{\"model\":{\"type\":\"BPE\"}}".toByteArray()),
        )
    }

    private fun inStorage(block: (File) -> Unit) {
        val root = Files.createTempDirectory("dit-import-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedImportHasStandardNamesMarkersAndDiTDefaults() = inStorage { root ->
        var done = 0L
        val dir = DitModelImport.importModel(root, "my Klein", DitImportKind.KLEIN, components(), onProgress = { value, _ -> done = value })
        assertEquals("myKlein", dir.name)
        assertTrue(DitModelImport.isComplete(dir, DitImportKind.KLEIN))
        assertTrue(File(dir, DitModelImport.IMPORT_MARKER).isFile)
        assertFalse(File(dir, "npucustom").exists())
        assertEquals(4, JSONObject(File(dir, "config.json").readText()).getInt("default_steps"))
        assertTrue(done > 0)
        assertEquals(listOf("myKlein"), root.list()!!.toList())
    }

    @Test
    fun duplicateOrReservedNameNeverReplacesExistingData() = inStorage { root ->
        val dir = File(root, "existing").apply { mkdir() }
        File(dir, "keep").writeText("original")
        expectFailure { DitModelImport.importModel(root, "EXISTING", DitImportKind.KLEIN, components(), ignoreCase = true) }
        expectFailure { DitModelImport.importModel(root, "builtin", DitImportKind.KLEIN, components(), isReserved = { it == "builtin" }) }
        assertEquals("original", File(dir, "keep").readText())
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun invalidNamesCannotEscapeModelStorage() {
        listOf("../escape", "/absolute", "a/b", "a\\b", ".hidden", "", "..", "a:b").forEach {
            expectFailure { DitModelImport.modelId(it) }
        }
    }

    @Test
    fun missingComponentsAreRejectedForEveryModelFamily() = inStorage { root ->
        for (kind in DitImportKind.entries) {
            expectFailure { DitModelImport.importModel(root, kind.backend, kind, emptyMap()) }
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun ioFailureAndCancellationRemoveStagingAndNeverPublishModel() = inStorage { root ->
        val bad = components().toMutableMap()
        bad[DitImportComponent.VAE] = DitImportSource(-1) { throw IOException("provider disconnected") }
        expectFailure { DitModelImport.importModel(root, "broken", DitImportKind.KLEIN, bad) }
        var calls = 0
        expectFailure {
            DitModelImport.importModel(root, "cancelled", DitImportKind.KLEIN, components(), checkCancelled = {
                if (++calls == 3) throw CancellationException("cancel")
            })
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun malformedTruncatedAndWrongRoleFilesAreRejected() = inStorage { root ->
        for (component in DitImportComponent.entries.filter { it != DitImportComponent.VISION }) {
            val bad = components().toMutableMap()
            bad[component] = source("<html>download failed</html>".toByteArray())
            expectFailure { DitModelImport.importModel(root, "bad", DitImportKind.KLEIN, bad) }
        }
        val truncated = components().toMutableMap()
        truncated[DitImportComponent.DIFFUSION] = source(safetensors("img_in.weight").dropLast(1).toByteArray())
        expectFailure { DitModelImport.importModel(root, "truncated", DitImportKind.KLEIN, truncated) }
        val wrongVae = components().toMutableMap()
        wrongVae[DitImportComponent.VAE] = source(safetensors("img_in.weight"))
        expectFailure { DitModelImport.importModel(root, "wrongvae", DitImportKind.KLEIN, wrongVae) }
        val wrongTokenizer = components().toMutableMap()
        wrongTokenizer[DitImportComponent.TOKENIZER] = source("{\"tokenizer_class\":\"Qwen\"}".toByteArray())
        expectFailure { DitModelImport.importModel(root, "wrongtokenizer", DitImportKind.KLEIN, wrongTokenizer) }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun ggufDiffusionIsNamedCorrectlyForEveryModelFamily() = inStorage { root ->
        for (kind in DitImportKind.entries) {
            val gguf = components().toMutableMap()
            gguf[DitImportComponent.DIFFUSION] = gguf.getValue(DitImportComponent.TEXT_ENCODER)
            if (kind.needsVision) gguf[DitImportComponent.VISION] = gguf.getValue(DitImportComponent.TEXT_ENCODER)
            val dir = DitModelImport.importModel(root, "quantized-${kind.backend}", kind, gguf)
            assertTrue(File(dir, "dit.gguf").isFile)
            assertFalse(File(dir, "dit.safetensors").exists())
            assertTrue(DitModelImport.isComplete(dir, kind))
        }
    }

    @Test
    fun safetensorsImportHasNoKleinDimensionOrFp8OnlyGate() = inStorage { root ->
        for (kind in DitImportKind.entries) {
            val sources = components().toMutableMap()
            if (kind.needsVision) {
                expectFailure { DitModelImport.importModel(root, "missing-vision-${kind.backend}", kind, sources) }
                sources[DitImportComponent.VISION] = sources.getValue(DitImportComponent.TEXT_ENCODER)
            }
            // The synthetic F32 header intentionally has none of Klein 4B's
            // architecture-specific keys. File integrity remains validated.
            val dir = DitModelImport.importModel(root, "original-${kind.backend}", kind, sources)
            assertTrue(DitModelImport.isComplete(dir, kind))
            assertTrue(File(dir, "dit.safetensors").isFile)
            assertTrue(File(dir, kind.marker).isFile)
        }
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Expected import to reject the input", failed)
    }
}
