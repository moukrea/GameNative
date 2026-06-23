package app.gamenative.provisioning.model

import app.gamenative.data.GameSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Current major version of the per-game recipe schema.
 *
 * A recipe whose [GameRecipe.schemaVersion] exceeds this value is rejected by
 * [app.gamenative.provisioning.schema.RecipeValidator]; older minor versions are migrated forward.
 */
const val RECIPE_SCHEMA_VERSION: Int = 1

/**
 * Declarative, versioned description of everything a single game needs in its Wine prefix before
 * boot: pinned open-source components, environment variables, DLL overrides, registry patches,
 * winetricks-style dependency verbs, file writes, cleanups, launch arguments and per-device
 * overrides. This is also the catalog interchange format.
 *
 * A recipe never embeds binaries: components are referenced by their `manifest.json` id and
 * dependency verbs are referenced by name (resolved against the verb registry).
 */
@Serializable
data class GameRecipe(
    val schemaVersion: Int = RECIPE_SCHEMA_VERSION,
    val id: String,
    val match: RecipeMatch,
    val prefixArch: PrefixArch = PrefixArch.WIN64,
    val components: ComponentPins = ComponentPins(),
    val env: Map<String, String> = emptyMap(),
    val dllOverrides: Map<String, String> = emptyMap(),
    val registry: List<RegistryPatch> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val files: List<PrefixFile> = emptyList(),
    val iniPatches: List<IniPatch> = emptyList(),
    val cleanup: Cleanup = Cleanup(),
    val launch: LaunchSpec = LaunchSpec(),
    val steamDrm: SteamDrmSpec? = null,
    val deviceOverrides: List<DeviceOverride> = emptyList(),
    val provenance: Provenance? = null,
)

/**
 * Per-game Steam DRM strategy. Steam-DRM-wrapped games fail to boot ("Application load error
 * 3:0000065432") unless a Steam client/emulator answers the ownership check. GameNative already
 * bundles the open-source Goldberg `steam_api` emulator, a cold-client `steamclient` loader and a
 * Steamless de-stubber, but never picks one per game. This lets a recipe encode which existing path
 * a known title needs — the appid-keyed selection that is otherwise missing. It only selects an
 * already-shipped mechanism; it bundles nothing new and copies no third-party code.
 */
@Serializable
enum class SteamDrmStrategy {
    /** Leave the container's existing DRM toggles untouched. */
    @SerialName("auto")
    AUTO,

    /** Goldberg `steam_api(64).dll` replacement (best for older Steamworks titles). */
    @SerialName("legacy_goldberg")
    LEGACY_GOLDBERG,

    /** Cold-client `steamclient` loader (GameNative's default path). */
    @SerialName("cold_client")
    COLD_CLIENT,

    /** Run the user's real Steam client in the prefix. */
    @SerialName("real_steam")
    REAL_STEAM,
}

/**
 * A recipe's Steam-DRM decision. Applied by [app.gamenative.provisioning.PerGameProvisioning] to the
 * container's existing DRM toggles ([com.winlator.container.Container.setUseLegacyDRM] etc.) so the
 * game takes the path it needs. [unpack] additionally enables the Steamless de-stub pass.
 */
@Serializable
data class SteamDrmSpec(
    val strategy: SteamDrmStrategy = SteamDrmStrategy.AUTO,
    val unpack: Boolean = false,
    val note: String = "",
)

/** Identity and matching rules used to bind a recipe to a launched game. */
@Serializable
data class RecipeMatch(
    val source: GameSource,
    val appId: String,
    val exeNameContains: String? = null,
    val exeSha256: String? = null,
)

/** Windows architecture the prefix targets. Informational; WoW64 is handled by the container. */
@Serializable
enum class PrefixArch {
    @SerialName("win32")
    WIN32,

    @SerialName("win64")
    WIN64,
}

