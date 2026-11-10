package io.github.xororz.localdream.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The DiT engine (libdit_engine.so) that runs Z-Image Turbo, FLUX.2/Klein and
 * Qwen Image 2.1.
 *
 * It ships inside the APK alongside the backend executable, so it lands in
 * nativeLibraryDir and the core dlopens it from there. Its FastRPC skels are
 * APK assets copied to the shared runtime directory by BackendService.
 */
object DitEngine {
    const val ENGINE_LIB = "libdit_engine.so"
    const val SM8650_CPU_VAE_KEY = "sm8650_cpu_vae"

    // DSP decode completed successfully on the target Xiaomi 14; retain CPU as an explicit fallback.
    const val SM8650_CPU_VAE_DEFAULT = false

    private fun partNumber(soc: String): Int? {
        val normalized = soc.trim().uppercase()
        if (!normalized.startsWith("SM")) return null
        return normalized.drop(2).takeWhile { it.isDigit() }.toIntOrNull()
    }

    internal fun isSm8650Soc(soc: String, apiLevel: Int): Boolean = apiLevel >= 31 && partNumber(soc) == 8650

    internal fun isSupportedSoc(soc: String, apiLevel: Int): Boolean {
        if (apiLevel < 31) return false
        val part = partNumber(soc) ?: return false
        // Add 8 Gen 3 explicitly; do not accidentally admit other lower-numbered
        // chips whose HTP architecture has not been covered by this backend.
        return part == 8650 || part >= 8750
    }

    // v75 still needs FP8 -> F16 HMX compatibility even though it is now a
    // supported device. Keep that hardware choice separate from eligibility.
    fun isSm8650Device(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        isSm8650Soc(Build.SOC_MODEL, Build.VERSION.SDK_INT)

    fun isSupportedDevice(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        isSupportedSoc(Build.SOC_MODEL, Build.VERSION.SDK_INT)

    /** Directory holding the engine, i.e. the app's native library directory. */
    fun dir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun isInstalled(context: Context): Boolean = File(dir(context), ENGINE_LIB).exists()
}
