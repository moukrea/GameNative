package app.gamenative.provisioning.engine

import app.gamenative.provisioning.verbs.VerbContext
import app.gamenative.provisioning.verbs.VerbDownload

/**
 * Fake [VerbContext] for tests: returns canned (or stub) bytes for downloads and archive entries,
 * so verb execution can be exercised without network access or real archives.
 */
class RecordingVerbContext(
    private val blobs: Map<String, ByteArray> = emptyMap(),
    private val archiveEntries: Map<String, ByteArray> = emptyMap(),
    private val failFor: Set<String> = emptySet(),
) : VerbContext {
    val fetched = mutableListOf<String>()

    override suspend fun fetch(download: VerbDownload): ByteArray {
        fetched += download.fileName
        if (download.fileName in failFor) error("simulated download failure: ${download.fileName}")
        return blobs[download.fileName] ?: "stub:${download.fileName}".encodeToByteArray()
    }

    override suspend fun extract(archive: ByteArray, entries: List<String>): Map<String, ByteArray> =
        entries.associateWith { archiveEntries[it] ?: "stub-entry:$it".encodeToByteArray() }
}
