package app.gamenative.provisioning

import app.gamenative.provisioning.engine.DllOverrides
import app.gamenative.provisioning.engine.InMemoryPrefixState
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.engine.ProvisioningResult
import app.gamenative.provisioning.engine.RecordingVerbContext
import app.gamenative.provisioning.model.ComponentKind
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.schema.RecipeValidator
import app.gamenative.provisioning.verbs.VerbRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance harness: applies a full declarative recipe to an in-memory prefix and asserts the
 * resulting state (component pins, env, DLL overrides, registry, files, launch args, verbs) — no
 * device, no real Wine.
 */
class ProvisioningConformanceTest {

    @Test
    fun fullRecipeAppliesToExpectedPrefixState() = runBlocking {
        val recipe = RecipeCodec.decode(RecipeFixtures.load("valid_full.json"))
        assertTrue(RecipeValidator.validate(recipe).isValid)

        val engine = ProvisioningEngine(VerbRegistry.builtin(), RecordingVerbContext())
        val state = InMemoryPrefixState()

        val result = engine.apply(recipe, DeviceProfile.UNKNOWN, state)
        assertTrue(result is ProvisioningResult.Applied)
        result as ProvisioningResult.Applied
        assertTrue("verbs should all resolve: ${result.verbOutcomes}", result.complete)

        assertEquals("60", state.env["DXVK_FRAME_RATE"])
        assertEquals("0", state.env["BOX64_DYNAREC_BIGBLOCK"])

        val overrides = DllOverrides.parse(state.env["WINEDLLOVERRIDES"])
        assertEquals("native,builtin", overrides["d3dx9_43"])
        assertEquals("n,b", overrides["d3dcompiler_43"])
        assertEquals("d", overrides["icu"])

        assertEquals("0", state.registry["SYSTEM|Software\\Wine\\Direct3D|csmt"])
        assertEquals("baz", state.registry["USER|Software\\Foo|Bar"])

        assertEquals("proton-10.0-arm64ec-2", state.pins[ComponentKind.PROTON])
        assertEquals("2.4.1", state.pins[ComponentKind.DXVK])

        assertEquals("--rendering-driver vulkan", state.getLaunchArgs())

        val ini = requireNotNull(state.readText("users/Public/Documents/cfg.ini"))
        assertTrue(ini.contains("[Render]"))
        assertTrue(ini.contains("vsync=0"))

        assertTrue(result.pendingCommands.any { it.contains("vc_redist") })
    }
}
