package app.gamenative.provisioning

import app.gamenative.data.GameSource
import app.gamenative.provisioning.engine.GameHubBaseline
import app.gamenative.provisioning.engine.GameHubCatalog
import app.gamenative.provisioning.engine.InMemoryPrefixState
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.engine.ProvisioningResult
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.SteamDrmStrategy
import app.gamenative.provisioning.schema.RecipeValidator
import app.gamenative.provisioning.verbs.GameHubVerbCatalog
import app.gamenative.provisioning.verbs.VerbRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ported GameHub compatibility mechanism: the full winetricks-seeded dependency verb catalog
 * and the GameHub baseline (common deps + Box64/FEX tuning).
 */
class GameHubMechanismTest {

    @Test
    fun verbCatalogLoadsFullDependencySet() {
        val defs = GameHubVerbCatalog.definitions
        assertTrue("expected the full dependency catalog, got ${defs.size}", defs.size >= 90)
        val names = defs.map { it.name }.toSet()
        listOf("vcrun2010", "vcrun2022", "physx", "dotnet48", "d3dx9", "xact", "openal", "xna40").forEach {
            assertTrue("catalog missing verb $it", names.contains(it))
        }
        val vcrun2010 = defs.first { it.name == "vcrun2010" }
        assertTrue("vcrun2010 should have downloads", vcrun2010.downloads.isNotEmpty())
        assertNotNull("vcrun2010 should have an install command", vcrun2010.installerCommand)
        assertTrue("vcrun2010 should override msvcr100", vcrun2010.dllOverrides.containsKey("msvcr100"))
    }

    @Test
    fun registryExposesGameHubVerbs() {
        val registry = VerbRegistry.builtin()
        listOf("vcrun2010", "physx", "dotnet48", "xna40", "openal").forEach {
            assertTrue("registry missing $it", registry.has(it))
        }
    }

    @Test
    fun baselineIsValidAppliesTuningAndResolvesAllDeps() {
        val baseline = requireNotNull(GameHubBaseline.recipe) { "baseline recipe should load" }
        assertTrue("baseline invalid: ${RecipeValidator.validate(baseline).errors}", RecipeValidator.validate(baseline).isValid)

        val registry = VerbRegistry.builtin()
        baseline.dependencies.forEach {
            assertTrue("baseline dependency '$it' not in verb registry", registry.has(it))
        }

        val state = InMemoryPrefixState()
        val result = ProvisioningEngine().applyDeclarative(baseline, DeviceProfile.UNKNOWN, state)
        assertTrue("baseline failed to apply: $result", result is ProvisioningResult.Applied)
        // GameHub Wine/DXVK env vars that GameNative didn't set (verbatim from bg5.smali).
        assertEquals("1", state.env["DXVK_ASYNC"])
        assertEquals("warn", state.env["DXVK_LOG_LEVEL"])
        // PROTON_DISABLE_LSTEAMCLIENT is deliberately NOT in the baseline: it suppresses Proton's
        // lsteamclient.dll bridge, which the bionic/real-Steam CEG path needs (it black-screened
        // Mirror's Edge). XServerScreen also scrubs any stale copy on the Steam-client launch paths.
        assertNull("baseline must not disable lsteamclient (breaks CEG/bionic-Steam)", state.env["PROTON_DISABLE_LSTEAMCLIENT"])
    }

    @Test
    fun mirrorsEdgeRecipeSelectsBionicSteamForCeg() {
        // Mirror's Edge is Steam CEG: only a genuine logged-in Valve client can decrypt it (Goldberg
        // and Steamless both cannot). The recipe routes it to GameNative's HEADLESS bionic-Steam
        // path (the GameHub-equivalent). Also exercises the @SerialName decode and asset load.
        val recipe = GameHubCatalog.forGame(GameSource.STEAM, "17410")
        assertNotNull("Mirror's Edge (17410) DRM recipe should be in the catalog", recipe)
        val drm = recipe!!.steamDrm
        assertNotNull("17410 should declare a Steam DRM strategy", drm)
        assertEquals(SteamDrmStrategy.BIONIC_STEAM, drm!!.strategy)
        assertTrue("recipe must validate", RecipeValidator.validate(recipe).isValid)
    }
}
