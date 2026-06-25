package app.gamenative.utils

import app.gamenative.data.GameCompatibilityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

/**
 * Read-only client for EmuReady's PUBLIC mobile tRPC API (no auth) — a SECOND community data source
 * layered on top of GameNative's own api.gamenative.app, with no GameNative-server change. EmuReady
 * supplies the per-device hardware granularity GameNative's API lacks: each GameNative listing carries
 * the device SoC + GPU model and a structured performance rank, which lets us tier the compatibility
 * badge by how well the game ran on hardware like the user's, and (later) import the reported config.
 *
 * tRPC GET format (verified live): GET {BASE}/<procedure>?input=<urlencoded {"json": <args>}> ;
 * the payload is at result.data.json. All procedures used here are public (header-less).
 *
 * HONEST LIMITS (verified against the live API): EmuReady's Android listings expose NO RAM and NO
 * numeric fps — fps is free-text in `notes`/an optional `average_fps` custom field. So "same-RAM"
 * tiers are impossible; the strong signal is (GPU-model match + performance rank). GPU matching is a
 * string heuristic (Android GL_RENDERER vs EmuReady's gpuModel). Data is attributed to EmuReady and
 * is typically less complete than GameNative's own configs.
 */
object EmuReadyService {
    private const val BASE = "https://www.emuready.com/api/mobile/trpc"

    /** Verified stable id of the "GameNative" emulator on EmuReady (microsoft_windows system). */
    const val GAMENATIVE_EMULATOR_ID = "c7717d6a-fc63-4dd4-b021-3048f88d254a"
    private const val MICROSOFT_WINDOWS_KEY = "microsoft_windows"

    private val httpClient = Net.http

    /** One EmuReady GameNative compatibility report. */
    data class EmuListing(
        val id: String,
        val gpuModel: String?,
        val socName: String?,
        val performanceRank: Int, // 1=Perfect .. 8=Nothing (lower is better)
        val performanceLabel: String,
        val upvotes: Int,
        val downvotes: Int,
        val successRate: Double, // 0..1
        val notes: String?,
    )

    /** Result of projecting EmuReady listings onto the badge, with provenance for the UI. */
    data class EmuBadge(
        val status: GameCompatibilityStatus,
        val caveat: Boolean, // true when proven only on different/weaker hardware than the user's
        val attribution: String,
    )

    // ---- public API ------------------------------------------------------------------------------

