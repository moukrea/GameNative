package app.gamenative.provisioning.verbs

import app.gamenative.service.SteamService
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Production [VerbContext]: downloads a verb's payload via the app's downloader (SHA-256 verified
 * when a hash is pinned) and returns the bytes for [app.gamenative.provisioning.verbs.DataDrivenVerb]
 * to place into the prefix. Replaces the [NoopVerbContext] that made the whole verb-install path dead
 * code (its `fetch` threw, so no DLL-drop verb could ever run).
 *
 * Archive extraction (Microsoft `.cab`) is not implemented — there is no pure-JVM `.cab` reader — so
 * [extract] returns empty. Consequence: raw-DLL verbs (e.g. `d3dcompiler_47` from fxc2, which place a
 * downloaded DLL directly) work here; cab-packed DirectX DLLs (d3dx9 family) are instead installed by
 * the in-guest DXSETUP path in [app.gamenative.provisioning.ProvisioningInstallers]. This split is
 * documented in docs/gamehub-compat-mechanism.md (no faking — a verb that needs extraction records a
 * skip rather than pretending success).
 */
class AppVerbContext(private val stagingDir: File) : VerbContext {

    override suspend fun fetch(download: VerbDownload): ByteArray {
        stagingDir.mkdirs()
        val dest = File(stagingDir, download.fileName)
        if (!(dest.isFile && verifies(dest, download.sha256))) {
            val ok = runCatching {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    SteamService.fetchFile(download.url, dest) { }
                    true
                } ?: false
            }.getOrDefault(false)
            require(ok && verifies(dest, download.sha256)) { "fetch/verify failed: ${download.url}" }
        }
        return dest.readBytes()
    }

    override suspend fun extract(archive: ByteArray, entries: List<String>): Map<String, ByteArray> {
        Timber.tag("Provisioning").w("Archive extraction unsupported; skipping entries=%s", entries)
        return emptyMap()
    }

    private fun verifies(file: File, sha256: String?): Boolean {
        if (sha256.isNullOrBlank()) return file.isFile && file.length() > 0
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.equals(sha256, ignoreCase = true)
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
