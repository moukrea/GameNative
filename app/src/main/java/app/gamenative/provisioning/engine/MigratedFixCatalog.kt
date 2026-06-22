package app.gamenative.provisioning.engine

import app.gamenative.data.GameSource
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RecipeCodec

/**
 * Built-in recipe catalog migrated from the legacy hardcoded `GameFixesRegistry`.
 *
 * The recipes are loaded from a bundled JSON resource so they are shareable and diffable rather
 * than baked into Kotlin. The launch resolver uses this as the lowest-precedence (built-in) recipe
 * source when the per-game provisioning flag is enabled; the legacy `GameFixesRegistry` remains the
 * fallback while the flag is off.
 */
object MigratedFixCatalog {
    private const val RESOURCE = "provisioning/migrated-fixes.json"

    val recipes: List<GameRecipe> by lazy { load() }

    private fun load(): List<GameRecipe> {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE) ?: return emptyList()
        return stream.bufferedReader().use { RecipeCodec.decodeList(it.readText()) }
    }

    fun forGame(source: GameSource, appId: String): GameRecipe? =
        recipes.firstOrNull { it.match.source == source && it.match.appId == appId }
}
