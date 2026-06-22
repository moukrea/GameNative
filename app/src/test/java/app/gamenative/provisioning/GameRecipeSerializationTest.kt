package app.gamenative.provisioning

import app.gamenative.data.GameSource
import app.gamenative.provisioning.model.ComponentKind
import app.gamenative.provisioning.model.PrefixArch
import app.gamenative.provisioning.model.RECIPE_SCHEMA_VERSION
import app.gamenative.provisioning.model.RecipeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecipeSerializationTest {

    @Test
    fun decodesFullRecipe() {
        val r = RecipeCodec.decode(RecipeFixtures.load("valid_full.json"))

        assertEquals("steam-413420", r.id)
        assertEquals(GameSource.STEAM, r.match.source)
        assertEquals("413420", r.match.appId)
        assertEquals(PrefixArch.WIN64, r.prefixArch)
        assertEquals("2.4.1", r.components.dxvk)
        assertEquals("proton-10.0-arm64ec-2", r.components.proton)
        assertEquals("native,builtin", r.dllOverrides["d3dx9_43"])
        assertEquals("d", r.dllOverrides["icu"])
        assertEquals("", r.dllOverrides["mscoree"])
        assertEquals(2, r.registry.size)
        assertEquals(listOf("d3dx9_43", "vcrun2019"), r.dependencies)
        assertEquals("adreno6xx", r.deviceOverrides[0].whenCondition.gpuFamily)
        assertEquals("1.10.3", r.deviceOverrides[0].set.components?.dxvk)
        assertEquals(
            mapOf(ComponentKind.PROTON to "proton-10.0-arm64ec-2", ComponentKind.DXVK to "2.4.1"),
            r.components.asMap(),
        )
    }

    @Test
    fun decodesMinimalRecipeWithDefaults() {
        val r = RecipeCodec.decode(RecipeFixtures.load("valid_minimal.json"))

        assertEquals(RECIPE_SCHEMA_VERSION, r.schemaVersion)
        assertEquals(PrefixArch.WIN64, r.prefixArch)
        assertTrue(r.components.isEmpty())
        assertTrue(r.env.isEmpty())
        assertTrue(r.dependencies.isEmpty())
        assertTrue(r.cleanup.deletePaths.isEmpty())
    }

    @Test
    fun roundTripsThroughJson() {
        val original = RecipeCodec.decode(RecipeFixtures.load("valid_full.json"))
        val reDecoded = RecipeCodec.decode(RecipeCodec.encode(original))
        assertEquals(original, reDecoded)
    }

    @Test
    fun ignoresUnknownKeysForForwardCompatibility() {
        val json = """
            {
              "id": "x",
              "match": { "source": "GOG", "appId": "42" },
              "futureField": { "nested": true },
              "anotherFuture": [1, 2, 3]
            }
        """.trimIndent()

        val r = RecipeCodec.decode(json)

        assertEquals("x", r.id)
        assertEquals(GameSource.GOG, r.match.source)
    }
}
