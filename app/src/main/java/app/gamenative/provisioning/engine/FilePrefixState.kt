package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.ComponentKind
import app.gamenative.provisioning.model.RegistryHive
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

/**
 * Production [PrefixState] backed by a [Container]: env vars and launch args live on the container,
 * idempotency markers and component pins in its extras, registry in the prefix's system.reg/user.reg
 * (via [WineRegistryEditor]), and files under the prefix `drive_c`.
 *
 * Buffered changes (env, extras, launch args) are flushed on [commit] via `Container.saveData()`;
 * registry and file writes are applied immediately.
 */
class FilePrefixState(
    private val container: Container,
    private val gameInstallDir: File? = null,
) : PrefixState {

    private val env = EnvVars(container.envVars)
    private val wineDir = File(container.rootDir, ".wine")
    private val driveC = File(wineDir, "drive_c")

    override fun getEnv(name: String): String? = if (env.has(name)) env.get(name) else null

    override fun setEnv(name: String, value: String) = env.put(name, value)

    override fun removeEnv(name: String) = env.remove(name)

    override fun getComponentPin(kind: ComponentKind): String? =
        container.getExtra(pinKey(kind), "").ifEmpty { null }

    override fun setComponentPin(kind: ComponentKind, id: String) = container.putExtra(pinKey(kind), id)

    override fun getRegistryString(hive: RegistryHive, key: String, name: String): String? =
        WineRegistryEditor(regFile(hive)).use { it.getStringValue(key, name, null) }

    override fun setRegistryString(hive: RegistryHive, key: String, name: String, value: String) {
        WineRegistryEditor(regFile(hive)).use {
            it.setCreateKeyIfNotExist(true)
            it.setStringValue(key, name, value)
        }
    }

    override fun setRegistryDword(hive: RegistryHive, key: String, name: String, value: Int) {
        WineRegistryEditor(regFile(hive)).use {
            it.setCreateKeyIfNotExist(true)
            it.setDwordValue(key, name, value)
        }
    }

    override fun removeRegistryValue(hive: RegistryHive, key: String, name: String) {
        // WineRegistryEditor exposes only whole-key removal; single-value rollback is best-effort.
        Timber.tag(TAG).d("removeRegistryValue not supported for %s\\%s; leaving value in place", key, name)
    }

    override fun fileExists(driveCRelativePath: String): Boolean = File(driveC, driveCRelativePath).exists()

    override fun readFile(driveCRelativePath: String): ByteArray? {
        val file = File(driveC, driveCRelativePath)
        return if (file.isFile) file.readBytes() else null
    }

    override fun writeFile(driveCRelativePath: String, bytes: ByteArray) {
        val file = File(driveC, driveCRelativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun deletePath(driveCRelativePath: String) {
        File(driveC, driveCRelativePath).deleteRecursively()
    }

    override fun patchGameIni(relativePath: String, values: Map<String, String>) {
        val dir = gameInstallDir
        if (dir == null) {
            Timber.tag(TAG).d("patchGameIni skipped: game install dir unknown (%s)", relativePath)
            return
        }
        val ini = File(dir, relativePath)
        if (!ini.isFile) return
        var content = ini.readText()
        for ((key, value) in values) {
            val regex = Regex("(?im)^(${Regex.escape(key)}\\s*=\\s*).*$")
            content = if (regex.containsMatchIn(content)) {
                content.replace(regex, "$1$value")
            } else {
                val suffix = if (content.endsWith("\n") || content.isEmpty()) "" else "\n"
                content + suffix + "$key=$value\n"
            }
        }
        ini.writeText(content)
    }

    override fun getLaunchArgs(): String? = container.execArgs

    override fun setLaunchArgs(args: String) = container.setExecArgs(args)

    override fun isMarked(marker: String): Boolean = container.getExtra(marker, "") == "1"

    override fun mark(marker: String) = container.putExtra(marker, "1")

    override fun unmark(marker: String) = container.putExtra(marker, "0")

    override fun commit() {
        container.envVars = env.toString()
        container.saveData()
    }

    private fun regFile(hive: RegistryHive): File =
        File(wineDir, if (hive == RegistryHive.SYSTEM) "system.reg" else "user.reg")

    private fun pinKey(kind: ComponentKind): String = "provisioning.pin.${kind.manifestKey}"

    companion object {
        private const val TAG = "Provisioning"
    }
}
