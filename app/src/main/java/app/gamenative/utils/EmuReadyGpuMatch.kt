package app.gamenative.utils

/**
 * Heuristic matcher between the device's GPU (Android GL_RENDERER, e.g. "Adreno (TM) 740") and an
 * EmuReady listing's GPU model string (e.g. "Adreno 740", "Mali-G615", "Xclipse 940"). Pure +
 * unit-tested. RAM is deliberately absent — EmuReady's Android listings carry no RAM — so similarity
 * is GPU-vendor + model-number only (the strongest signal the data supports).
 */
object EmuReadyGpuMatch {

    /** Proximity of an EmuReady listing's GPU to the device's, best first. */
    enum class Tier { EXACT, FAMILY, LOWER, OTHER }

    private data class Gpu(val vendor: String?, val number: Int?)

    private fun normalize(raw: String?): Gpu {
        if (raw.isNullOrBlank()) return Gpu(null, null)
        var s = raw.lowercase().replace("(tm)", " ").replace("(r)", " ")
        // Strip Arm's core/module-count suffixes (MP12, MC6, ...) FIRST: otherwise the model-number
        // scan grabs the core count, e.g. "Mali-G1 Ultra MP12" would parse as 12 instead of G1.
        s = s.replace(Regex("\\bm[cp]\\d+\\b"), " ")
        // Canonical vendor. Arm brands the SAME chip as both "Mali-Gxxx" and "Immortalis-Gxxx" (and the
        // dual-branded "Mali-G720-Immortalis"), so fold them into one family or an exact-GPU report
        // never matches. Check the more specific brands before the generic "mali".
        val vendor = when {
            s.contains("adreno") -> "adreno"
            s.contains("xclipse") -> "xclipse"
            s.contains("immortalis") || s.contains("mali") -> "arm-mali"
            s.contains("powervr") -> "powervr"
            s.contains("tegra") || s.contains("nvidia") || s.contains("geforce") -> "nvidia"
            s.contains("intel") -> "intel"
            else -> null
        }
        // First 2-4 digit run is the model number (Mali "g615" -> 615, Adreno "740" -> 740).
        val number = Regex("(\\d{2,4})").find(s)?.value?.toIntOrNull()
        return Gpu(vendor, number)
    }

    /**
     * Tier of [emuGpuModel] relative to [deviceGpu]. Same model -> EXACT; same vendor & same hundreds
     * generation -> FAMILY; same vendor but the reported GPU is weaker (lower number) -> LOWER (a
     * weaker GPU succeeding is reassuring for a stronger one); everything else (different vendor,
     * stronger GPU, or unparseable) -> OTHER.
     */
    fun tier(deviceGpu: String?, emuGpuModel: String?): Tier {
        val d = normalize(deviceGpu)
        val e = normalize(emuGpuModel)
        if (d.vendor == null || e.vendor == null || d.vendor != e.vendor) return Tier.OTHER
        if (d.number == null || e.number == null) return Tier.FAMILY // same vendor, unknown number
        return when {
            d.number == e.number -> Tier.EXACT
            d.number / 100 == e.number / 100 -> Tier.FAMILY // same generation (e.g. 740 & 730)
            e.number < d.number -> Tier.LOWER // reported on a weaker GPU than the user's
            else -> Tier.OTHER // reported on a stronger GPU, or far off
        }
    }
}
