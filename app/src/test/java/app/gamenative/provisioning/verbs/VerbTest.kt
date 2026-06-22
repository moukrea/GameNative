package app.gamenative.provisioning.verbs

import app.gamenative.provisioning.engine.DllOverrides
import app.gamenative.provisioning.engine.InMemoryPrefixState
import app.gamenative.provisioning.engine.RecordingVerbContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbTest {

    @Test
    fun dllPlacementVerbPlacesFilesSetsOverrideAndMarks() = runBlocking {
        val verb = DataDrivenVerb(BuiltinVerbs.D3DCOMPILER_47)
        val state = InMemoryPrefixState()
        val ctx = RecordingVerbContext()

        assertFalse(verb.isSatisfied(state))
        val outcome = verb.install(state, ctx)

        assertTrue(outcome.installed)
        assertFalse(outcome.skipped)
        assertTrue(state.fileExists("windows/system32/d3dcompiler_47.dll"))
        assertTrue(state.fileExists("windows/syswow64/d3dcompiler_47.dll"))
        assertEquals("native", DllOverrides.parse(state.env["WINEDLLOVERRIDES"])["d3dcompiler_47"])
        assertTrue(verb.isSatisfied(state))
    }

    @Test
    fun verbIsIdempotent() = runBlocking {
        val verb = DataDrivenVerb(BuiltinVerbs.D3DCOMPILER_47)
        val state = InMemoryPrefixState()
        val ctx = RecordingVerbContext()

        verb.install(state, ctx)
        val second = verb.install(state, ctx)

        assertTrue(second.skipped)
        assertFalse(second.installed)
        // Only the first install fetched downloads.
        assertEquals(2, ctx.fetched.size)
    }

    @Test
    fun installerVerbEmitsGuestCommand() = runBlocking {
        val verb = DataDrivenVerb(BuiltinVerbs.VCRUN2022)
        val state = InMemoryPrefixState()

        val outcome = verb.install(state, RecordingVerbContext())

        assertTrue(outcome.installed)
        assertNotNull(outcome.command)
        assertTrue(outcome.command!!.contains("vc_redist"))
    }

    @Test
    fun archiveExtractingVerbReadsEntry() = runBlocking {
        val verb = DataDrivenVerb(BuiltinVerbs.D3DX9_43)
        val state = InMemoryPrefixState()
        val ctx = RecordingVerbContext(
            archiveEntries = mapOf("DXSETUP/APR2007_d3dx9_33_x86.cab/d3dx9_43.dll" to "DLLBYTES".encodeToByteArray()),
        )

        verb.install(state, ctx)

        assertEquals("DLLBYTES", state.readText("windows/syswow64/d3dx9_43.dll"))
        assertEquals("native", DllOverrides.parse(state.env["WINEDLLOVERRIDES"])["d3dx9_43"])
    }

    @Test
    fun registryHasAllPriorityVerbs() {
        val registry = VerbRegistry.builtin()
        listOf("vcrun2019", "vcrun2022", "dotnet48", "openal", "d3dcompiler_47", "d3dx9_43").forEach {
            assertTrue("missing verb $it", registry.has(it))
        }
    }
}
