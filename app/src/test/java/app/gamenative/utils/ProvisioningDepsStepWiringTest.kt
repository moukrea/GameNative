package app.gamenative.utils

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.createTempDirectory

/**
 * Proves the provisioning dependency installer is actually WIRED into the launch chain and gated
 * correctly — countering the "is this even hooked up / c'est du vent" doubt. Pairs with
 * [app.gamenative.provisioning.ProvisioningAssetsAndroidTest] (proves the data loads on Android) and
 * [app.gamenative.provisioning.ProvisioningInstallersTest] (proves the install command builds).
 *
 * Robolectric so PrefManager's androidx.datastore-backed members can class-init; the flag value is
 * mocked (not round-tripped through DataStore) to keep the gating assertions deterministic.
 */
@RunWith(RobolectricTestRunner::class)
class ProvisioningDepsStepWiringTest {

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun stepIsRegisteredInThePreInstallChain() {
        assertTrue(
            "ProvisioningDepsStep must be registered in PreInstallSteps so it runs at launch",
            PreInstallSteps.registeredStepsForTest().any { it === ProvisioningDepsStep },
        )
    }

    @Test
    fun stepActivatesOnlyWhenFlagOnAndNotYetProvisioned() {
        mockkObject(PrefManager)
        val container = mockk<Container>(relaxed = true)
        val gameDir = createTempDirectory("gnprov-wiring").toString()

        every { PrefManager.enablePerGameProvisioning } returns false
        assertFalse(
            "must be completely inert when the opt-in flag is off (zero regression)",
            ProvisioningDepsStep.appliesTo(container, GameSource.STEAM, gameDir),
        )

        every { PrefManager.enablePerGameProvisioning } returns true
        assertTrue(
            "must activate on a fresh container when the flag is on",
            ProvisioningDepsStep.appliesTo(container, GameSource.STEAM, gameDir),
        )

        MarkerUtils.addMarker(gameDir, Marker.PROVISIONING_DEPS_INSTALLED)
        assertFalse(
            "must not re-run once provisioning has completed (idempotent)",
            ProvisioningDepsStep.appliesTo(container, GameSource.STEAM, gameDir),
        )
    }
}