/** Pinned component builds, each referencing an id from `manifest.json` (null = leave default). */
@Serializable
data class ComponentPins(
    val proton: String? = null,
    val wine: String? = null,
    val dxvk: String? = null,
    val vkd3d: String? = null,
    val driver: String? = null,
    val box64: String? = null,
    val fexcore: String? = null,
    val wowbox64: String? = null,
) {
    /** True when no component is pinned. */
    fun isEmpty(): Boolean = asMap().isEmpty()

    /** Returns the non-null pins keyed by [ComponentKind]. */
    fun asMap(): Map<ComponentKind, String> = buildMap {
        proton?.let { put(ComponentKind.PROTON, it) }
        wine?.let { put(ComponentKind.WINE, it) }
        dxvk?.let { put(ComponentKind.DXVK, it) }
        vkd3d?.let { put(ComponentKind.VKD3D, it) }
        driver?.let { put(ComponentKind.DRIVER, it) }
        box64?.let { put(ComponentKind.BOX64, it) }
        fexcore?.let { put(ComponentKind.FEXCORE, it) }
        wowbox64?.let { put(ComponentKind.WOWBOX64, it) }
    }

    /** Returns a copy where any null field is taken from [other] (this recipe wins on conflict). */
    fun overlayOnto(other: ComponentPins): ComponentPins = ComponentPins(
        proton = proton ?: other.proton,
        wine = wine ?: other.wine,
        dxvk = dxvk ?: other.dxvk,
        vkd3d = vkd3d ?: other.vkd3d,
        driver = driver ?: other.driver,
        box64 = box64 ?: other.box64,
        fexcore = fexcore ?: other.fexcore,
        wowbox64 = wowbox64 ?: other.wowbox64,
    )
}

/**
 * Component kinds, aligned 1:1 with the keys used in `manifest.json`
 * (see [app.gamenative.utils.ManifestContentTypes]).
 */
@Serializable
enum class ComponentKind(val manifestKey: String) {
    @SerialName("proton")
    PROTON("proton"),

    @SerialName("wine")
    WINE("wine"),

    @SerialName("dxvk")
    DXVK("dxvk"),

    @SerialName("vkd3d")
    VKD3D("vkd3d"),

    @SerialName("driver")
    DRIVER("driver"),

    @SerialName("box64")
    BOX64("box64"),

    @SerialName("fexcore")
    FEXCORE("fexcore"),

    @SerialName("wowbox64")
    WOWBOX64("wowbox64"),
}

/** A single Wine registry value to upsert. Applied idempotently via WineRegistryEditor. */
@Serializable
data class RegistryPatch(
    val hive: RegistryHive,
    val key: String,
    val name: String,
    val type: RegistryValueType = RegistryValueType.STRING,
    val value: String,
)

@Serializable
enum class RegistryHive {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,
}

@Serializable
enum class RegistryValueType {
    @SerialName("string")
    STRING,

    @SerialName("dword")
    DWORD,
}

/**
 * A file to materialize inside the prefix, relative to `drive_c`. Either [content] (verbatim text,
 * kept diffable) is written, or [iniMerge] merges key/values into an existing INI by section.
 */
@Serializable
data class PrefixFile(
    val driveCRelativePath: String,
    val content: String? = null,
    val iniMerge: Map<String, Map<String, String>>? = null,
)

/**
 * A flat `key=value` patch applied to an INI file inside the game's install directory (not the
 * prefix), mirroring the existing IniFileFix. Existing keys are updated in place; missing keys are
 * appended.
 */
@Serializable
data class IniPatch(
    val relativePath: String,
    val values: Map<String, String> = emptyMap(),
)

/** Paths (relative to `drive_c`) to delete before launch, e.g. stale DRM/bootstrap state. */
@Serializable
data class Cleanup(
    val deletePaths: List<String> = emptyList(),
)

/** Launch tuning: extra arguments and an optional wrapper command. */
@Serializable
data class LaunchSpec(
    val args: String? = null,
    val wrapper: String? = null,
)

/** A conditional overlay applied when the running device matches [whenCondition]. */
@Serializable
data class DeviceOverride(
    @SerialName("when")
    val whenCondition: DeviceCondition,
    val set: RecipeOverlay,
)

/** Predicates on the running device profile. Empty conditions are rejected by the validator. */
@Serializable
data class DeviceCondition(
    val gpuFamily: String? = null,
    val socContains: String? = null,
    val driverContains: String? = null,
) {
    fun isEmpty(): Boolean = gpuFamily == null && socContains == null && driverContains == null
}

/** Partial recipe fields a [DeviceOverride] can set. Merged onto the base recipe. */
@Serializable
data class RecipeOverlay(
    val components: ComponentPins? = null,
    val env: Map<String, String> = emptyMap(),
    val dllOverrides: Map<String, String> = emptyMap(),
    val registry: List<RegistryPatch> = emptyList(),
    val dependencies: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean =
        (components?.isEmpty() != false) &&
            env.isEmpty() &&
            dllOverrides.isEmpty() &&
            registry.isEmpty() &&
            dependencies.isEmpty()
}

/** Where a recipe came from and how trustworthy it is. Facts only — never proprietary payloads. */
@Serializable
data class Provenance(
    val source: String,
    val confidence: String? = null,
    val contributor: String? = null,
    val verifiedOn: String? = null,
    val device: String? = null,
)
