package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.RegistryPatch
import app.gamenative.provisioning.model.RegistryValueType

/** Name of the `WINEDLLOVERRIDES` environment variable carried in `container.envVars`. */
const val WINE_DLL_OVERRIDES_ENV: String = "WINEDLLOVERRIDES"

/**
 * Helpers for merging DLL overrides into the `WINEDLLOVERRIDES` env var, using Wine's canonical
 * `;`-separated `dll=mode` syntax (matching the convention used across the repo's game fixes).
 */
object DllOverrides {

    /** Parses a `WINEDLLOVERRIDES` value into an ordered `dll -> mode` map. */
    fun parse(value: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (value.isNullOrBlank()) return out
        for (entry in value.split(';')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq < 0) {
                out[trimmed] = ""
            } else {
                out[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
            }
        }
        return out
    }

    /** Serializes a `dll -> mode` map back to a `WINEDLLOVERRIDES` value. */
    fun serialize(overrides: Map<String, String>): String =
        overrides.entries.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Adds `dll=mode` to the prefix's `WINEDLLOVERRIDES`, preserving any pre-existing entry for that
     * DLL (so user/earlier choices win). Idempotent: re-applying the same override is a no-op.
     */
    fun apply(state: PrefixState, dll: String, mode: String) {
        val current = parse(state.getEnv(WINE_DLL_OVERRIDES_ENV))
        if (current.containsKey(dll)) return
        current[dll] = mode
        state.setEnv(WINE_DLL_OVERRIDES_ENV, serialize(current))
    }
}

/**
 * Applies a single [RegistryPatch] to the prefix, only when the target value is currently absent or
 * empty — mirroring the idempotent, user-respecting behaviour of the existing `RegistryKeyFix`.
 */
fun applyRegistryPatch(state: PrefixState, patch: RegistryPatch) {
    val existing = state.getRegistryString(patch.hive, patch.key, patch.name)
    if (!existing.isNullOrEmpty()) return
    when (patch.type) {
        RegistryValueType.STRING -> state.setRegistryString(patch.hive, patch.key, patch.name, patch.value)
        RegistryValueType.DWORD -> state.setRegistryDword(patch.hive, patch.key, patch.name, parseDword(patch.value))
    }
}

/** Parses a registry dword literal (decimal or `0x`-hex) into its 32-bit value. */
fun parseDword(value: String): Int {
    val v = value.trim()
    val asLong = if (v.startsWith("0x", ignoreCase = true)) v.substring(2).toLong(16) else v.toLong(10)
    return asLong.toInt()
}
