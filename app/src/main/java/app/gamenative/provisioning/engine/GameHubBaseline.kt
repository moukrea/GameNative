package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RecipeCodec

/**
 * GameHub's default-prefix provisioning, reverse-engineered: the common Windows runtime
 * dependencies (VC++ 2005-2022, d3dx9/d3dcompiler, .NET 4.8, PhysX, XAct, XNA, …) plus its
 * Box64/FEX execution tuning. GameHub bakes these into its base imagefs, which is why many games
 * boot under GameHub but not under a vanilla prefix. Applied (under any per-game recipe) at launch
 * when the per-game provisioning flag is on.
 */
object GameHubBaseline {
    private const val RESOURCE = "provisioning/gamehub-baseline.json"

    val recipe: GameRecipe? by lazy { load() }

    private fun load(): GameRecipe? {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE) ?: return null
        val text = stream.bufferedReader().use { it.readText() }
        return runCatching { RecipeCodec.decode(text) }.getOrNull()
    }
}
