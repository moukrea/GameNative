package app.gamenative.utils

import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.provisioning.ProvisioningInstallers
import app.gamenative.provisioning.engine.GameHubBaseline
import app.gamenative.provisioning.resolver.GameHubCatalogSource
import app.gamenative.provisioning.resolver.MigratedFixCatalogSource
import app.gamenative.provisioning.verbs.DataDrivenVerb
import app.gamenative.provisioning.verbs.VerbDownload
import app.gamenative.provisioning.verbs.VerbRegistry
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Provisions the Wine prefix with the common Windows-runtime dependencies before launch — the core
 * of GameHub's superior compatibility, reverse-engineered (clean-room) and ported.
 *
 * GameHub bakes the winetricks dependency set (VC++ 2005-2022, PhysX, .NET, XNA, …) into its base
 * imagefs, so games that need those runtimes boot under GameHub but fail on a vanilla prefix. This
 * step downloads the same redistributables from their official vendors at launch and runs their
 * silent installers in the guest via GameNative's existing pre-install chain. Nothing is bundled or
 * redistributed; only the *facts* (which runtime, from where, with which silent flag) are reused.
 *
 * Opt-in: runs only when [PrefManager.enablePerGameProvisioning] is on. Installers are staged once
 * per prefix under `C:\.gnprov\<verb>\` (so they are not re-downloaded for every game in a
 * container) and gated by a per-game marker so each game provisions at most once.
 */
object ProvisioningDepsStep : PreInstallStep {
    private const val TAG = "ProvisioningDeps"

    /** Per-file overall download budget; a slower transfer is dropped so launch is never wedged. */
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L

    override val marker: Marker = Marker.PROVISIONING_DEPS_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean = PrefManager.enablePerGameProvisioning &&
        // Defer ONLY to the full real-Steam client (steam.exe -applaunch): that path runs the game's
        // _CommonRedist install scripts itself, so our installers would collide with it. The bionic
        // path does NOT — it launches the game exe DIRECTLY (XServerScreen getWineStartCommand bionic
        // branch), so Steam never runs the redist scripts there; nothing else provisions the runtimes.
        // GameHub likewise always runs its blocking dependency phase pre-launch regardless of Steam
        // mode (its host-side Steam agent only grants the launch). So run for bionic + emulator paths.
        !container.isLaunchRealSteam &&
        !MarkerUtils.hasMarker(gameDirPath, marker)

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val verbs = ProvisioningInstallers.installerVerbs(resolveDependencies(appId, gameSource))
        if (verbs.isEmpty()) return null

        val registry = VerbRegistry.builtin()
        // Shared per-prefix staging under drive_c so deps download once per container, not per game.
        val stagingRoot = File(container.rootDir, ".wine/drive_c/.gnprov")
        val staged = mutableListOf<ProvisioningInstallers.Staged>()
        val skipped = mutableListOf<String>()

        verbs.forEachIndexed { index, verb ->
            val def = (registry.get(verb) as? DataDrivenVerb)?.definition
            if (def == null) {
                Timber.tag(TAG).w("Verb '%s' is not installable in the registry; skipping", verb)
                skipped += verb
                return@forEachIndexed
            }
            val runnable = def.downloads.filter { ProvisioningInstallers.isRunnable(it.fileName) }
            if (runnable.isEmpty()) return@forEachIndexed

            // Visible feedback: downloads are synchronous and can be large, so surface progress on
            // the boot splash instead of leaving the screen looking frozen.
            splash("Preparing dependencies: $verb (${index + 1}/${verbs.size})…")

            val verbDir = File(stagingRoot, verb).apply { mkdirs() }
            val verbStaged = mutableListOf<ProvisioningInstallers.Staged>()
            var ok = true
            for (dl in runnable) {
                val dest = File(verbDir, dl.fileName)
                if (!ensureDownloaded(dest, dl)) {
                    ok = false
                    break
                }
                verbStaged += ProvisioningInstallers.Staged(
                    verb = verb,
                    fileName = dl.fileName,
                    guestPath = "C:\\.gnprov\\$verb\\${dl.fileName}",
                )
            }
            if (ok) {
                staged += verbStaged
            } else {
                skipped += verb
                Timber.tag(TAG).w("Skipping verb '%s' (download/verify failed)", verb)
            }
        }

        // Install whatever DID stage rather than discarding the entire chain when one verb fails.
        // The previous all-or-nothing return-null meant a single flaky CDN (e.g. the archived XNA
        // mirror) silently zeroed PhysX + VC++ + DirectX too — the user saw "nothing installed".
        // Staged files are cached under the shared per-prefix dir, so a reinstall (fresh game dir,
        // no marker) re-attempts only the verbs that are still missing.
        if (skipped.isNotEmpty()) {
            Timber.tag(TAG).w("Provisioning partial for %s: installing %d verb file(s); skipped %s",
                appId, staged.size, skipped.joinToString(","))
        }
        if (staged.isEmpty()) {
            Timber.tag(TAG).w("Nothing staged for %s (all downloads failed); deferring to next launch", appId)
            return null
        }
        Timber.tag(TAG).i("Provisioning %d installer file(s) for %s", staged.size, appId)
        return ProvisioningInstallers.chain(staged)
    }

    /** Baseline (always) + per-game GameHub/migrated recipe dependency verbs, de-duplicated. */
    private fun resolveDependencies(appId: String, gameSource: GameSource): List<String> {
        val deps = LinkedHashSet<String>()
        GameHubBaseline.recipe?.dependencies?.let { deps += it }
        matchIdFor(appId, gameSource)?.let { matchId ->
            GameHubCatalogSource.recipeFor(gameSource, matchId)?.dependencies?.let { deps += it }
            MigratedFixCatalogSource.recipeFor(gameSource, matchId)?.dependencies?.let { deps += it }
        }
        return deps.toList()
    }

    private fun matchIdFor(appId: String, gameSource: GameSource): String? {
        val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }.getOrNull() ?: return null
        if (gameId <= 0) return null
        return if (gameSource == GameSource.EPIC) {
            EpicService.getEpicGameOf(gameId)?.catalogId
        } else {
            gameId.toString()
        }
    }

    private fun ensureDownloaded(dest: File, dl: VerbDownload): Boolean {
        // A previously-staged file that is unreadable/corrupt must not abort the launch: guard the
        // verify so a throw falls through to a fresh download instead of propagating.
        if (dest.isFile && runCatching { verifies(dest, dl.sha256) }.getOrDefault(false)) return true
        return runCatching {
            // Bound the download so a stuck/slow CDN can't hang the launch indefinitely; on timeout
            // the verb is dropped (and the marker withheld), so the game still launches.
            val fetched = runBlocking {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    SteamService.fetchFile(dl.url, dest) { }
                    true
                } ?: false
            }
            fetched && verifies(dest, dl.sha256)
        }.getOrElse {
            Timber.tag(TAG).w(it, "download failed for %s", dl.url)
            dest.delete()
            false
        }
    }

    private fun verifies(file: File, sha256: String?): Boolean {
        if (sha256.isNullOrBlank()) return file.isFile && file.length() > 0
        return sha256Hex(file).equals(sha256, ignoreCase = true)
    }

    /** Surfaces provisioning progress on the boot splash (never fatal). */
    private fun splash(text: String) {
        runCatching { PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(text)) }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n >= 0) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
