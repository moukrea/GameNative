package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.ComponentKind
import app.gamenative.provisioning.model.RegistryHive

/**
 * In-memory [PrefixState] for headless tests. Records every mutation so the provisioning engine,
 * verbs and migrated recipes can be applied and asserted without a device or real Wine.
 */
open class InMemoryPrefixState : PrefixState {
    val env = LinkedHashMap<String, String>()
    val pins = LinkedHashMap<ComponentKind, String>()
    val registry = LinkedHashMap<String, String>()
    val files = LinkedHashMap<String, ByteArray>()
    private var launchArgsStore: String? = null
    val markers = LinkedHashSet<String>()
    var commits = 0

    private fun rk(hive: RegistryHive, key: String, name: String): String = "${hive.name}|$key|$name"

    override fun getEnv(name: String): String? = env[name]
    override fun setEnv(name: String, value: String) {
        env[name] = value
    }
    override fun removeEnv(name: String) {
        env.remove(name)
    }

    override fun getComponentPin(kind: ComponentKind): String? = pins[kind]
    override fun setComponentPin(kind: ComponentKind, id: String) {
        pins[kind] = id
    }

    override fun getRegistryString(hive: RegistryHive, key: String, name: String): String? = registry[rk(hive, key, name)]
    override fun setRegistryString(hive: RegistryHive, key: String, name: String, value: String) {
        registry[rk(hive, key, name)] = value
    }
    override fun setRegistryDword(hive: RegistryHive, key: String, name: String, value: Int) {
        registry[rk(hive, key, name)] = value.toString()
    }
    override fun removeRegistryValue(hive: RegistryHive, key: String, name: String) {
        registry.remove(rk(hive, key, name))
    }

    override fun fileExists(driveCRelativePath: String): Boolean = files.containsKey(driveCRelativePath)
    override fun readFile(driveCRelativePath: String): ByteArray? = files[driveCRelativePath]
    override fun writeFile(driveCRelativePath: String, bytes: ByteArray) {
        files[driveCRelativePath] = bytes
    }
    override fun deletePath(driveCRelativePath: String) {
        files.remove(driveCRelativePath)
        files.keys.toList().filter { it.startsWith("$driveCRelativePath/") }.forEach { files.remove(it) }
    }

    override fun getLaunchArgs(): String? = launchArgsStore
    override fun setLaunchArgs(args: String) {
        launchArgsStore = args
    }

    override fun isMarked(marker: String): Boolean = markers.contains(marker)
    override fun mark(marker: String) {
        markers.add(marker)
    }
    override fun unmark(marker: String) {
        markers.remove(marker)
    }

    override fun commit() {
        commits++
    }

    fun recipeMarkers(): List<String> = markers.filter { it.startsWith(RECIPE_MARKER_PREFIX) }
}
