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
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PreInstallSteps
import com.winlator.container.Container
import timber.log.Timber

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
    fun reapplyNow(context: Context, appId: String): String {
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

        return buildString {
            append("Provisioning applied (")
            append(parts.joinToString(" · "))
            append("). ")
            if (installers.isNotEmpty()) {
                append("${installers.size} runtime(s) install on next launch: ")
                append(installers.joinToString(", "))
            } else {
                append("No runtime installers required.")
            }
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
}
