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
    fun badge_notCompatibleWhenBestReportFailed() {
        val b = EmuReadyService.computeBadge(listOf(listing("Adreno 740", 8, "Nothing")), "Adreno (TM) 740")
        assertEquals(GameCompatibilityStatus.NOT_COMPATIBLE, b.status)
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
