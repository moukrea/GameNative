package app.gamenative.provisioning.resolver

import app.gamenative.data.GameSource
import app.gamenative.provisioning.engine.GameHubCatalog
import app.gamenative.provisioning.engine.MigratedFixCatalog
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RecipeCodec
import com.winlator.container.Container

/** Built-in recipes migrated from the legacy GameFixesRegistry. */
object MigratedFixCatalogSource : RecipeSource {
    override val name: String = "migrated"
    override fun recipeFor(source: GameSource, appId: String): GameRecipe? =
        MigratedFixCatalog.forGame(source, appId)
}

/** Built-in per-game recipes derived from the open GameHub Lite / BannerHub catalog. */
object GameHubCatalogSource : RecipeSource {
    override val name: String = "gamehub"
    override fun recipeFor(source: GameSource, appId: String): GameRecipe? =
        GameHubCatalog.forGame(source, appId)
}

/**
 * Durable per-game recipe cache backed by the container's extras. Stores the last resolved recipe
 * as JSON so the game can be provisioned offline on a later launch.
 */
class PrefixRecipeCache(private val container: Container) : RecipeCache {
    override fun get(source: GameSource, appId: String): GameRecipe? {
        val json = container.getExtra(KEY, "")
        if (json.isEmpty()) return null
        return runCatching { RecipeCodec.decode(json) }.getOrNull()
    }

    override fun put(source: GameSource, appId: String, recipe: GameRecipe) {
        container.putExtra(KEY, RecipeCodec.encode(recipe))
        container.saveData()
    }

    companion object {
        private const val KEY = "provisioning.cachedRecipe"
    }
}
