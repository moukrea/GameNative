package app.gamenative.provisioning.verbs

import app.gamenative.provisioning.engine.DllOverrides
import app.gamenative.provisioning.engine.PrefixState
import app.gamenative.provisioning.engine.applyRegistryPatch
import app.gamenative.provisioning.model.Provenance
import app.gamenative.provisioning.model.RegistryPatch
import kotlinx.serialization.Serializable

/**
 * Abstraction over fetching and extracting a verb's payload, so verbs can be exercised in unit
 * tests with canned bytes (no network, no real archives). The production implementation downloads
 * via the app's existing download stack and extracts with commons-compress.
 */
interface VerbContext {
    /** Fetches a remote resource; implementations verify [VerbDownload.sha256] when present. */
    suspend fun fetch(download: VerbDownload): ByteArray

    /** Extracts the named [entries] from an archive blob; returns entryPath -> bytes. */
    suspend fun extract(archive: ByteArray, entries: List<String>): Map<String, ByteArray>
}

/** A [VerbContext] that performs no I/O; used when only the declarative (no-verb) path runs. */
object NoopVerbContext : VerbContext {
    override suspend fun fetch(download: VerbDownload): ByteArray =
        error("NoopVerbContext cannot fetch ${download.fileName}; provide a real VerbContext to install verbs")

    override suspend fun extract(archive: ByteArray, entries: List<String>): Map<String, ByteArray> = emptyMap()
}

/**
 * A winetricks-style dependency verb: declares how to make a Windows redistributable present in the
 * prefix (place DLLs, set overrides, write registry keys, or run an installer).
 */
interface Verb {
    val name: String

    /** True when this verb has already been installed into [state] (idempotency check). */
    fun isSatisfied(state: PrefixState): Boolean

    /** Installs the verb into [state], or returns a skipped/failed outcome. Idempotent. */
    suspend fun install(state: PrefixState, ctx: VerbContext): VerbOutcome
}

/** Result of attempting to install a verb. */
data class VerbOutcome(
    val verb: String,
    val installed: Boolean,
    val skipped: Boolean,
    /** For installer-style verbs, a guest command the launcher must run (e.g. `wine msiexec ...`). */
    val command: String? = null,
    val error: String? = null,
)

@Serializable
data class VerbDownload(
    val url: String,
    val fileName: String,
    val sha256: String? = null,
)

/** A file to materialize into the prefix, optionally extracted from an archive download. */
@Serializable
data class PlacedFile(
    val fromFile: String,
    val archiveEntry: String? = null,
    val toPrefixPath: String,
)

/**
 * Declarative, data-driven verb definition.
 *
 * The *data* (download references, the files to place, the overrides and registry keys) is sourced
 * as fact from the winetricks project, which serves as the oracle of *what* a redistributable needs
 * and *where* it goes. Only references and facts are stored — never a proprietary binary. The
 * executor ([DataDrivenVerb]) is original Kotlin built on the app's own prefix machinery.
 */
@Serializable
data class VerbDefinition(
    val name: String,
    val description: String = "",
    val downloads: List<VerbDownload> = emptyList(),
    val placeFiles: List<PlacedFile> = emptyList(),
    val dllOverrides: Map<String, String> = emptyMap(),
    val registry: List<RegistryPatch> = emptyList(),
    /** For installer-style verbs, a guest command the launcher runs after files are staged. */
    val installerCommand: String? = null,
    val provenance: Provenance? = null,
)

/** Idempotency marker recorded in the prefix once [name] has been installed. */
fun verbMarker(name: String): String = "provisioning.verb.$name"

/** Executes a [VerbDefinition] against a [PrefixState]. */
class DataDrivenVerb(val definition: VerbDefinition) : Verb {

    override val name: String get() = definition.name

    override fun isSatisfied(state: PrefixState): Boolean = state.isMarked(verbMarker(name))

    override suspend fun install(state: PrefixState, ctx: VerbContext): VerbOutcome {
        if (isSatisfied(state)) {
            return VerbOutcome(name, installed = false, skipped = true, command = definition.installerCommand)
        }
        return try {
            val blobs = HashMap<String, ByteArray>()
            for (download in definition.downloads) {
                blobs[download.fileName] = ctx.fetch(download)
            }
            for (placed in definition.placeFiles) {
                val blob = blobs[placed.fromFile] ?: error("verb $name: missing download '${placed.fromFile}'")
                val bytes = if (placed.archiveEntry != null) {
                    ctx.extract(blob, listOf(placed.archiveEntry))[placed.archiveEntry]
                        ?: error("verb $name: archive entry '${placed.archiveEntry}' not found")
                } else {
                    blob
                }
                state.writeFile(placed.toPrefixPath, bytes)
            }
            for ((dll, mode) in definition.dllOverrides) {
                DllOverrides.apply(state, dll, mode)
            }
            for (patch in definition.registry) {
                applyRegistryPatch(state, patch)
            }
            state.mark(verbMarker(name))
            VerbOutcome(name, installed = true, skipped = false, command = definition.installerCommand)
        } catch (e: Exception) {
            VerbOutcome(name, installed = false, skipped = false, error = e.message ?: e.toString())
        }
    }
}