    /** Resolves a game title to its EmuReady (Microsoft Windows) game id, or null. */
    suspend fun searchGameId(title: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = getJsonArray("games.searchGames", JSONObject().put("query", title)) ?: return@runCatching null
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                if (g.optJSONObject("system")?.optString("key") == MICROSOFT_WINDOWS_KEY) {
                    return@runCatching g.optString("id").ifBlank { null }
                }
            }
            null
        }.onFailure { Timber.tag(TAG).w(it, "searchGameId failed for %s", title) }.getOrNull()
    }

    /** GameNative compatibility listings for an EmuReady game id (best-effort, capped). */
    suspend fun listingsForGame(gameId: String, limit: Int = 30): List<EmuListing> = withContext(Dispatchers.IO) {
        runCatching {
            val input = JSONObject()
                .put("gameIds", listOf(gameId).toJsonArray())
                .put("emulatorIds", listOf(GAMENATIVE_EMULATOR_ID).toJsonArray())
                .put("limit", limit.coerceIn(1, 50))
            val data = getJson("listings.get", input) ?: return@runCatching emptyList()
            val listings = data.optJSONArray("listings") ?: return@runCatching emptyList()
            (0 until listings.length()).mapNotNull { i -> parseListing(listings.optJSONObject(i)) }
        }.onFailure { Timber.tag(TAG).w(it, "listingsForGame failed for %s", gameId) }.getOrDefault(emptyList())
    }

    /** The raw GameNative launch config EmuReady stores for a listing (for the import picker), or null. */
    suspend fun getEmulatorConfig(listingId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val data = getJson("listings.getEmulatorConfig", JSONObject().put("listingId", listingId))
            data?.toString()
        }.onFailure { Timber.tag(TAG).w(it, "getEmulatorConfig failed for %s", listingId) }.getOrNull()
    }

    /** The importable GameNative config JSON (the `content` blob) for a listing, or null. */
    suspend fun getEmulatorConfigContent(listingId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val data = getJson("listings.getEmulatorConfig", JSONObject().put("listingId", listingId)) ?: return@runCatching null
            if (data.optString("type") != "gamenative") return@runCatching null // route: only GameNative configs
            data.optString("content").ifBlank { null } // a stringified JSON config (keys match ContainerData)
        }.onFailure { Timber.tag(TAG).w(it, "getEmulatorConfigContent failed for %s", listingId) }.getOrNull()
    }

    /** One pickable EmuReady report: id (to fetch the config), a human label, and the author's notes. */
    data class RankedListing(val id: String, val label: String, val notes: String?)

    /**
     * Sanitises an EmuReady GameNative config so GameNative's import doesn't falsely reject it:
     * - arm64ec Proton only runs on a BIONIC container, so force containerVariant=bionic when the
     *   wineVersion is arm64ec (otherwise the manifest is filtered by the wrong variant and the
     *   available proton — e.g. proton-11.0-1-arm64ec-1 — is wrongly reported "not available").
     * - EmuReady uses the literal "other" as a placeholder meaning "see the notes"; it is not an
     *   installable component, so drop those keys (keep the container's current/default) instead of
     *   blocking the whole import. The real value (e.g. the DXVK version) is in the notes, surfaced
     *   in the picker so the user can set it manually.
     */
    fun sanitizeGameNativeConfig(content: String): String = runCatching {
        val o = JSONObject(content)
        if (o.optString("wineVersion").contains("arm64ec", ignoreCase = true)) {
            o.put("containerVariant", "bionic")
        }
        for (k in listOf("dxwrapper", "graphicsDriver", "graphicsDriverVersion")) {
            if (o.optString(k).equals("other", ignoreCase = true)) o.remove(k)
        }
        if (o.optString("dxwrapperConfig").contains("version=other", ignoreCase = true)) {
            o.remove("dxwrapper"); o.remove("dxwrapperConfig")
        }
        o.toString()
    }.getOrDefault(content)

    /**
     * A game's GameNative reports ranked by GPU proximity (EXACT > FAMILY > LOWER > OTHER) then by
     * performance rank, as rows for the import picker. Empty if the game isn't on EmuReady.
     */
    suspend fun rankedListings(title: String, deviceGpu: String?): List<RankedListing> = withContext(Dispatchers.IO) {
        val gameId = searchGameId(title) ?: return@withContext emptyList()
        listingsForGame(gameId, limit = 50)
            .sortedWith(
                compareBy<EmuListing> { EmuReadyGpuMatch.tier(deviceGpu, it.gpuModel).ordinal }
                    .thenBy { it.performanceRank },
            )
            .map { l ->
                val tierLabel = when (EmuReadyGpuMatch.tier(deviceGpu, l.gpuModel)) {
                    EmuReadyGpuMatch.Tier.EXACT -> "your GPU"
                    EmuReadyGpuMatch.Tier.FAMILY -> "similar GPU"
                    EmuReadyGpuMatch.Tier.LOWER -> "weaker GPU"
                    EmuReadyGpuMatch.Tier.OTHER -> "other GPU"
                }
                val noteHint = l.notes?.let { " · 📝" } ?: ""
                RankedListing(l.id, "${l.gpuModel ?: "?"} · ${l.performanceLabel} · $tierLabel$noteHint", l.notes)
            }
    }

    // ---- cached, best-effort per-game badge ------------------------------------------------------

    private data class Cached(val badge: EmuBadge, val atMs: Long)
    private val badgeCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * The EmuReady-derived badge for a game title on this device's GPU. Best-effort + cached 6h in
     * memory (keyed by title|gpu) so a library scroll never re-hits the API. Any failure or missing
     * game yields UNKNOWN, so the caller simply falls back to the GameNative badge. Two network calls
     * on a cache miss (searchGameId + listingsForGame); none on a hit.
     */
    suspend fun badgeFor(title: String, deviceGpu: String?): EmuBadge {
        val key = "$title|${deviceGpu ?: ""}"
        badgeCache[key]?.let { if (System.currentTimeMillis() - it.atMs < TTL_MS) return it.badge }
        val badge = runCatching {
            val gameId = searchGameId(title) ?: return@runCatching unknownBadge()
            computeBadge(listingsForGame(gameId), deviceGpu)
        }.getOrDefault(unknownBadge())
        badgeCache[key] = Cached(badge, System.currentTimeMillis())
        return badge
    }

    private fun unknownBadge() = EmuBadge(GameCompatibilityStatus.UNKNOWN, caveat = false, attribution = "")

    // ---- badge logic (pure, unit-tested) ---------------------------------------------------------

    /**
     * Projects a game's EmuReady GameNative listings onto a badge, tiered by how close the reporting
     * hardware is to the user's GPU and how well it ran. "Works" = performance rank <= 3
     * (Perfect/Great/Playable). Strong green requires an EXACT GPU-model match that worked; a similar
     * or weaker GPU, or other hardware, yields a caveat "compatible (other HW)"; only-failed reports
     * yield NOT_COMPATIBLE; no listings -> UNKNOWN.
     */
    fun computeBadge(listings: List<EmuListing>, deviceGpu: String?): EmuBadge {
        if (listings.isEmpty()) return EmuBadge(GameCompatibilityStatus.UNKNOWN, caveat = false, attribution = "")
        // Best = best GPU proximity, then best (lowest) performance rank.
        val ranked = listings.sortedWith(
            compareBy<EmuListing> { EmuReadyGpuMatch.tier(deviceGpu, it.gpuModel).ordinal }
                .thenBy { it.performanceRank },
        )
        val best = ranked.first()
        val worked = best.performanceRank in 1..3
        val marginal = best.performanceRank in 4..5
        val tier = EmuReadyGpuMatch.tier(deviceGpu, best.gpuModel)
        val n = listings.size
        return when {
            worked && tier == EmuReadyGpuMatch.Tier.EXACT ->
                EmuBadge(GameCompatibilityStatus.GPU_COMPATIBLE, caveat = false, attribution = attribution(best, n, "your GPU"))
            worked ->
                EmuBadge(GameCompatibilityStatus.COMPATIBLE, caveat = true, attribution = attribution(best, n, gpuPhrase(tier, best.gpuModel)))
            marginal ->
                EmuBadge(GameCompatibilityStatus.COMPATIBLE, caveat = true, attribution = attribution(best, n, gpuPhrase(tier, best.gpuModel)))
            // listings exist but the best report is Intro/Loadable/Nothing -> reported not working.
            else -> EmuBadge(GameCompatibilityStatus.NOT_COMPATIBLE, caveat = false, attribution = attribution(best, n, gpuPhrase(tier, best.gpuModel)))
        }
    }

    private fun gpuPhrase(tier: EmuReadyGpuMatch.Tier, gpu: String?): String = when (tier) {
        EmuReadyGpuMatch.Tier.EXACT -> "your GPU"
        EmuReadyGpuMatch.Tier.FAMILY -> "a similar GPU (${gpu ?: "?"})"
        EmuReadyGpuMatch.Tier.LOWER -> "a weaker GPU (${gpu ?: "?"})"
        EmuReadyGpuMatch.Tier.OTHER -> "other hardware (${gpu ?: "?"})"
    }

    private fun attribution(best: EmuListing, count: Int, on: String): String =
        "EmuReady: ${best.performanceLabel} on $on ($count report${if (count == 1) "" else "s"})"

    // ---- internals -------------------------------------------------------------------------------

    private fun parseListing(o: JSONObject?): EmuListing? {
        o ?: return null
        val perf = o.optJSONObject("performance")
        val soc = o.optJSONObject("device")?.optJSONObject("soc")
        return EmuListing(
            id = o.optString("id").ifBlank { return null },
            gpuModel = soc?.optString("gpuModel")?.ifBlank { null },
            socName = soc?.optString("name")?.ifBlank { null },
            performanceRank = perf?.optInt("rank", 99) ?: 99,
            performanceLabel = perf?.optString("label")?.ifBlank { "?" } ?: "?",
            upvotes = o.optInt("upvoteCount", o.optInt("upVotes", 0)),
            downvotes = o.optInt("downvoteCount", o.optInt("downVotes", 0)),
            successRate = o.optDouble("successRate", 0.0),
            notes = o.optString("notes")?.ifBlank { null },
        )
    }

    /** Executes a public tRPC GET and returns the `result.data.json` object, or null. */
    private fun getJson(procedure: String, args: JSONObject): JSONObject? {
        val envelope = JSONObject().put("json", args).toString()
        val url = "$BASE/$procedure?input=" + URLEncoder.encode(envelope, "UTF-8")
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.tag(TAG).w("%s -> HTTP %d", procedure, resp.code)
                return null
            }
            val body = resp.body?.string() ?: return null
            val root = JSONObject(body)
            root.optJSONObject("error")?.let { Timber.tag(TAG).w("%s tRPC error: %s", procedure, it); return null }
            return root.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json")
        }
    }

    private fun getJsonArray(procedure: String, args: JSONObject): org.json.JSONArray? {
        // Some procedures (searchGames) return a bare array at result.data.json.
        val envelope = JSONObject().put("json", args).toString()
        val url = "$BASE/$procedure?input=" + URLEncoder.encode(envelope, "UTF-8")
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return JSONObject(body).optJSONObject("result")?.optJSONObject("data")?.optJSONArray("json")
        }
    }

    private fun List<String>.toJsonArray(): org.json.JSONArray =
        org.json.JSONArray().also { a -> forEach { a.put(it) } }

    private const val TAG = "EmuReadyService"
}
