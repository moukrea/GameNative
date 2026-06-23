package app.gamenative.provisioning

import app.gamenative.data.GameSource
import app.gamenative.provisioning.engine.GameHubCatalog
import app.gamenative.provisioning.engine.InMemoryPrefixState
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.engine.ProvisioningResult
import app.gamenative.provisioning.engine.RecordingVerbContext
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.schema.RecipeValidator
import app.gamenative.provisioning.verbs.VerbRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance for the GameHub-derived recipe catalog: every recipe is schema-valid, round-trips,
 * is a Steam recipe with at least one config file, and applies to an in-memory prefix.
 */
class GameHubCatalogConformanceTest {

    @Test
    fun allGameHubRecipesAreValidApplyAndWriteConfigFiles() = runBlocking {
        val recipes = GameHubCatalog.recipes
        assertTrue("GameHub catalog should not be empty", recipes.size >= 19)

        val engine = ProvisioningEngine(VerbRegistry.builtin(), RecordingVerbContext())
        for (recipe in recipes) {
            val validation = RecipeValidator.validate(recipe)
            assertTrue("${recipe.id} invalid: ${validation.errors}", validation.isValid)
            assertEquals("${recipe.id} not round-tripping", recipe, RecipeCodec.decode(RecipeCodec.encode(recipe)))
            assertEquals("${recipe.id} should be a Steam recipe", GameSource.STEAM, recipe.match.source)
            assertTrue(
                "${recipe.id} should carry config files or a Steam DRM directive",
                recipe.files.isNotEmpty() || recipe.steamDrm != null,
            )

            val state = InMemoryPrefixState()
            val result = engine.applyDeclarative(recipe, DeviceProfile.UNKNOWN, state)
            assertTrue("${recipe.id} failed to apply: $result", result is ProvisioningResult.Applied)
            assertEquals("${recipe.id} should write its files", recipe.files.size, state.files.size)
        }
    }
}
