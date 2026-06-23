package app.gamenative.provisioning

/**
 * Per-Steam-AppId environment tuning — a faithful, verified port of GameHub's `jgm` table
 * (decompiled `jgm.smali` lists `c`–`h`, consumed by `bg5.smali` lines 3256-3490). GameHub applies
 * these WINE_* heap/DXGI/scheduling tweaks to specific games on every launch; they are pure data and
 * a significant per-game compatibility driver that GameNative previously had no equivalent for.
 *
 * Only the simple appId→flag lists are ported here. `jgm.a`/`jgm.b` are not consumed by the env
 * assembly, and `jgm.i` is a more complex LinkedHashMap — both are intentionally omitted pending
 * decode (see docs/gamehub-compat-mechanism.md), rather than guessed at.
 */
object AppIdEnvTuning {

    private data class Rule(val key: String, val value: String, val appIds: Set<Long>)

    // Verbatim from jgm.smali (field -> bg5 env var):
    private val RULES = listOf(
        // jgm.c -> OPENSSL_ia32cap
        Rule("OPENSSL_ia32cap", "~0x20000000", setOf(752590L, 414740L, 201510L, 1233880L)),
        // jgm.d -> WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER
        Rule("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1", setOf(692890L, 1017900L, 1331440L, 2620730L)),
        // jgm.e -> WINE_DISABLE_HARDWARE_SCHEDULING
        Rule("WINE_DISABLE_HARDWARE_SCHEDULING", "1", setOf(275850L, 2012840L)),
        // jgm.f -> WINE_HEAP_DELAY_FREE
        Rule("WINE_HEAP_DELAY_FREE", "1", setOf(2012840L, 1331440L, 2620730L, 1233880L)),
        // jgm.g -> WINE_HEAP_ZERO_MEMORY
        Rule("WINE_HEAP_ZERO_MEMORY", "1", setOf(21980L, 553850L, 2055290L)),
        // jgm.h -> WINE_HEAP_TOP_DOWN
        Rule("WINE_HEAP_TOP_DOWN", "1", setOf(71230L, 3328910L)),
    )

    /** The WINE_* tuning env vars GameHub applies to this Steam appId (empty if none). */
    fun envFor(steamAppId: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (rule in RULES) {
            if (steamAppId in rule.appIds) out[rule.key] = rule.value
        }
        return out
    }
}
