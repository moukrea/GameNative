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
        // d3dx9 is now handled (DirectX redist + DXSETUP); gdiplus is override-only; unknownverb dropped.
        assertEquals(listOf("vcrun2010", "d3dx9", "physx", "xna40"), verbs)
    }

    @Test
    fun directXVerbExtractsRedistThenRunsDxsetup() {
        val cmd = ProvisioningInstallers.guestCommand(
            Staged("d3dx9", "directx_Jun2010_redist.exe", "C:\\.gnprov\\d3dx9\\directx_Jun2010_redist.exe"),
        )
        assertEquals(
            "start \"\" /wait C:\\.gnprov\\d3dx9\\directx_Jun2010_redist.exe /Q /T:C:\\.gnprov\\d3dx9\\dxextract & " +
                "start \"\" /wait C:\\.gnprov\\d3dx9\\dxextract\\DXSETUP.exe /silent",
            cmd,
        )
    }

    @Test
    fun dotnet48RunsWithFusionOverride() {
        val cmd = ProvisioningInstallers.guestCommand(
            Staged("dotnet48", "ndp48-x86-x64-allos-enu.exe", "C:\\.gnprov\\dotnet48\\ndp48-x86-x64-allos-enu.exe"),
        )
        assertEquals(
            "set WINEDLLOVERRIDES=fusion=b& " +
                "start \"\" /wait C:\\.gnprov\\dotnet48\\ndp48-x86-x64-allos-enu.exe /sfxlang:1027 /q /norestart",
            cmd,
        )
    }

    @Test
    fun coversTheBootCriticalRuntimeSet() {
        val flags = ProvisioningInstallers.INSTALL_FLAGS
        listOf("vcrun2005", "vcrun2010", "vcrun2013", "vcrun2022", "physx", "dotnet48", "xna40").forEach {
            assertTrue("INSTALL_FLAGS missing boot-critical verb $it", flags.containsKey(it))
        }
    }

    @Test
    fun exeRunsViaStartWaitAndMsiRunsViaMsiexecWithSilentFlags() {
        // start "" /wait makes the cmd chain block until the installer exits (not torn down early).
        val exe = ProvisioningInstallers.guestCommand(
            Staged("vcrun2010", "vcredist_x86.exe", "C:\\.gnprov\\vcrun2010\\vcredist_x86.exe"),
        )
        assertEquals("start \"\" /wait C:\\.gnprov\\vcrun2010\\vcredist_x86.exe /q /norestart", exe)

        val msi = ProvisioningInstallers.guestCommand(
            Staged("xna40", "xnafx40_redist.msi", "C:\\.gnprov\\xna40\\xnafx40_redist.msi"),
        )
        assertEquals("start \"\" /wait msiexec /i C:\\.gnprov\\xna40\\xnafx40_redist.msi /quiet /norestart", msi)
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
            "start \"\" /wait C:\\.gnprov\\vcrun2022\\vc_redist.x86.exe /install /quiet /norestart & " +
                "start \"\" /wait C:\\.gnprov\\vcrun2022\\vc_redist.x64.exe /install /quiet /norestart",
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
