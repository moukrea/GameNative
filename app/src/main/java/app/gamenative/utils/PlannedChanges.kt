package app.gamenative.utils

import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet

/** One settings change a known-config would make to the container: human label, current -> new. */
data class PlannedChange(val label: String, val current: String, val next: String)

/**
 * Computes the "planned changes" preview for a known config — what WILL change in the container if the
 * user applies it. It applies the parsed field map to a COPY of the current container data and diffs a
 * curated, human-readable set of fields, so the preview is exactly what the real apply does (including
 * the dxwrapperConfig -> DXVK/VKD3D version collapse). Only changed fields are returned.
 */
object PlannedChanges {

    fun compute(current: ContainerData, intended: Map<String, Any?>): List<PlannedChange> {
        if (intended.isEmpty()) return emptyList()
        val updated = ContainerUtils.applyBestConfigMapToContainerData(current, intended)
        val out = mutableListOf<PlannedChange>()

        fun add(label: String, cur: String?, nxt: String?) {
            val c = cur.orEmpty()
            val n = nxt.orEmpty()
            if (c != n) out.add(PlannedChange(label, c, n))
        }
        fun onOff(b: Boolean) = if (b) "on" else "off"

        add("Container variant", current.containerVariant, updated.containerVariant)
        add("Wine / Proton", current.wineVersion, updated.wineVersion)
        add("32-bit emulator", current.emulator, updated.emulator)
        add("Graphics driver", current.graphicsDriver, updated.graphicsDriver)
        add("Driver version", current.graphicsDriverVersion, updated.graphicsDriverVersion)
        add("DX wrapper", current.dxwrapper, updated.dxwrapper)
        add("DXVK version", dxvkVersion(current), dxvkVersion(updated))
        add("VKD3D version", vkd3dVersion(current), vkd3dVersion(updated))
        add("Box64", current.box64Version, updated.box64Version)
        add("Box64 preset", current.box64Preset, updated.box64Preset)
        add("FEXCore", current.fexcoreVersion, updated.fexcoreVersion)
        add("FEX preset", current.fexcorePreset, updated.fexcorePreset)
        add("WoW64 mode", onOff(current.wow64Mode), onOff(updated.wow64Mode))
        add("Audio driver", current.audioDriver, updated.audioDriver)
        add("Video memory", current.videoMemorySize, updated.videoMemorySize)
        add("Windows components", current.wincomponents, updated.wincomponents)
        add("Executable", current.executablePath, updated.executablePath)
        add("Launch args", current.execArgs, updated.execArgs)
        // envVars can be huge — report that it changes without dumping the whole blob.
        if (current.envVars.trim() != updated.envVars.trim()) {
            out.add(PlannedChange("Environment variables", "(current)", "(updated)"))
        }
        return out
    }

    /** The DXVK version inside dxwrapperConfig, only when the wrapper is dxvk; else "". */
    private fun dxvkVersion(c: ContainerData): String =
        if (c.dxwrapper.equals("dxvk", ignoreCase = true) && c.dxwrapperConfig.isNotBlank()) {
            runCatching { KeyValueSet(c.dxwrapperConfig).get("version", "") }.getOrDefault("")
        } else {
            ""
        }

    /** The VKD3D version inside dxwrapperConfig, only when the wrapper is vkd3d; else "". */
    private fun vkd3dVersion(c: ContainerData): String =
        if (c.dxwrapper.equals("vkd3d", ignoreCase = true) && c.dxwrapperConfig.isNotBlank()) {
            runCatching { KeyValueSet(c.dxwrapperConfig).get("vkd3dVersion", "") }.getOrDefault("")
        } else {
            ""
        }
}
