package app.gamenative.provisioning

/**
 * Pure (I/O-free) logic for turning a list of resolved dependency verbs into the silent guest
 * install commands GameHub runs to provision a prefix. The actual download + launch is done by
 * [app.gamenative.utils.ProvisioningDepsStep] through GameNative's existing pre-install chain; this
 * object isolates the command-building so it can be unit-tested without a device.
 *
 * Only verbs whose payload is a directly-runnable Windows installer (`.exe`/`.msi`) are run here.
 * DLL-drop verbs (d3dx9, d3dcompiler, xact, …) are materialized by the declarative engine, not by a
 * guest installer. The silent flags are winetricks-faithful facts; they are best-effort on
 * Wine/Box64 and are validated on-device (see docs/gamehub-compat-mechanism.md).
 */
object ProvisioningInstallers {

    /**
     * Boot-critical installer verbs we run silently in the Wine guest, with their silent flags.
     * `.msi` payloads are launched via `msiexec /i`; `.exe` payloads are launched directly.
     */
    val INSTALL_FLAGS: Map<String, String> = linkedMapOf(
        "vcrun2005" to "/q",
        "vcrun2008" to "/q",
        "vcrun2010" to "/q /norestart",
        "vcrun2012" to "/install /quiet /norestart",
        "vcrun2013" to "/install /quiet /norestart",
        "vcrun2015" to "/install /quiet /norestart",
        "vcrun2017" to "/install /quiet /norestart",
        "vcrun2019" to "/install /quiet /norestart",
        "vcrun2022" to "/install /quiet /norestart",
        // NVIDIA PhysX System Software is a 7-Zip/NSIS self-extractor: its silent switch is `/s`
        // (it does not understand the InstallShield-style /quiet /norestart switches).
        "physx" to "/s",
        // .NET Framework 4.8 offline installer: winetricks-faithful silent switch.
        "dotnet48" to "/sfxlang:1027 /q /norestart",
        "xna31" to "/quiet /norestart",
        "xna40" to "/quiet /norestart",
        // NOTE: dotnet40/45/46 are intentionally absent. Their full bootstrappers need a wrapped
        // /c:"...\\install.exe /q" switch and are notoriously unstable on Wine/Box64; auto-running
        // them with a bare /q would hang or show UI. They are deferred pending on-device validation.
    )

    /** A downloaded, staged installer file ready to run in the guest. */
    data class Staged(val verb: String, val fileName: String, val guestPath: String)

    /** Whether [fileName] is a directly-runnable installer payload. */
    fun isRunnable(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase().let { it == "exe" || it == "msi" }

    /** The installer verbs in [deps] we know how to run, de-duplicated, order preserved. */
    fun installerVerbs(deps: List<String>): List<String> =
        deps.asSequence().filter { INSTALL_FLAGS.containsKey(it) }.distinct().toList()

    /**
     * The silent guest command for one staged installer file.
     *
     * Wrapped in `start "" /wait` so the surrounding `cmd /c "... & taskkill explorer & wineserver -k"`
     * BLOCKS until the installer (and the child process the bootstrapper spawns) actually exits.
     * Without this, the `&` chain returned immediately and `wineserver -k` tore the installer down
     * mid-flight — the installers never finished, so nothing landed in the prefix.
     */
    fun guestCommand(staged: Staged): String {
        val flags = INSTALL_FLAGS[staged.verb] ?: "/quiet /norestart"
        return if (staged.fileName.substringAfterLast('.', "").equals("msi", ignoreCase = true)) {
            "start \"\" /wait msiexec /i ${staged.guestPath} $flags"
        } else {
            "start \"\" /wait ${staged.guestPath} $flags"
        }
    }

    /** Chains all staged installers into one `cmd /c` command, or null when there is nothing to run. */
    fun chain(staged: List<Staged>): String? =
        staged.takeIf { it.isNotEmpty() }?.joinToString(" & ") { guestCommand(it) }
}
