package app.gamenative.provisioning.resolver

import app.gamenative.data.GameSource
import app.gamenative.provisioning.model.GameRecipe

/** A source of per-game recipes (user overrides, community catalog, built-in, …). */
interface RecipeSource {
    val name: String

    /** A recipe for the game, or null if this source has none or is unavailable (e.g. offline). */
    fun recipeFor(source: GameSource, appId: String): GameRecipe?
}

/** Per-game cache of the last successfully resolved recipe, enabling offline-safe relaunch. */
interface RecipeCache {
    fun get(source: GameSource, appId: String): GameRecipe?
    fun put(source: GameSource, appId: String, recipe: GameRecipe)
}

/** A no-op cache (used when no durable cache is available). */
object NoopRecipeCache : RecipeCache {
    override fun get(source: GameSource, appId: String): GameRecipe? = null
    override fun put(source: GameSource, appId: String, recipe: GameRecipe) = Unit
}

/** A recipe resolved for a game, with the source it came from. */
data class ResolvedRecipe(
    val recipe: GameRecipe,
    val sourceName: String,
    val fromCache: Boolean,
)

/**
 * Resolves the effective recipe for a game.
 *
 * - **Precedence**: [sources] are consulted highest-precedence first (e.g. user override > community
 *   catalog > built-in default); the first that yields a recipe wins.
 * - **Offline-safe**: a successful resolution is written to [cache]; if every source is unavailable
 *   (e.g. offline), the last cached recipe is used so a game launched once online still launches
 *   offline. The resolver never fails closed — it returns null only when nothing is available at
 *   all, in which case the caller falls back to the legacy path.
 */
class RecipeResolver(
    private val sources: List<RecipeSource>,
    private val cache: RecipeCache = NoopRecipeCache,
) {
    fun resolve(source: GameSource, appId: String): ResolvedRecipe? {
        for (recipeSource in sources) {
            val recipe = runCatching { recipeSource.recipeFor(source, appId) }.getOrNull() ?: continue
            cache.put(source, appId, recipe)
            return ResolvedRecipe(recipe, recipeSource.name, fromCache = false)
        }
        val cached = cache.get(source, appId) ?: return null
        return ResolvedRecipe(cached, "cache", fromCache = true)
    }
}
