package app.gamenative.provisioning.model

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
