package app.gamenative.utils

import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.utils.EmuReadyService.EmuListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the EmuReady GPU matcher + badge tiering (no network). */
class EmuReadyTest {

    @Test
    fun gpuTier_classifiesProximity() {
        fun t(emu: String?) = EmuReadyGpuMatch.tier("Adreno (TM) 740", emu)
        assertEquals(EmuReadyGpuMatch.Tier.EXACT, t("Adreno 740"))
        assertEquals(EmuReadyGpuMatch.Tier.FAMILY, t("Adreno 730")) // same 7xx generation
        assertEquals(EmuReadyGpuMatch.Tier.LOWER, t("Adreno 660")) // weaker GPU succeeded -> reassuring
        assertEquals(EmuReadyGpuMatch.Tier.OTHER, t("Adreno 830")) // stronger, different generation
        assertEquals(EmuReadyGpuMatch.Tier.OTHER, t("Mali-G715")) // different vendor
        assertEquals(EmuReadyGpuMatch.Tier.OTHER, t(null))
        // Mali device numbers parse from the Gxxx model.
        assertEquals(EmuReadyGpuMatch.Tier.EXACT, EmuReadyGpuMatch.tier("Mali-G715", "Mali-G715"))
    }

    @Test
    fun gpuTier_armCanonicalizesMaliAndImmortalis() {
        // Arm brands the SAME chip as "Mali-G720-Immortalis" (device) and "Immortalis-G720" (EmuReady):
        // these must match EXACT, not fall to OTHER on a vendor-string mismatch.
        assertEquals(
            EmuReadyGpuMatch.Tier.EXACT,
            EmuReadyGpuMatch.tier("Mali-G720-Immortalis MC12", "Immortalis-G720"),
        )
        assertEquals(
            EmuReadyGpuMatch.Tier.FAMILY,
            EmuReadyGpuMatch.tier("Immortalis-G925", "Mali-G920"), // same 9xx Arm generation
        )
    }

    @Test
    fun gpuTier_ignoresCoreCountSuffix() {
        // "MP12" / "MC6" are core counts, NOT the model number; stripping them keeps the Gxxx model.
        assertEquals(EmuReadyGpuMatch.Tier.EXACT, EmuReadyGpuMatch.tier("Mali-G610 MC6", "Mali-G610"))
        // "Mali-G1 ... MP12" has a single-digit model (no 2-4 digit run) -> same-vendor FAMILY, never a
        // bogus "model 12" tier grabbed from the MP12 core count.
        assertEquals(EmuReadyGpuMatch.Tier.FAMILY, EmuReadyGpuMatch.tier("Mali-G1 Ultra MP12", "Mali-G715"))
    }

    private fun listing(gpu: String?, rank: Int, label: String = "x") =
        EmuListing("id-$rank-$gpu", gpu, "SoC", rank, label, 1, 0, 0.9, null)

    @Test
    fun badge_strongGreenOnlyForExactGpuThatWorked() {
        val b = EmuReadyService.computeBadge(listOf(listing("Adreno 740", 1, "Perfect")), "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.GPU_COMPATIBLE, b.status)
        assertFalse(b.caveat)
        assertTrue(b.attribution.contains("EmuReady"))
    }

    @Test
    fun badge_caveatWhenWorkedOnDifferentOrWeakerGpu() {
        val family = EmuReadyService.computeBadge(listOf(listing("Adreno 730", 3, "Playable")), "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.COMPATIBLE, family.status)
        assertTrue(family.caveat)

        val other = EmuReadyService.computeBadge(listOf(listing("Mali-G715", 1, "Perfect")), "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.COMPATIBLE, other.status)
        assertTrue(other.caveat)
    }

    @Test
    fun badge_singleFailedReportIsUnknownNotCondemned() {
        // One noisy negative report is NOT enough to condemn the game (avoids a single EXACT "Nothing"
        // flipping a game red); it falls back to UNKNOWN so the GameNative badge stands.
        val b = EmuReadyService.computeBadge(listOf(listing("Adreno 740", 8, "Nothing")), "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.UNKNOWN, b.status)
    }

    @Test
    fun badge_notCompatibleNeedsCorroboratingFailures() {
        val listings = listOf(
            listing("Adreno 740", 8, "Nothing"),
            listing("Adreno 730", 7, "Loadable"),
        )
        val b = EmuReadyService.computeBadge(listings, "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.NOT_COMPATIBLE, b.status)
    }

    @Test
    fun badge_failedExactStillCompatibleIfAnotherGpuWorked() {
        // Exact-GPU report failed, but a similar GPU ran it -> caveat COMPATIBLE, not condemned.
        val listings = listOf(
            listing("Adreno 740", 8, "Nothing"),
            listing("Adreno 730", 1, "Perfect"),
        )
        val b = EmuReadyService.computeBadge(listings, "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.COMPATIBLE, b.status)
        assertTrue(b.caveat)
    }

    @Test
    fun badge_picksBestListing_exactWorkingOverOtherFailing() {
        val listings = listOf(
            listing("Mali-G715", 8, "Nothing"),
            listing("Adreno 740", 2, "Great"),
        )
        val b = EmuReadyService.computeBadge(listings, "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.GPU_COMPATIBLE, b.status)
        assertFalse(b.caveat)
    }

    @Test
    fun badge_unknownWhenNoListings() {
        assertEquals(GameCompatibilityStatus.UNKNOWN, EmuReadyService.computeBadge(emptyList(), "Adreno 740").status)
    }
}
