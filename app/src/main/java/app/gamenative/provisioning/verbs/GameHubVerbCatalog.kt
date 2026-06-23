package app.gamenative.provisioning.verbs

import app.gamenative.provisioning.ProvisioningAssets
import app.gamenative.provisioning.model.Provenance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The dependency-verb catalog reverse-engineered from GameHub's component registry (114 dependency
 * components, which are the winetricks verb set) seeded with faithful install definitions from the
 * open-source winetricks project (download URLs, SHA-256, guest install commands, DLL overrides).
 *
 * Loaded from a bundled JSON resource. Facts only — no GameHub code; the redistributables are
 * downloaded at runtime from their official vendors, never bundled. See `THIRD_PARTY_NOTICES`.
 */
object GameHubVerbCatalog {
    private const val RESOURCE = "gamehub-verbs.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CatalogVerb(
        val name: String,
        val installKind: String = "other",
        val downloads: List<VerbDownload> = emptyList(),
        val installerCommands: List<String> = emptyList(),
        val dllOverrides: Map<String, String> = emptyMap(),
        val placedDlls: List<String> = emptyList(),
    )

    val definitions: List<VerbDefinition> by lazy { load() }

    private fun load(): List<VerbDefinition> {
        val text = ProvisioningAssets.readText(RESOURCE) ?: return emptyList()
        val verbs = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CatalogVerb.serializer()), text)
        val provenance = Provenance(source = "winetricks", confidence = "reported", contributor = "gamehub-component-catalog")
        return verbs.map { v ->
            VerbDefinition(
                name = v.name,
                description = "${v.installKind} dependency (winetricks-seeded)",
                downloads = v.downloads,
                dllOverrides = v.dllOverrides,
                // Installer verbs run their command(s) in the guest, chained; cabextract/dll verbs
                // at minimum contribute their DLL overrides (binary placement is a follow-up).
                installerCommand = v.installerCommands.takeIf { it.isNotEmpty() }?.joinToString(" && "),
                provenance = provenance,
            )
        }
    }
}
