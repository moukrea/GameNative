package app.gamenative.provisioning.resolver

import app.gamenative.data.GameSource
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RecipeMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeResolverTest {

    private fun recipe(id: String) = GameRecipe(id = id, match = RecipeMatch(GameSource.STEAM, "1"))

    private class FixedSource(override val name: String, private val recipe: GameRecipe?) : RecipeSource {
        override fun recipeFor(source: GameSource, appId: String): GameRecipe? = recipe
    }

    private class MemCache : RecipeCache {
        private val store = HashMap<String, GameRecipe>()
        override fun get(source: GameSource, appId: String): GameRecipe? = store["$source|$appId"]
        override fun put(source: GameSource, appId: String, recipe: GameRecipe) {
            store["$source|$appId"] = recipe
        }
    }

    @Test
    fun higherPrecedenceSourceWins() {
        val resolver = RecipeResolver(
            listOf(
                FixedSource("user", recipe("user")),
                FixedSource("community", recipe("community")),
                FixedSource("builtin", recipe("builtin")),
            ),
        )
        val resolved = resolver.resolve(GameSource.STEAM, "1")
        assertEquals("user", resolved?.recipe?.id)
        assertEquals("user", resolved?.sourceName)
    }

    @Test
    fun fallsThroughToLowerPrecedenceWhenHigherEmpty() {
        val resolver = RecipeResolver(
            listOf(
                FixedSource("user", null),
                FixedSource("community", null),
                FixedSource("builtin", recipe("builtin")),
            ),
        )
        val resolved = resolver.resolve(GameSource.STEAM, "1")
        assertEquals("builtin", resolved?.recipe?.id)
        assertFalse(resolved!!.fromCache)
    }

    @Test
    fun offlineFallsBackToCachedRecipe() {
        val cache = MemCache()

        val online = RecipeResolver(listOf(FixedSource("community", recipe("community"))), cache)
        assertEquals("community", online.resolve(GameSource.STEAM, "1")?.recipe?.id)

        val offline = RecipeResolver(listOf(FixedSource("community", null)), cache)
        val resolved = offline.resolve(GameSource.STEAM, "1")
        assertEquals("community", resolved?.recipe?.id)
        assertTrue(resolved!!.fromCache)
    }

    @Test
    fun returnsNullWhenNothingAvailableAndNoCache() {
        val resolver = RecipeResolver(listOf(FixedSource("community", null)))
        assertNull(resolver.resolve(GameSource.STEAM, "1"))
    }

    @Test
    fun builtinSourceServesMigratedCatalog() {
        // The Danganronpa 2 fix (STEAM 413420) was migrated; the built-in source must serve it.
        val resolved = MigratedFixCatalogSource.recipeFor(GameSource.STEAM, "413420")
        assertEquals("steam-413420", resolved?.id)
    }
}
