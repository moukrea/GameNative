package app.gamenative.provisioning

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.provisioning.engine.FilePrefixState
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
 * Dependency verbs (which download redistributables / run guest installers) are intentionally NOT
 * run on this synchronous launch path; that is a follow-up using the asynchronous verb engine.
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

        val resolver = RecipeResolver(
            sources = listOf(UserRecipeSource(context), GameHubCatalogSource, MigratedFixCatalogSource),
            cache = PrefixRecipeCache(container),
        )
        val resolved = resolver.resolve(source, matchId)
        if (resolved == null) {
            // No recipe for this game: keep the legacy behaviour.
            GameFixesRegistry.applyFor(context, appId, container)
            return
        }

        val result = ProvisioningEngine().applyDeclarative(resolved.recipe, DeviceProfile.UNKNOWN, FilePrefixState(container))
        Timber.tag(TAG).i("Applied recipe '${resolved.recipe.id}' from ${resolved.sourceName}: $result")
    }
}
