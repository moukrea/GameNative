package app.gamenative.provisioning.resolver

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.schema.RecipeValidator
import java.io.File
import timber.log.Timber

/**
 * Highest-precedence source: user-supplied recipe overrides dropped as JSON files into
 * `<filesDir>/provisioning/recipes/`. This lets a tester provide a recipe for any game (e.g. via
 * `adb push`) without rebuilding the app. Invalid recipes are skipped with a log.
 */
class UserRecipeSource(private val context: Context) : RecipeSource {
    override val name: String = "user"

    override fun recipeFor(source: GameSource, appId: String): GameRecipe? {
        // Both the private files dir and the external files dir (the latter is `adb push`-friendly:
        // /sdcard/Android/data/<pkg>/files/provisioning/recipes/).
        val dirs = listOfNotNull(
            File(context.filesDir, RECIPES_SUBDIR),
            context.getExternalFilesDir(null)?.let { File(it, RECIPES_SUBDIR) },
        )
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: continue
            for (file in files) {
                val recipe = runCatching { RecipeCodec.decode(file.readText()) }.getOrNull() ?: continue
                if (recipe.match.source != source || recipe.match.appId != appId) continue
                val validation = RecipeValidator.validate(recipe)
                if (validation.isValid) return recipe
                Timber.tag("Provisioning").w("user recipe ${file.name} is invalid: ${validation.errors}")
            }
        }
        return null
    }

    companion object {
        private const val RECIPES_SUBDIR = "provisioning/recipes"
    }
}
