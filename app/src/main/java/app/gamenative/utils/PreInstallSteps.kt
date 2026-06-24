package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File

/**
 * Determines whether pre-install steps (VC Redist, GOG script interpreter) need to run
 * as Wine guest programs before the game launches. These installers require wine explorer
 * and cannot be run via execShellCommand.
 *
 * Each returned entry contains the marker and complete guest executable string for one
 * Wine session. The caller chains them via termination callbacks and persists markers
 * per-step as they complete.
 *
 * Completion is tracked via marker files in the game directory (not container config),
 * so importing a container config won't incorrectly skip pre-install steps.
 */
object PreInstallSteps {
    data class PreInstallCommand(
        val marker: Marker,
        val executable: String,
    )

    private val steps: List<PreInstallStep> = listOf(
        // Opt-in: provisions the prefix with the common Windows runtimes (GameHub's mechanism)
        // before the game-shipped redists. No-op unless the per-game provisioning flag is on.
        ProvisioningDepsStep,
        VcRedistStep,
        PhysXStep,
        OpenALStep,
        XnaFrameworkStep,
        GogScriptInterpreterStep,
        UbisoftConnectStep,
    )

    private var stepsProvider: () -> List<PreInstallStep> = { steps }
    private fun currentSteps(): List<PreInstallStep> = stepsProvider()
    private fun allMarkers(): List<Marker> = currentSteps().map { it.marker }.distinct()

    /**
     * Returns a list of pre-install commands (marker + guest executable). Each entry is a
     * separate Wine session. Returns empty list if nothing needs installing.
     */
    fun getPreInstallCommands(
        container: Container,
        appId: String,
        gameSource: GameSource,
        screenInfo: String,
        containerVariantChanged: Boolean,
    ): List<PreInstallCommand> {
        val gameDir = getGameDir(container) ?: return emptyList()
        val gameDirPath = gameDir.absolutePath

        if (containerVariantChanged) resetMarkers(gameDirPath)

        val commands = mutableListOf<PreInstallCommand>()

        for (step in currentSteps()) {
            if (step.appliesTo(
                    container = container,
                    gameSource = gameSource,
                    gameDirPath = gameDirPath,
                )
            ) {
                step.buildCommand(
                    container = container,
                    appId = appId,
                    gameSource = gameSource,
                    gameDir = gameDir,
                    gameDirPath = gameDirPath,
                )?.let { cmd ->
                    commands.add(
                        PreInstallCommand(
                            marker = step.marker,
                            executable = wrapAsGuestExecutable(cmd, screenInfo),
                        ),
                    )
                }
            }
        }

        return commands
    }

    fun markAllDone(container: Container) {
        val gameDir = getGameDir(container) ?: return
        val gameDirPath = gameDir.absolutePath
        for (marker in allMarkers()) {
            MarkerUtils.addMarker(gameDirPath, marker)
        }
    }

    /**
     * Signature DLLs that prove a runtime installer actually landed in the prefix. GameHub verifies
     * each dependency by file presence ("broken" = file missing) and re-runs it otherwise; we mirror
     * that for the DLL-verifiable runtime steps so a silently-failed installer (e.g. PhysX black-holing
     * under Box64) is NOT recorded as done and re-runs next launch instead of faking success. Steps
     * without a checkable system DLL (GOG/Ubisoft scripts, .NET/XNA in the GAC, and the multi-runtime
     * ProvisioningDepsStep which has its own partial-install handling) are not listed and mark as before.
     */
    private val SIGNATURE_DLLS: Map<Marker, List<String>> = mapOf(
        Marker.PHYSX_INSTALLED to listOf("PhysXLoader.dll", "PhysXLoader64.dll", "PhysXCore.dll", "physxcudart_20.dll"),
        Marker.VCREDIST_INSTALLED to listOf(
            "msvcp140.dll", "vcruntime140.dll", "msvcr120.dll", "msvcr110.dll", "msvcr100.dll", "msvcr90.dll", "msvcr80.dll",
        ),
        Marker.OPENAL_INSTALLED to listOf("OpenAL32.dll", "soft_oal.dll", "wrap_oal.dll"),
    )

    private fun signatureSatisfied(container: Container, marker: Marker): Boolean {
        val dlls = SIGNATURE_DLLS[marker] ?: return true // not DLL-verifiable -> trust completion
        val sys32 = File(container.rootDir, ".wine/drive_c/windows/system32")
        val syswow = File(container.rootDir, ".wine/drive_c/windows/syswow64")
        return dlls.any { File(sys32, it).isFile || File(syswow, it).isFile }
    }

    /**
     * Removes all pre-install step markers for [container].
     * Call this whenever the Wine prefix is wiped (e.g. after general patches are applied)
     * so that VC Redist, OpenAL, PhysX etc. re-run against the fresh prefix.
     */
    fun resetAllMarkers(container: Container) {
        val gameDir = getGameDir(container) ?: return
        resetMarkers(gameDir.absolutePath)
    }

    fun markStepDone(container: Container, marker: Marker) {
        val gameDir = getGameDir(container) ?: return
        val gameDirPath = gameDir.absolutePath
        if (!signatureSatisfied(container, marker)) {
            // Installer ran but its signature DLL is absent -> withhold the marker so the step retries
            // next launch instead of recording a false success (the "ça prétend installer" failure).
            timber.log.Timber.tag("Provisioning")
                .w("Step %s ran but its signature DLL is missing; not marking done (will retry)", marker)
            return
        }
        MarkerUtils.addMarker(gameDirPath, marker)
    }

    /** Clears the provisioning-deps marker so [ProvisioningDepsStep] re-runs on the next launch. */
    fun clearProvisioningDepsMarker(container: Container) {
        val gameDir = getGameDir(container) ?: return
        MarkerUtils.removeMarker(gameDir.absolutePath, Marker.PROVISIONING_DEPS_INSTALLED)
    }

    /** True once [ProvisioningDepsStep] has completed for this game (its installers ran in-guest). */
    fun isProvisioningMarked(container: Container): Boolean {
        val gameDir = getGameDir(container) ?: return false
        return MarkerUtils.hasMarker(gameDir.absolutePath, Marker.PROVISIONING_DEPS_INSTALLED)
    }

    private fun resetMarkers(gameDirPath: String) {
        for (marker in allMarkers()) {
            MarkerUtils.removeMarker(gameDirPath, marker)
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val wrapped = "winhandler.exe cmd /c \"$cmdChain & taskkill /F /IM explorer.exe & wineserver -k\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }

    private fun getGameDir(container: Container): File? {
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].equals("A", ignoreCase = true)) return File(drive[1])
        }
        return null
    }

    /**
     * Test-only hook to override the pre-install step provider.
     * Not intended for production code paths.
     *
     * @param provider Steps provider for tests; pass null to restore the default provider.
     */
    internal fun setStepsProviderForTests(provider: (() -> List<PreInstallStep>)?) {
        stepsProvider = provider ?: { steps }
    }

    /** Test-only: the default registered pre-install steps (to assert wiring). */
    internal fun registeredStepsForTest(): List<PreInstallStep> = steps
}
