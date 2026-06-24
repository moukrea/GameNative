package app.gamenative.provisioning

/**
 * Per-Steam-AppId environment tuning — a faithful, verified port of GameHub's `jgm` table
 * (decompiled `jgm.smali` lists `c`–`h`, consumed by `bg5.smali` lines 3256-3490). GameHub applies
 * these WINE_* heap/DXGI/scheduling tweaks to specific games on every launch; they are pure data and
 * a significant per-game compatibility driver that GameNative previously had no equivalent for.
 *
 * Lists c–h are the env-producing tables and are ported verbatim. The other jgm fields are NOT env
 * inputs and are intentionally not represented here: `jgm.a`/`jgm.b` feed the selection logic for the
 * lists above (and were the source of an earlier build's fabricated c/f values), `jgm.i` is a
 * launch-arg override (its env, WINEMU_CEF_FILES, is native-kernel-only), and `jgm.j`/`jgm.k` feed
 * file/process handling (see docs/gamehub-compat-mechanism.md).
 */
object AppIdEnvTuning {

    private data class Rule(val key: String, val value: String, val appIds: Set<Long>)

    // Verbatim from jgm.smali (field -> bg5 env var). Counts cross-checked against the smali
    // filled-new-array ranges: c=55, d=4, e=2, f=7, g=3, h=2.
    private val RULES = listOf(
        // jgm.c -> OPENSSL_ia32cap (55 ids; the prior 4-id list was fabricated from jgm.a/jgm.b)
        Rule(
            "OPENSSL_ia32cap", "~0x20000000",
            setOf(
                285190L, 366870L, 386360L, 392110L, 399810L, 406970L, 415200L, 420290L, 425670L, 433100L,
                433530L, 437630L, 442780L, 447020L, 451520L, 463150L, 469610L, 485030L, 487720L, 492230L,
                521890L, 535850L, 544920L, 551770L, 556180L, 558260L, 561600L, 649950L, 661920L, 682990L,
                711750L, 748360L, 759740L, 775900L, 783170L, 798290L, 824280L, 834280L, 882020L, 890880L,
                967390L, 968870L, 996580L, 1029890L, 1051200L, 1052070L, 1055610L, 1058450L, 1092660L,
                1096570L, 1133320L, 1150080L, 1195460L, 1237970L, 1342260L,
            ),
        ),
        // jgm.d -> WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER
        Rule("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1", setOf(692890L, 1017900L, 1331440L, 2620730L)),
        // jgm.e -> WINE_DISABLE_HARDWARE_SCHEDULING
        Rule("WINE_DISABLE_HARDWARE_SCHEDULING", "1", setOf(275850L, 2012840L)),
        // jgm.f -> WINE_HEAP_DELAY_FREE (7 ids; the prior 4-id list was fabricated from jgm.a/d/e)
        Rule("WINE_HEAP_DELAY_FREE", "1", setOf(202990L, 212910L, 499100L, 789910L, 1183470L, 1404090L, 2052410L)),
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
