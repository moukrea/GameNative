package app.gamenative.provisioning

import app.gamenative.data.GameSource
import app.gamenative.provisioning.engine.DllOverrides
import app.gamenative.provisioning.engine.InMemoryPrefixState
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.engine.ProvisioningResult
import app.gamenative.provisioning.engine.RecordingVerbContext
import app.gamenative.provisioning.model.ComponentKind
import app.gamenative.provisioning.model.ComponentPins
import app.gamenative.provisioning.model.DeviceCondition
import app.gamenative.provisioning.model.DeviceOverride
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.LaunchSpec
import app.gamenative.provisioning.model.RecipeMatch
import app.gamenative.provisioning.model.RecipeOverlay
import app.gamenative.provisioning.model.RegistryHive
import app.gamenative.provisioning.model.RegistryPatch
import app.gamenative.provisioning.model.RegistryValueType
import app.gamenative.provisioning.verbs.VerbRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningEngineTest {

    private val engine = ProvisioningEngine(VerbRegistry.builtin(), RecordingVerbContext())

    private fun sampleRecipe() = GameRecipe(
        id = "test-1",
        match = RecipeMatch(GameSource.STEAM, "1"),
        components = ComponentPins(dxvk = "2.4.1"),
        env = linkedMapOf("DXVK_FRAME_RATE" to "60"),
        dllOverrides = linkedMapOf("d3dx9_43" to "native,builtin"),
        registry = listOf(
            RegistryPatch(RegistryHive.SYSTEM, "Software\\Wine\\Direct3D", "csmt", RegistryValueType.DWORD, "0"),
        ),
        dependencies = listOf("d3dcompiler_47"),
        launch = LaunchSpec(args = "--vulkan"),
    )

    @Test
    fun appliesThenIsIdempotent() = runBlocking {
        val state = InMemoryPrefixState()

        val r1 = engine.apply(sampleRecipe(), DeviceProfile.UNKNOWN, state)
        assertTrue(r1 is ProvisioningResult.Applied)
        assertTrue((r1 as ProvisioningResult.Applied).complete)
        assertEquals("60", state.env["DXVK_FRAME_RATE"])
        assertEquals("2.4.1", state.pins[ComponentKind.DXVK])
        assertEquals("native,builtin", DllOverrides.parse(state.env["WINEDLLOVERRIDES"])["d3dx9_43"])
        assertEquals("0", state.registry["SYSTEM|Software\\Wine\\Direct3D|csmt"])
        assertEquals("--vulkan", state.getLaunchArgs())
        assertTrue(state.fileExists("windows/system32/d3dcompiler_47.dll"))
        assertEquals(1, state.recipeMarkers().size)
        assertEquals(1, state.commits)

        val envSnapshot = LinkedHashMap(state.env)
        val regSnapshot = LinkedHashMap(state.registry)

        val r2 = engine.apply(sampleRecipe(), DeviceProfile.UNKNOWN, state)
        assertTrue(r2 is ProvisioningResult.AlreadyApplied)
        assertEquals(envSnapshot, state.env)
        assertEquals(regSnapshot, state.registry)
    }

    private fun deviceRecipe() = GameRecipe(
        id = "test-dev",
        match = RecipeMatch(GameSource.STEAM, "2"),
        components = ComponentPins(dxvk = "2.4.1"),
        deviceOverrides = listOf(
            DeviceOverride(
                DeviceCondition(gpuFamily = "adreno6xx"),
                RecipeOverlay(components = ComponentPins(dxvk = "1.10.3")),
            ),
        ),
    )

    @Test
    fun deviceOverrideBranchesAndChangesHash() = runBlocking {
        val adreno6 = InMemoryPrefixState()
        engine.apply(deviceRecipe(), DeviceProfile(gpuFamily = "adreno6xx"), adreno6)
        assertEquals("1.10.3", adreno6.pins[ComponentKind.DXVK])

        val adreno7 = InMemoryPrefixState()
        engine.apply(deviceRecipe(), DeviceProfile(gpuFamily = "adreno7xx"), adreno7)
        assertEquals("2.4.1", adreno7.pins[ComponentKind.DXVK])

        assertNotEquals(adreno6.recipeMarkers().first(), adreno7.recipeMarkers().first())
    }

    @Test
    fun rollsBackEnvOnFailureAndDoesNotMark() = runBlocking {
        val throwing = object : InMemoryPrefixState() {
            override fun setRegistryString(hive: RegistryHive, key: String, name: String, value: String) {
                error("simulated registry write failure")
            }
        }
        val recipe = GameRecipe(
            id = "rollback",
            match = RecipeMatch(GameSource.STEAM, "3"),
            env = linkedMapOf("FOO" to "BAR"),
            registry = listOf(RegistryPatch(RegistryHive.SYSTEM, "K", "N", RegistryValueType.STRING, "V")),
        )

        val r = engine.apply(recipe, DeviceProfile.UNKNOWN, throwing)
        assertTrue(r is ProvisioningResult.Failed)
        assertEquals("registry", (r as ProvisioningResult.Failed).failedStep)
        assertNull(throwing.env["FOO"])
        assertTrue(throwing.recipeMarkers().isEmpty())
    }

    @Test
    fun dllOverrideMergePreservesExistingEntries() {
        val state = InMemoryPrefixState()
        state.setEnv("WINEDLLOVERRIDES", "icu=n")

        DllOverrides.apply(state, "icu", "b")
        assertEquals("icu=n", state.env["WINEDLLOVERRIDES"])

        DllOverrides.apply(state, "d3d11", "native")
        assertEquals("icu=n;d3d11=native", state.env["WINEDLLOVERRIDES"])
    }

    @Test
    fun verbFailureLeavesRecipeUnmarkedButBootsOffline() = runBlocking {
        val offline = RecordingVerbContext(failFor = setOf("d3dcompiler_47_32.dll"))
        val offlineEngine = ProvisioningEngine(VerbRegistry.builtin(), offline)
        val recipe = GameRecipe(
            id = "offline",
            match = RecipeMatch(GameSource.STEAM, "4"),
            env = linkedMapOf("A" to "B"),
            dependencies = listOf("d3dcompiler_47"),
        )
        val state = InMemoryPrefixState()

        val r = offlineEngine.apply(recipe, DeviceProfile.UNKNOWN, state)
        assertTrue(r is ProvisioningResult.Applied)
        assertFalse((r as ProvisioningResult.Applied).complete)
        assertEquals("B", state.env["A"])
        assertTrue(state.recipeMarkers().isEmpty())
    }
}
