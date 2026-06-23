package app.gamenative.provisioning

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.provisioning.engine.FilePrefixState
import app.gamenative.provisioning.engine.GameHubBaseline
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.engine.ProvisioningResult
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.SteamDrmSpec
import app.gamenative.provisioning.model.SteamDrmStrategy
import app.gamenative.provisioning.resolver.GameHubCatalogSource
import app.gamenative.provisioning.resolver.MigratedFixCatalogSource
import app.gamenative.provisioning.resolver.PrefixRecipeCache
import app.gamenative.provisioning.resolver.RecipeResolver
import app.gamenative.provisioning.resolver.UserRecipeSource
import app.gamenative.provisioning.verbs.DataDrivenVerb
import app.gamenative.provisioning.verbs.VerbRegistry
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PreInstallSteps
import com.winlator.container.Container
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/**
 * Launch-time entry point for the declarative per-game provisioning pipeline (opt-in).
 *
 * Resolves the recipe for the game by precedence (user override > built-in migrated catalog),
 * caching the result for offline relaunch, then applies its declarative state (components, env,
 * DLL overrides, registry, files, INI patches, cleanup, launch args) to the container/prefix. Falls
 * back to the legacy [GameFixesRegistry] when no recipe matches, so nothing regresses.
 *
 * This synchronous path applies only the declarative state (config/env/overrides/registry/files).
 * The dependency *installers* (download each redistributable + run its silent installer in the Wine
 * guest — the core of GameHub's compatibility) are run separately by
 * [app.gamenative.utils.ProvisioningDepsStep] through the existing pre-install chain, which can run
 * guest programs before the game starts.
 */
object PerGameProvisioning {
    private const val TAG = "Provisioning"

