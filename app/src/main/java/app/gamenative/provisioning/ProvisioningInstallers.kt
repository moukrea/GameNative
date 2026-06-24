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
        // NOTE: dotnet40/dotnet48 are deliberately NOT run in-guest. The real .NET Framework
        // installers HANG under Wine/Box64 (they spin a child process that never exits silently),
        // which froze the whole pre-install chain on device ("Installing prerequisites" forever).
        // GameNative already installs wine-mono (see XServerScreen.unpackExecutableFile), which
        // provides the .NET runtime for managed apps, so the fragile real installers add hang risk
        // for no gain. The correct way to bake real .NET would be a prefix/imagefs seed, not an
        // in-guest installer. xna40/xna31 (which depended on dotnet40) are dropped for the same reason.
        // DirectX June 2010 redist self-extractor (covers the whole d3dx9 / d3dcompiler_43 / xact /
        // dsound family). Handled specially in guestCommand: extract then run DXSETUP.
        "d3dx9" to "/silent",
    )

    /** Verbs whose staged payload is the DirectX June 2010 redist (extract + DXSETUP). */
    private val DIRECTX_REDIST_VERBS = setOf("d3dx9")

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
     * The installer is run **directly** (no `start "" /wait` wrapper), exactly like GameNative's own
     * shipped pre-install steps ([app.gamenative.utils.VcRedistStep], [app.gamenative.utils.PhysXStep]):
     * the surrounding `cmd /c "<cmd1> & <cmd2> & … & wineserver -k"` runs each `&`-separated console
     * installer sequentially and waits for it to exit before the next. An earlier build wrapped these
     * in `start "" /wait`, which is unproven under this Wine's cmd and differs from the steps that are
     * known to work on-device — the most likely reason nothing installed. Aligning to the proven raw
     * form keeps a single, validated execution model for every installer.
     */
    fun guestCommand(staged: Staged): String {
        // DirectX family: extract the redist's cabs then run DXSETUP (no Kotlin .cab extraction).
        if (staged.verb in DIRECTX_REDIST_VERBS) {
            val dir = staged.guestPath.substringBeforeLast('\\')
            val extract = "$dir\\dxextract"
            return "${staged.guestPath} /Q /T:$extract & $extract\\DXSETUP.exe /silent"
        }
        // .NET 4.8 needs the fusion DLL forced to builtin during install, or it hangs under Wine.
        // Set it in the cmd session (no nested quotes — those would break the outer cmd /c "...").
        if (staged.verb == "dotnet48") {
            return "set WINEDLLOVERRIDES=fusion=b& ${staged.guestPath} /sfxlang:1027 /q /norestart"
        }
        val flags = INSTALL_FLAGS[staged.verb] ?: "/quiet /norestart"
        return if (staged.fileName.substringAfterLast('.', "").equals("msi", ignoreCase = true)) {
            "msiexec /i ${staged.guestPath} $flags"
        } else {
            "${staged.guestPath} $flags"
        }
    }

    /** Chains all staged installers into one `cmd /c` command, or null when there is nothing to run. */
    fun chain(staged: List<Staged>): String? =
        staged.takeIf { it.isNotEmpty() }?.joinToString(" & ") { guestCommand(it) }
}
