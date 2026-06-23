package app.gamenative.provisioning.engine

import app.gamenative.data.GameSource
import app.gamenative.provisioning.ProvisioningAssets
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RecipeCodec

/**
 * Per-game recipes derived from the open GameHub Lite / BannerHub catalog
 * ([The412Banner/bannerhub-api](https://github.com/The412Banner/bannerhub-api)), which re-publishes
 * XiaoJi GameHub's per-game configuration data as facts.
 *
 * Each recipe materializes the game's known-good config files (e.g. `Engine.ini`,
 * `GameUserSettings.ini`, graphics configs) into the prefix — exactly what GameHub does per game.
 * Only facts are stored: no GameHub code and no proprietary binaries. See `THIRD_PARTY_NOTICES`.
 */
object GameHubCatalog {
    private const val RESOURCE = "gamehub-recipes.json"

    val recipes: List<GameRecipe> by lazy { load() }

    private fun load(): List<GameRecipe> {
        val text = ProvisioningAssets.readText(RESOURCE) ?: return emptyList()
        return runCatching { RecipeCodec.decodeList(text) }.getOrDefault(emptyList())
    }

    fun forGame(source: GameSource, appId: String): GameRecipe? =
        recipes.firstOrNull { it.match.source == source && it.match.appId == appId }
}