    fun applyAtLaunch(context: Context, appId: String, container: Container) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return
        val matchId = when (source) {
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId.toInt())?.catalogId ?: return
            else -> gameId
        }

        val engine = ProvisioningEngine()
        val state = FilePrefixState(container)

        // 1. GameHub baseline (common Windows-runtime deps + Box64/FEX tuning) applied under any
        //    per-game recipe — the default-prefix provisioning that makes many games boot.
        GameHubBaseline.recipe?.let {
            val baseResult = engine.applyDeclarative(it, DeviceProfile.UNKNOWN, state)
            Timber.tag(TAG).i("Applied GameHub baseline: $baseResult")
        }

        // 2. Per-game recipe (user override > GameHub catalog > migrated fixes).
        val resolver = RecipeResolver(
            sources = listOf(UserRecipeSource(context), GameHubCatalogSource, MigratedFixCatalogSource),
            cache = PrefixRecipeCache(container),
        )
        val resolved = resolver.resolve(source, matchId)
        if (resolved == null) {
            // No per-game recipe: keep the legacy fixes on top of the baseline.
            GameFixesRegistry.applyFor(context, appId, container)
            return
        }

        val result = engine.applyDeclarative(resolved.recipe, DeviceProfile.UNKNOWN, state)
        Timber.tag(TAG).i("Applied recipe '${resolved.recipe.id}' from ${resolved.sourceName}: $result")

        // Apply the recipe's Steam DRM strategy ONCE per container (set-if-not-yet-applied), so a
        // known DRM title auto-selects the right path on its first provisioned launch without
        // clobbering a mode the user later changes manually.
        resolved.recipe.steamDrm?.let { spec ->
            if (container.getExtra(DRM_APPLIED_EXTRA, "").isEmpty()) {
                val desc = applySteamDrm(container, spec)
                container.putExtra(DRM_APPLIED_EXTRA, spec.strategy.name)
                container.saveData()
                Timber.tag(TAG).i("Applied Steam DRM strategy ($desc) for '$appId'")
            }
        }
    }

    /**
     * User-triggered re-apply from the per-game menu ("Re-apply provisioning"). This is the explicit
     * "force" action: it force-applies the baseline + per-game declarative state NOW (overwriting any
     * prior provisioning, unlike the passive set-if-absent launch path) and clears the dependency
     * marker so the Windows-runtime installers re-run on the next launch. Returns a human-readable
     * summary for a snackbar so the user can confirm exactly what provisioning will do.
     */
    suspend fun reapplyNow(context: Context, appId: String, onProgress: (String) -> Unit = {}): String {
        if (!PrefManager.enablePerGameProvisioning) {
            return "Per-game provisioning is off — enable it in Settings first."
        }
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString()
            ?: return "Provisioning: could not identify this game."
        val matchId = when (source) {
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId.toInt())?.catalogId ?: gameId
            else -> gameId
        }

        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val engine = ProvisioningEngine()
        val state = FilePrefixState(container)
        val parts = mutableListOf<String>()

        val baseline = GameHubBaseline.recipe
        if (baseline != null) {
            val r = engine.applyDeclarative(baseline, DeviceProfile.UNKNOWN, state, force = true)
            parts += if (r is ProvisioningResult.Applied) "baseline applied" else "baseline ok"
        } else {
            parts += "baseline unavailable"
        }

        val resolver = RecipeResolver(
            sources = listOf(UserRecipeSource(context), GameHubCatalogSource, MigratedFixCatalogSource),
            cache = PrefixRecipeCache(container),
        )
        val resolved = resolver.resolve(source, matchId)
        if (resolved != null) {
            engine.applyDeclarative(resolved.recipe, DeviceProfile.UNKNOWN, state, force = true)
            parts += "recipe '${resolved.recipe.id}' (${resolved.sourceName})"
        } else {
            parts += "no per-game recipe"
        }

        // Explicitly (re)apply the recipe's Steam DRM strategy — the user asked for it.
        resolved?.recipe?.steamDrm?.let { spec ->
            parts += applySteamDrm(container, spec)
            container.putExtra(DRM_APPLIED_EXTRA, spec.strategy.name)
            container.saveData()
        }

        // Clear the dependency marker so the runtime installers re-run on the next launch.
        PreInstallSteps.clearProvisioningDepsMarker(container)

        val deps = LinkedHashSet<String>()
        baseline?.dependencies?.let { deps += it }
        resolved?.recipe?.dependencies?.let { deps += it }
        val installers = ProvisioningInstallers.installerVerbs(deps.toList())
        Timber.tag(TAG).i("Re-apply '$appId': ${parts.joinToString("; ")}; installers=$installers")

        // Download the runtimes NOW (watchable proof, on demand) into the shared staging dir the
        // launch-time installer reads. This is purely additive: ProvisioningDepsStep is unchanged and
        // simply finds the files already present (skips re-download). Lets the user SEE it work
        // without launching a game.
        val staged = prefetchRuntimes(container, installers, onProgress)
        PreInstallSteps.clearProvisioningDepsMarker(container)

        return buildString {
            append("Provisioning applied (")
            append(parts.joinToString(" · "))
            append("). ")
            if (installers.isNotEmpty()) {
                append("Runtimes downloaded $staged/${installers.size} (")
                append(installers.joinToString(", "))
                append("); they install on next launch.")
            } else {
                append("No runtime installers required.")
            }
        }
    }

    /**
     * Downloads the resolved installer runtimes into the shared per-prefix staging dir, reporting
     * per-runtime progress. Integrity is (re)checked by [app.gamenative.utils.ProvisioningDepsStep]
     * at launch, so this is a best-effort accelerator + on-demand "it works" proof. Returns how many
     * runtimes are fully staged.
     */
    private suspend fun prefetchRuntimes(
        container: Container,
        installers: List<String>,
        onProgress: (String) -> Unit,
    ): Int {
        if (installers.isEmpty()) return 0
        val registry = VerbRegistry.builtin()
        val stagingRoot = File(container.rootDir, ".wine/drive_c/.gnprov")
        var staged = 0
        installers.forEachIndexed { index, verb ->
            onProgress("Downloading runtime ${index + 1}/${installers.size}: $verb…")
            val def = (registry.get(verb) as? DataDrivenVerb)?.definition ?: return@forEachIndexed
            val files = def.downloads.filter { ProvisioningInstallers.isRunnable(it.fileName) }
            if (files.isEmpty()) return@forEachIndexed
            val verbDir = File(stagingRoot, verb).apply { mkdirs() }
            val ok = files.all { dl ->
                val dest = File(verbDir, dl.fileName)
                if (dest.isFile && dest.length() > 0) {
                    true
                } else {
                    runCatching {
                        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                            SteamService.fetchFile(dl.url, dest) { }
                            true
                        } ?: false
                    }.getOrElse {
                        Timber.tag(TAG).w(it, "prefetch failed for %s", dl.url)
                        dest.delete()
                        false
                    }
                }
            }
            if (ok) staged++
        }
        onProgress("Runtimes downloaded: $staged/${installers.size}.")
        return staged
    }

    /**
     * Read-only status for the per-game menu ("Provisioning status"). Reports GROUND TRUTH from disk
     * — which runtimes are actually staged under `drive_c/.gnprov/`, whether the install marker is
     * set, the resolved recipe and DRM mode — so the user can confirm what really happened rather
     * than rely on catching a launch-time splash.
     */
    fun statusSummary(context: Context, appId: String): String {
        if (!PrefManager.enablePerGameProvisioning) {
            return "Per-game provisioning is OFF — enable it in Settings, then 'Re-apply provisioning'."
        }
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString()
            ?: return "Provisioning: could not identify this game."
        val matchId = when (source) {
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId.toInt())?.catalogId ?: gameId
            else -> gameId
        }
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val resolved = RecipeResolver(
            sources = listOf(UserRecipeSource(context), GameHubCatalogSource, MigratedFixCatalogSource),
            cache = PrefixRecipeCache(container),
        ).resolve(source, matchId)

        val deps = LinkedHashSet<String>()
        GameHubBaseline.recipe?.dependencies?.let { deps += it }
        resolved?.recipe?.dependencies?.let { deps += it }
        val installers = ProvisioningInstallers.installerVerbs(deps.toList())

        val stagingRoot = File(container.rootDir, ".wine/drive_c/.gnprov")
        val staged = installers.count { verb ->
            File(stagingRoot, verb).listFiles()?.any { it.isFile && it.length() > 0 } == true
        }
        val installed = PreInstallSteps.isProvisioningMarked(container)
        val drm = resolved?.recipe?.steamDrm?.strategy?.name?.lowercase()
            ?: if (container.isUseLegacyDRM) "legacy (manual toggle)" else "default (cold-client)"

        return buildString {
            append("Provisioning ON. ")
            append("Recipe: ${resolved?.recipe?.id ?: "baseline only"}. ")
            append("Runtimes downloaded: $staged/${installers.size}. ")
            append("Installed in prefix: ${if (installed) "yes" else "not yet"}. ")
            append("DRM: $drm. ")
            append(
                when {
                    installed -> "Looks provisioned — see drive_c/.gnprov/."
                    staged > 0 -> "Downloaded; relaunch to finish installing."
                    else -> "Nothing yet — launch the game or tap 'Re-apply provisioning'."
                },
            )
        }
    }

    /**
     * Selects the existing GameNative Steam-DRM path the recipe asks for, by setting the container's
     * own DRM toggles (no new mechanism — Goldberg `steam_api`, the cold-client loader and Steamless
     * are all already shipped). Returns a short human description for the snackbar/log. The DRM
     * replace itself happens later, in the launch path, which reads these container fields.
     */
    private fun applySteamDrm(container: Container, spec: SteamDrmSpec): String = when (spec.strategy) {
        SteamDrmStrategy.AUTO -> "DRM: unchanged"
        SteamDrmStrategy.LEGACY_GOLDBERG -> {
            container.setLaunchRealSteam(false)
            container.setUseLegacyDRM(true)
            container.setUnpackFiles(spec.unpack)
            "DRM: legacy Goldberg steam_api" + if (spec.unpack) " + unpack" else ""
        }
        SteamDrmStrategy.COLD_CLIENT -> {
            container.setLaunchRealSteam(false)
            container.setUseLegacyDRM(false)
            container.setUnpackFiles(spec.unpack)
            "DRM: cold-client steamclient" + if (spec.unpack) " + unpack" else ""
        }
        SteamDrmStrategy.REAL_STEAM -> {
            container.setLaunchRealSteam(true)
            "DRM: real Steam client"
        }
    }

    private const val DRM_APPLIED_EXTRA = "provisioning.drmApplied"

    /** Per-runtime download budget for the on-demand prefetch. */
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L
}
