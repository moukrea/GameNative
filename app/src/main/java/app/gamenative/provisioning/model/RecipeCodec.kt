package app.gamenative.provisioning.model

import kotlinx.serialization.json.Json

/**
 * JSON (de)serialization for [GameRecipe] and recipe lists.
 *
 * Configured to be forward-compatible (unknown keys are ignored so newer minor schema additions
 * do not break older clients) and to emit defaults (so a serialized recipe is self-describing and
 * diffable).
 */
object RecipeCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun decode(text: String): GameRecipe = json.decodeFromString(GameRecipe.serializer(), text)

    fun decodeList(text: String): List<GameRecipe> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GameRecipe.serializer()), text)

    fun encode(recipe: GameRecipe): String = json.encodeToString(GameRecipe.serializer(), recipe)

    fun encodeList(recipes: List<GameRecipe>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GameRecipe.serializer()), recipes)
}
