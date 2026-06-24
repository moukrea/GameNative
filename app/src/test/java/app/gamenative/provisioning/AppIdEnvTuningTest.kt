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
        // jgm.c -> OPENSSL_ia32cap (verified ids from the 55-entry smali list: first and last)
        assertEquals("~0x20000000", AppIdEnvTuning.envFor(285190L)["OPENSSL_ia32cap"])
        assertEquals("~0x20000000", AppIdEnvTuning.envFor(1342260L)["OPENSSL_ia32cap"])
        // jgm.f -> WINE_HEAP_DELAY_FREE (verified ids from the 7-entry smali list: first and last)
        assertEquals("1", AppIdEnvTuning.envFor(202990L)["WINE_HEAP_DELAY_FREE"])
        assertEquals("1", AppIdEnvTuning.envFor(2052410L)["WINE_HEAP_DELAY_FREE"])
    }

    @Test
    fun fabricatedIdsFromEarlierPortNoLongerGetTuning() {
        // These belong to jgm.a/jgm.b (NOT env-producing) and were wrongly mapped to OPENSSL/DELAY_FREE
        // by an earlier build's fabricated lists. After the smali-verified correction they map to nothing.
        assertTrue(AppIdEnvTuning.envFor(201510L).isEmpty()) // jgm.a
        assertTrue(AppIdEnvTuning.envFor(414740L).isEmpty()) // jgm.a
        assertTrue(AppIdEnvTuning.envFor(752590L).isEmpty()) // jgm.b
        assertTrue(AppIdEnvTuning.envFor(1233880L).isEmpty()) // jgm.a
    }

    @Test
    fun unknownAppIdGetsNothing() {
        assertTrue(AppIdEnvTuning.envFor(440L).isEmpty()) // TF2 — not in any jgm list
        assertTrue(AppIdEnvTuning.envFor(0L).isEmpty())
    }
}
