package app.gamenative.provisioning

import app.gamenative.provisioning.ProvisioningInstallers.Staged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the GameHub dependency-install command builder (no device, no network).
 */
class ProvisioningInstallersTest {

    @Test
    fun installerVerbsFiltersToKnownAndDeduplicatesPreservingOrder() {
        val deps = listOf("vcrun2010", "d3dx9", "vcrun2010", "physx", "gdiplus", "xna40", "unknownverb")
        val verbs = ProvisioningInstallers.installerVerbs(deps)
        // d3dx9 is handled (DirectX redist + DXSETUP); gdiplus is override-only; unknownverb dropped;
        // xna40 is no longer run in-guest (depended on the hang-prone .NET installers, now removed).
        assertEquals(listOf("vcrun2010", "d3dx9", "physx"), verbs)
    }

    @Test
    fun directXVerbExtractsRedistThenRunsDxsetup() {
        val cmd = ProvisioningInstallers.guestCommand(
            Staged("d3dx9", "directx_Jun2010_redist.exe", "C:\\.gnprov\\d3dx9\\directx_Jun2010_redist.exe"),
        )
        assertEquals(
            "C:\\.gnprov\\d3dx9\\directx_Jun2010_redist.exe /Q /T:C:\\.gnprov\\d3dx9\\dxextract & " +
                "C:\\.gnprov\\d3dx9\\dxextract\\DXSETUP.exe /silent",
            cmd,
        )
    }

    @Test
    fun dotnetAndXnaAreNotRunInGuest() {
        // The real .NET / XNA installers hang under Wine/Box64 and froze the pre-install chain on
        // device; wine-mono provides .NET instead. They must not appear as runnable installer verbs.
        val flags = ProvisioningInstallers.INSTALL_FLAGS
        listOf("dotnet40", "dotnet48", "xna31", "xna40").forEach {
            assertTrue("$it must NOT be a runnable installer (it hangs under Wine)", !flags.containsKey(it))
        }
        assertEquals(emptyList<String>(), ProvisioningInstallers.installerVerbs(listOf("dotnet48", "xna40")))
    }

    @Test
    fun coversTheBootCriticalRuntimeSet() {
        val flags = ProvisioningInstallers.INSTALL_FLAGS
        listOf("vcrun2005", "vcrun2010", "vcrun2013", "vcrun2022", "physx", "d3dx9").forEach {
            assertTrue("INSTALL_FLAGS missing boot-critical verb $it", flags.containsKey(it))
        }
    }

    @Test
    fun exeRunsDirectlyAndMsiRunsViaMsiexecWithSilentFlags() {
        // Raw form (no start/wait), matching GameNative's shipped VcRedistStep/PhysXStep: cmd's `&`
        // chain runs each console installer sequentially and waits for it before the next.
        val exe = ProvisioningInstallers.guestCommand(
            Staged("vcrun2010", "vcredist_x86.exe", "C:\\.gnprov\\vcrun2010\\vcredist_x86.exe"),
        )
        assertEquals("C:\\.gnprov\\vcrun2010\\vcredist_x86.exe /q /norestart", exe)

        val msi = ProvisioningInstallers.guestCommand(
            Staged("xna40", "xnafx40_redist.msi", "C:\\.gnprov\\xna40\\xnafx40_redist.msi"),
        )
        assertEquals("msiexec /i C:\\.gnprov\\xna40\\xnafx40_redist.msi /quiet /norestart", msi)
    }

    @Test
    fun chainJoinsWithAmpersandAndIsNullWhenEmpty() {
        assertNull(ProvisioningInstallers.chain(emptyList()))
        val chain = ProvisioningInstallers.chain(
            listOf(
                Staged("vcrun2022", "vc_redist.x86.exe", "C:\\.gnprov\\vcrun2022\\vc_redist.x86.exe"),
                Staged("vcrun2022", "vc_redist.x64.exe", "C:\\.gnprov\\vcrun2022\\vc_redist.x64.exe"),
            ),
        )
        assertEquals(
            "C:\\.gnprov\\vcrun2022\\vc_redist.x86.exe /install /quiet /norestart & " +
                "C:\\.gnprov\\vcrun2022\\vc_redist.x64.exe /install /quiet /norestart",
            chain,
        )
    }

    @Test
    fun isRunnableAcceptsExeAndMsiOnly() {
        assertTrue(ProvisioningInstallers.isRunnable("vcredist_x86.EXE"))
        assertTrue(ProvisioningInstallers.isRunnable("xnafx40_redist.msi"))
        assertTrue(!ProvisioningInstallers.isRunnable("oalinst.zip"))
        assertTrue(!ProvisioningInstallers.isRunnable("d3dcompiler_47.dll"))
    }
}
