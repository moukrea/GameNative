package app.gamenative.provisioning.model

import android.content.Context
import app.gamenative.utils.HardwareUtils
import com.winlator.core.GPUInformation

/**
 * The running device's relevant characteristics, used to evaluate [DeviceOverride]s.
 *
 * These are derived from signals the app already computes (e.g. the GPU name/family used by
 * BestConfigService's GPU matching) — no new fingerprinting is introduced.
 */
data class DeviceProfile(
    val gpuName: String? = null,
    val gpuFamily: String? = null,
    val soc: String? = null,
    val driver: String? = null,
) {
    companion object {
        val UNKNOWN = DeviceProfile()

        @Volatile
        private var cached: DeviceProfile? = null

        /**
         * The current device's profile, derived from the GPU renderer/version + SOC the app already
         * queries. Every probe is wrapped in runCatching and the result is memoised: GPU info is read
         * via [GPUInformation] (which caches it) and the native Vulkan-version query is deliberately
         * avoided (flagged crash-prone). On any failure a field is left null, which matches no
         * [DeviceCondition] — so an unresolved profile behaves exactly like [UNKNOWN] (zero behaviour
         * change), while a resolved one activates the device-override layer.
         */
        fun current(context: Context): DeviceProfile {
            cached?.let { return it }
            val renderer = runCatching { GPUInformation.getRenderer(context) }.getOrNull()?.ifBlank { null }
            val driver = runCatching { GPUInformation.getVersion(context) }.getOrNull()?.ifBlank { null }
            val soc = runCatching { HardwareUtils.getSOCName() }.getOrNull()?.ifBlank { null }
            val profile = DeviceProfile(
                gpuName = renderer,
                gpuFamily = renderer?.let { gpuFamilyOf(context, it) },
                soc = soc,
                driver = driver,
            )
            cached = profile
            return profile
        }

        /** Normalised GPU-family bucket reusing [GPUInformation]'s existing model regexes. */
        private fun gpuFamilyOf(context: Context, renderer: String): String? {
            val r = renderer.lowercase()
            return when {
                runCatching { GPUInformation.isAdreno8EliteGen5(context) }.getOrDefault(false) -> "adreno-8-elite-gen5"
                runCatching { GPUInformation.isAdreno8Elite(context) }.getOrDefault(false) -> "adreno-8-elite"
                runCatching { GPUInformation.isAdreno740(context) }.getOrDefault(false) -> "adreno-740"
                runCatching { GPUInformation.isAdreno710_720_732(context) }.getOrDefault(false) -> "adreno-710-720-732"
                runCatching { GPUInformation.isAdreno6xx(context) }.getOrDefault(false) -> "adreno-6xx"
                runCatching { GPUInformation.isTurnipCapable(context) }.getOrDefault(false) -> "adreno-turnip"
                r.contains("adreno") -> "adreno"
                r.contains("mali") -> "mali"
                r.contains("xclipse") -> "xclipse"
                r.contains("powervr") -> "powervr"
                else -> null
            }
        }
    }
}

/** True when every predicate this condition declares is satisfied by [device] (AND semantics). */
fun DeviceCondition.matches(device: DeviceProfile): Boolean {
    if (isEmpty()) return false
    gpuFamily?.let { if (!it.equals(device.gpuFamily, ignoreCase = true)) return false }
    socContains?.let { if (device.soc?.contains(it, ignoreCase = true) != true) return false }
    driverContains?.let { if (device.driver?.contains(it, ignoreCase = true) != true) return false }
    return true
}
