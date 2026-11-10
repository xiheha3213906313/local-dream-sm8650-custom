package io.github.xororz.localdream.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.xororz.localdream.data.DitEngine
import io.github.xororz.localdream.service.BackendService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object Sm8650Diagnostics {
    @Volatile
    var isRunning = false
        private set

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        check(DitEngine.isExperimentalDevice()) { "Requires SM8650 experimental build" }
        synchronized(this@Sm8650Diagnostics) {
            check(!isRunning) { "Diagnostic already running" }
            check(
                BackendService.backendState.value !is BackendService.BackendState.Starting &&
                    BackendService.backendState.value !is BackendService.BackendState.Running,
            ) { "Exit the generation screen and stop host mode before running diagnostics" }
            isRunning = true
        }
        var process: Process? = null
        val outputFile = File(context.cacheDir, "sm8650-diagnostic.log")
        outputFile.delete()
        val report = try {
            val nativeDir = DitEngine.dir(context)
            val executable = File(nativeDir, "libdit_diagnostic.so")
            check(executable.isFile) { "Diagnostic executable missing; rebuild native libraries" }
            val dspDir = File(context.filesDir, "sm8650_diagnostics").apply { mkdirs() }
            val skelName = "libggml-htp-v75.so"
            context.assets.open("ditlibs/$skelName").use { input ->
                File(dspDir, skelName).outputStream().use { input.copyTo(it) }
            }
            val dspPath = listOf(dspDir.path, "/vendor/lib/rfsa/adsp", "/vendor/dsp/cdsp", "/dsp")
                .joinToString(";")
            val builder = ProcessBuilder(executable.path).apply {
                directory(nativeDir)
                redirectErrorStream(true)
                redirectOutput(outputFile)
                environment().apply {
                    put("LD_LIBRARY_PATH", "${nativeDir.path}:/system/lib64:/vendor/lib64:/vendor/lib64/egl")
                    put("ADSP_LIBRARY_PATH", dspPath)
                    put("DSP_LIBRARY_PATH", dspPath)
                    remove("GGML_HEXAGON_ARCH")
                }
            }
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            val header = "SM8650 diagnostics: model=${Build.MODEL} soc=${Build.SOC_MODEL} " +
                "android=${Build.VERSION.RELEASE} totalMemory=${info.totalMem} availableMemory=${info.availMem}\n"
            val started = System.nanoTime()
            val child = builder.start()
            process = child
            while (!child.waitFor(250, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() - started < TimeUnit.MINUTES.toNanos(3)) { "Diagnostic timed out after 3 minutes" }
            }
            val matrixReport = header + "matrixExitCode=${child.exitValue()}\n" + outputFile.readText().takeLast(2_000_000)
            if (child.exitValue() != 0) {
                matrixReport
            } else {
                // Isolate each VAE probe so a DSP failure preserves earlier reports.
                var combined = matrixReport
                for (kind in listOf("norm", "conv")) {
                    val probeOutput = File(context.cacheDir, "sm8650-$kind-diagnostic.log")
                    val probeBuilder = ProcessBuilder(executable.path, "--$kind-only").apply {
                        directory(nativeDir)
                        redirectErrorStream(true)
                        redirectOutput(probeOutput)
                        environment().clear()
                        environment().putAll(builder.environment())
                    }
                    val probeChild = probeBuilder.start()
                    process = probeChild
                    val probeStarted = System.nanoTime()
                    try {
                        while (!probeChild.waitFor(250, TimeUnit.MILLISECONDS)) {
                            currentCoroutineContext().ensureActive()
                            check(System.nanoTime() - probeStarted < TimeUnit.MINUTES.toNanos(3)) { "$kind diagnostic timed out after 3 minutes" }
                        }
                        combined += "\nHTP VAE probe (generation decoder is selected in Settings): ${kind}ExitCode=${probeChild.exitValue()}\n"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        combined += "\nHTP $kind probe failed: ${e.message}\n"
                    } finally {
                        if (probeChild.isAlive) {
                            probeChild.destroyForcibly()
                            probeChild.waitFor(2, TimeUnit.SECONDS)
                        }
                        combined += if (probeOutput.isFile) probeOutput.readText().takeLast(256_000) else "No native output"
                    }
                }
                combined
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "FP8_DIAG FAIL: ${e.message}\n" +
                (if (outputFile.isFile) outputFile.readText().takeLast(2_000_000) else "No native output")
        } finally {
            process?.let {
                if (it.isAlive) {
                    it.destroyForcibly()
                    it.waitFor(2, TimeUnit.SECONDS)
                }
            }
            isRunning = false
        }
        // Keep a private copy across process restarts and app updates.
        runCatching { File(context.filesDir, "sm8650-last-diagnostic.txt").writeText(report) }
        report
    }

    suspend fun readLastReport(context: Context): String? = withContext(Dispatchers.IO) {
        val saved = File(context.filesDir, "sm8650-last-diagnostic.txt")
        val nativeOutput = File(context.cacheDir, "sm8650-diagnostic.log")
        val generationTrace = File(context.filesDir, "sm8650-generation-trace.log")
        val latest = listOf(saved, nativeOutput, generationTrace)
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
            ?: return@withContext null
        val contents = latest.readText().takeLast(2_000_000)
        if (latest == saved) {
            contents
        } else if (latest == generationTrace) {
            "Recovered generation trace (latest model run, not the matrix self-test):\n$contents"
        } else {
            // Recover output produced by older APKs, including the v0.2 diagnostic.
            "Recovered native diagnostic: model=${Build.MODEL} soc=${Build.SOC_MODEL} " +
                "android=${Build.VERSION.RELEASE}\n$contents"
        }
    }
}
