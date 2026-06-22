package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.ComponentKind
import app.gamenative.provisioning.model.RegistryHive

/**
 * Narrow, mockable abstraction over the operations the provisioning engine performs on a Wine
 * prefix and its container.
 *
 * The production implementation [FilePrefixState] is backed by the container (env vars, extras,
 * launch args), [com.winlator.core.WineRegistryEditor] (system.reg/user.reg) and the prefix
 * filesystem. Tests use an in-memory implementation so the engine, verbs and migrated recipes can
 * be exercised entirely headlessly — no device, no real Wine. This is what makes the programmatic
 * conformance oracle possible.
 *
 * All file paths are relative to the prefix's `drive_c`.
 */
interface PrefixState {

    // --- Environment variables (container.envVars) ---
    fun getEnv(name: String): String?
    fun setEnv(name: String, value: String)
    fun removeEnv(name: String)

    // --- Component pins (recorded; the launch resolver materializes them via ManifestInstaller) ---
    fun getComponentPin(kind: ComponentKind): String?
    fun setComponentPin(kind: ComponentKind, id: String)

    // --- Wine registry ---
    fun getRegistryString(hive: RegistryHive, key: String, name: String): String?
    fun setRegistryString(hive: RegistryHive, key: String, name: String, value: String)
    fun setRegistryDword(hive: RegistryHive, key: String, name: String, value: Int)
    fun removeRegistryValue(hive: RegistryHive, key: String, name: String)

    // --- Files relative to drive_c ---
    fun fileExists(driveCRelativePath: String): Boolean
    fun readFile(driveCRelativePath: String): ByteArray?
    fun writeFile(driveCRelativePath: String, bytes: ByteArray)
    fun deletePath(driveCRelativePath: String)

    fun readText(driveCRelativePath: String): String? = readFile(driveCRelativePath)?.decodeToString()
    fun writeText(driveCRelativePath: String, text: String) = writeFile(driveCRelativePath, text.encodeToByteArray())

    /**
     * Applies flat `key=value` updates to an INI file in the game's install directory (not the
     * prefix). No-op when the game directory is unknown (e.g. the game is not installed).
     */
    fun patchGameIni(relativePath: String, values: Map<String, String>)

    // --- Launch args (container.execArgs) ---
    fun getLaunchArgs(): String?
    fun setLaunchArgs(args: String)

    // --- Idempotency markers (container.extraData) ---
    fun isMarked(marker: String): Boolean
    fun mark(marker: String)
    fun unmark(marker: String)

    /** Flush any buffered changes (env, extras, launch args) to durable storage. */
    fun commit()
}
