package app.gamenative.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the faithful port of GameHub's jgm per-AppId WINE_* tuning table. */
class AppIdEnvTuningTest {

    @Test
    fun knownAppIdsGetTheirGameHubTuning() {
        // jgm.d -> WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER
        assertEquals("1", AppIdEnvTuning.envFor(692890L)["WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER"])
        // jgm.e -> WINE_DISABLE_HARDWARE_SCHEDULING
        assertEquals("1", AppIdEnvTuning.envFor(275850L)["WINE_DISABLE_HARDWARE_SCHEDULING"])
        // jgm.g -> WINE_HEAP_ZERO_MEMORY
        assertEquals("1", AppIdEnvTuning.envFor(553850L)["WINE_HEAP_ZERO_MEMORY"])
        // jgm.h -> WINE_HEAP_TOP_DOWN
        assertEquals("1", AppIdEnvTuning.envFor(71230L)["WINE_HEAP_TOP_DOWN"])
        // jgm.c -> OPENSSL_ia32cap with the exact value
        assertEquals("~0x20000000", AppIdEnvTuning.envFor(201510L)["OPENSSL_ia32cap"])
    }

    @Test
    fun appIdInMultipleListsGetsAllItsVars() {
        // 1233880 is in jgm.c (OPENSSL) AND jgm.f (WINE_HEAP_DELAY_FREE).
        val env = AppIdEnvTuning.envFor(1233880L)
        assertEquals("~0x20000000", env["OPENSSL_ia32cap"])
        assertEquals("1", env["WINE_HEAP_DELAY_FREE"])
    }

    @Test
    fun unknownAppIdGetsNothing() {
        assertTrue(AppIdEnvTuning.envFor(440L).isEmpty()) // TF2 — not in any jgm list
        assertTrue(AppIdEnvTuning.envFor(0L).isEmpty())
    }
}
