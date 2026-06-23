package app.gamenative.provisioning

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.provisioning.engine.FilePrefixState
import app.gamenative.provisioning.engine.GameHubBaseline
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.resolver.GameHubCatalogSource
import app.gamenative.provisioning.resolver.MigratedFixCatalogSource
import app.gamenative.provisioning.resolver.PrefixRecipeCache
import app.gamenative.provisioning.resolver.RecipeResolver
import app.gamenative.provisioning.resolver.UserRecipeSource
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ContainerUtils
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
    }
}
