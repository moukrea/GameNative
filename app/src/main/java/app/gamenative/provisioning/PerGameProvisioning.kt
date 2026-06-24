package app.gamenative.provisioning

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.provisioning.engine.FilePrefixState
import app.gamenative.provisioning.engine.GameHubBaseline
import app.gamenative.provisioning.engine.PrefixState
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
import app.gamenative.provisioning.verbs.AppVerbContext
import app.gamenative.provisioning.verbs.DataDrivenVerb
import app.gamenative.provisioning.verbs.VerbRegistry
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PreInstallSteps
import com.winlator.container.Container
import kotlinx.coroutines.runBlocking
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

        // 1b. Per-Steam-AppId WINE_* tuning (GameHub's jgm table) — applies to ANY Steam game, not
        //     just recipe-matched ones. Set-if-absent so a user/recipe value is never overwritten.
        if (source == GameSource.STEAM) {
            gameId.toLongOrNull()?.let { appNum ->
                val tuning = AppIdEnvTuning.envFor(appNum)
                if (tuning.isNotEmpty()) {
                    tuning.forEach { (k, v) -> if (state.getEnv(k) == null) state.setEnv(k, v) }
                    state.commit()
                    Timber.tag(TAG).i("Applied per-appId env tuning for $appNum: ${tuning.keys}")
                }
            }
        }

        // 1c. DLL-drop verbs (host-side file placement) — wires the previously-dead verb engine.
        //     Only verbs that actually place files run here (raw-DLL verbs like d3dcompiler_47);
        //     installer verbs are handled in-guest by ProvisioningDepsStep, and the cab-packed
        //     DirectX family by the DXSETUP installer path, so we never double-download.
        placeDllDropVerbs(container, state, GameHubBaseline.recipe?.dependencies.orEmpty())

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

        // NOTE: we intentionally do NOT touch the container's Steam-DRM toggles here. GameNative
        // already owns DRM via its container settings (Use Legacy DRM / Unpack Files / real Steam),
        // and overriding them from the recipe made those settings appear to "do nothing". DRM is the
        // user's to choose; provisioning only handles runtimes/config/tuning.
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

        // Explicit user action: force-apply the recipe's recommended DRM mode now (variant-aware),
        // so "Re-apply provisioning" reliably sets it even when the set-once launch path was skipped
        // (e.g. an older build already wrote a different strategy to the container).
        resolveDrmSpec(context, appId, container)?.takeIf { it.strategy != SteamDrmStrategy.AUTO }?.let { spec ->
            val effective = effectiveStrategy(container, spec.strategy)
            applyDrmStrategy(container, effective, spec.unpack)
            container.putExtra(DRM_APPLIED_EXTRA, effective.name)
            container.saveData()
            parts += "DRM ${effective.name.lowercase()}"
        }

        val deps = LinkedHashSet<String>()
        baseline?.dependencies?.let { deps += it }
        resolved?.recipe?.dependencies?.let { deps += it }
        val installers = ProvisioningInstallers.installerVerbs(deps.toList())
        Timber.tag(TAG).i("Re-apply '$appId': ${parts.joinToString("; ")}; installers=$installers")

        // Download the runtimes NOW (watchable, on demand) into the shared staging dir the launch
        // installer reads, then clear the marker so the install actually re-runs next launch. This is
        // additive: ProvisioningDepsStep finds the files already staged.
        val staged = prefetchRuntimes(container, installers, onProgress)
        PreInstallSteps.clearProvisioningDepsMarker(container)

        return buildString {
            append("Provisioning applied (")
            append(parts.joinToString(" · "))
            append("). ")
            if (installers.isNotEmpty()) {
                append("Downloaded $staged/${installers.size} runtime installer(s). They run on the next ")
                append("launch — then open 'Provisioning status' to confirm what actually landed in the prefix.")
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
        val downloaded = installers.count { verb ->
            File(stagingRoot, verb).listFiles()?.any { it.isFile && it.length() > 0 } == true
        }

        // GROUND TRUTH: don't trust the completion marker (it's written even if the install failed).
        // Check whether each runtime's signature DLL is actually present in the prefix system dirs.
        val sys32 = File(container.rootDir, ".wine/drive_c/windows/system32")
        val syswow = File(container.rootDir, ".wine/drive_c/windows/syswow64")
        fun present(dll: String) = File(sys32, dll).isFile || File(syswow, dll).isFile
        val checks = installers.mapNotNull { verb ->
            RUNTIME_SIGNATURE[verb]?.let { sig -> verb to sig.any { present(it) } }
        }
        val verifiedPresent = checks.count { it.second }
        val verifiable = checks.size

        return buildString {
            append(if (resolved != null) "Recipe '${resolved.recipe.id}'" else "Baseline only")
            append(" · ${installers.size} runtime installer(s) configured · downloaded $downloaded/${installers.size}. ")
            if (verifiable > 0) {
                val presentNames = checks.filter { it.second }.joinToString(", ") { it.first }
                append("Actually present in the prefix: $verifiedPresent/$verifiable")
                if (verifiedPresent > 0) append(" ($presentNames)")
                append(". ")
            }
            append("DRM is controlled by the container's own settings (provisioning no longer changes it). ")
            append(
                if (verifiedPresent == 0) {
                    "No runtimes detected in the prefix yet — launch the game once (or tap Re-apply provisioning), then check again."
                } else {
                    "(.NET/XNA aren't DLL-verifiable here; staged under drive_c/.gnprov/.)"
                },
            )
        }
    }

    /** Signature DLLs proving a runtime is actually installed in the prefix (system32/syswow64). */
    private val RUNTIME_SIGNATURE: Map<String, List<String>> = mapOf(
        "vcrun2005" to listOf("msvcr80.dll", "msvcp80.dll"),
        "vcrun2008" to listOf("msvcr90.dll", "msvcp90.dll"),
        "vcrun2010" to listOf("msvcr100.dll", "msvcp100.dll"),
        "vcrun2012" to listOf("msvcr110.dll", "msvcp110.dll"),
        "vcrun2013" to listOf("msvcr120.dll", "msvcp120.dll"),
        "vcrun2015" to listOf("msvcp140.dll", "vcruntime140.dll"),
        "vcrun2017" to listOf("msvcp140.dll", "vcruntime140.dll"),
        "vcrun2019" to listOf("msvcp140.dll", "vcruntime140.dll"),
        "vcrun2022" to listOf("msvcp140.dll", "vcruntime140.dll"),
        "physx" to listOf("PhysXLoader.dll", "physxcudart_20.dll"),
        "d3dx9" to listOf("d3dx9_43.dll", "d3dx9_42.dll"),
        // dotnet40/48 / xna40 install to the GAC/registry, not a checkable system32 DLL — omitted.
    )

    /**
     * Runs the DLL-drop verbs (those declaring `placeFiles`) host-side via the verb engine + a real
     * [AppVerbContext], so their DLLs actually land in the prefix system dirs. Only file-placing verbs
     * run here (installer verbs are handled in-guest by ProvisioningDepsStep), so there's no
     * double-download. Best-effort and non-fatal: a failed verb is logged, never aborts launch.
     */
    private fun placeDllDropVerbs(container: Container, state: PrefixState, deps: List<String>) {
        if (deps.isEmpty()) return
        val registry = VerbRegistry.builtin()
        val placeVerbs = deps.distinct()
            .mapNotNull { registry.get(it) as? DataDrivenVerb }
            .filter { it.definition.placeFiles.isNotEmpty() }
        if (placeVerbs.isEmpty()) return
        runCatching {
            runBlocking {
                val ctx = AppVerbContext(File(container.rootDir, ".wine/drive_c/.gnverb"))
                placeVerbs.forEach { verb ->
                    val o = verb.install(state, ctx)
                    Timber.tag(TAG).i(
                        "DLL-drop verb '%s': installed=%b skipped=%b err=%s",
                        verb.name, o.installed, o.skipped, o.error,
                    )
                }
                state.commit()
            }
        }.onFailure { Timber.tag(TAG).w(it, "verb-engine DLL placement failed (non-fatal)") }
    }

    /**
     * Applies a per-game recipe's recommended Steam DRM mode to the container, called from the launch
     * entry point BEFORE GameNative chooses its DRM path. Re-applies only when the recommendation
     * CHANGES (tracked via an extra holding the last-applied strategy): on a fresh container, or when
     * an older build wrote a different strategy, it (re)applies; once the current recommendation is in
     * place it never touches the container again, so the user's manual DRM choices are respected.
     * CEG titles route to the headless bionic-Steam path (variant-aware — see [effectiveStrategy]).
     */
    fun applyRecommendedDrmOnce(context: Context, appId: String, container: Container) {
        if (!PrefManager.enablePerGameProvisioning) return
        val spec = resolveDrmSpec(context, appId, container) ?: return
        if (spec.strategy == SteamDrmStrategy.AUTO) return
        val effective = effectiveStrategy(container, spec.strategy)
        if (container.getExtra(DRM_APPLIED_EXTRA, "") == effective.name) return // recommendation already applied
        applyDrmStrategy(container, effective, spec.unpack)
        container.putExtra(DRM_APPLIED_EXTRA, effective.name)
        container.saveData()
        Timber.tag(TAG).i("Applied DRM '$effective' for '$appId' (recipe recommended ${spec.strategy})")
    }

    /**
     * Clears the "DRM recommendation already applied" marker so [applyRecommendedDrmOnce] will re-apply
     * the recipe's strategy on the next launch. Call this when a container is RESET to defaults: the
     * reset flips isLaunchBionicSteam/RealSteam back to false but leaves this marker behind, so without
     * clearing it the set-once guard would early-return and never re-enable the CEG path — the game
     * would silently fall back to the Goldberg/cold path and black-screen. (A manual DRM toggle change
     * deliberately does NOT clear this — only an explicit reset does — so user choices still win.)
     */
    fun clearDrmRecommendationMarker(container: Container) {
        container.putExtra(DRM_APPLIED_EXTRA, null)
        container.saveData()
    }

    /** Resolves the recipe's Steam DRM spec for a launched Steam/Epic game (null if none). */
    private fun resolveDrmSpec(context: Context, appId: String, container: Container): SteamDrmSpec? {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return null
        val matchId = when (source) {
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId.toInt())?.catalogId ?: return null
            else -> gameId
        }
        return RecipeResolver(
            sources = listOf(UserRecipeSource(context), GameHubCatalogSource, MigratedFixCatalogSource),
            cache = PrefixRecipeCache(container),
        ).resolve(source, matchId)?.recipe?.steamDrm
    }

    /**
     * bionic-Steam only works on a BIONIC-variant container (the toggle is hidden otherwise and the
     * launch path ignores it). On a GLIBC container, fall back to real-Steam — also a genuine,
     * CEG-capable Valve client, just with a window.
     */
    private fun effectiveStrategy(container: Container, strategy: SteamDrmStrategy): SteamDrmStrategy =
        if (strategy == SteamDrmStrategy.BIONIC_STEAM &&
            !container.containerVariant.equals(Container.BIONIC, ignoreCase = true)
        ) {
            SteamDrmStrategy.REAL_STEAM
        } else {
            strategy
        }

    /** Maps a (already variant-resolved) DRM strategy onto the container's mutually-exclusive toggles. */
    private fun applyDrmStrategy(container: Container, strategy: SteamDrmStrategy, unpack: Boolean) {
        when (strategy) {
            SteamDrmStrategy.AUTO -> Unit
            SteamDrmStrategy.LEGACY_GOLDBERG -> {
                container.setLaunchRealSteam(false); container.setLaunchBionicSteam(false)
                container.setUseLegacyDRM(true); container.setUnpackFiles(unpack)
            }
            SteamDrmStrategy.COLD_CLIENT -> {
                container.setLaunchRealSteam(false); container.setLaunchBionicSteam(false)
                container.setUseLegacyDRM(false); container.setUnpackFiles(unpack)
            }
            SteamDrmStrategy.REAL_STEAM -> {
                container.setLaunchBionicSteam(false); container.setUseLegacyDRM(false)
                container.setUnpackFiles(false); container.setLaunchRealSteam(true)
            }
            SteamDrmStrategy.BIONIC_STEAM -> {
                container.setLaunchRealSteam(false); container.setUseLegacyDRM(false)
                container.setUnpackFiles(false); container.setLaunchBionicSteam(true)
            }
        }
    }

    private const val DRM_APPLIED_EXTRA = "provisioning.drmApplied"

    /** Per-runtime download budget for the on-demand prefetch. */
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L
}
