package app.gamenative.provisioning.schema

import app.gamenative.provisioning.model.DeviceOverride
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.RECIPE_SCHEMA_VERSION
import app.gamenative.provisioning.model.RegistryPatch
import app.gamenative.provisioning.model.RegistryValueType

/** Outcome of validating a [GameRecipe]: a recipe is usable iff [errors] is empty. */
data class RecipeValidationResult(
    val errors: List<String>,
    val warnings: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    companion object {
        fun valid(): RecipeValidationResult = RecipeValidationResult(emptyList(), emptyList())
    }
}

/**
 * Validates a [GameRecipe] against the schema's structural and value-level rules.
 *
 * This is a pure, Android-free function so the programmatic oracle can exercise it headlessly.
 * Existence checks that need runtime state (does a pinned component id exist in `manifest.json`?
 * does a dependency verb exist in the registry?) are intentionally NOT done here — they belong to
 * resolve time, where the resolver degrades leniently rather than failing closed.
 */
object RecipeValidator {

    /** DLL-override mode tokens accepted in a `WINEDLLOVERRIDES`-style value (plus empty = disabled). */
    private val DLL_OVERRIDE_TOKENS = setOf("native", "builtin", "n", "b", "d", "disabled")

    fun validate(recipe: GameRecipe): RecipeValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        when {
            recipe.schemaVersion < 1 ->
                errors += "schemaVersion must be >= 1 (was ${recipe.schemaVersion})"
            recipe.schemaVersion > RECIPE_SCHEMA_VERSION ->
                errors += "unsupported schemaVersion ${recipe.schemaVersion} (max supported $RECIPE_SCHEMA_VERSION)"
        }

        if (recipe.id.isBlank()) errors += "id must not be blank"
        if (recipe.match.appId.isBlank()) errors += "match.appId must not be blank"

        validateEnv(recipe.env, "env", errors)
        validateDllOverrides(recipe.dllOverrides, "dllOverrides", errors)
        recipe.registry.forEachIndexed { i, p -> validateRegistryPatch(p, "registry[$i]", errors) }
        validateComponentPins(recipe, errors)

        recipe.dependencies.forEachIndexed { i, dep ->
            if (dep.isBlank()) errors += "dependencies[$i] must not be blank"
        }

        recipe.files.forEachIndexed { i, f ->
            val ctx = "files[$i]"
            if (f.driveCRelativePath.isBlank()) errors += "$ctx.driveCRelativePath must not be blank"
            val hasContent = f.content != null
            val hasIni = f.iniMerge != null
            if (hasContent == hasIni) {
                errors += "$ctx must set exactly one of content or iniMerge"
            }
        }

        recipe.iniPatches.forEachIndexed { i, patch ->
            if (patch.relativePath.isBlank()) errors += "iniPatches[$i].relativePath must not be blank"
        }

        recipe.cleanup.deletePaths.forEachIndexed { i, p ->
            if (p.isBlank()) errors += "cleanup.deletePaths[$i] must not be blank"
        }

        recipe.deviceOverrides.forEachIndexed { i, o -> validateDeviceOverride(o, "deviceOverrides[$i]", errors, warnings) }

        recipe.provenance?.let {
            if (it.source.isBlank()) errors += "provenance.source must not be blank"
        }

        return RecipeValidationResult(errors, warnings)
    }

    private fun validateEnv(env: Map<String, String>, ctx: String, errors: MutableList<String>) {
        env.keys.forEach { key ->
            if (key.isBlank()) {
                errors += "$ctx key must not be blank"
            } else if (key.any { it.isWhitespace() } || key.contains('=')) {
                errors += "$ctx key '$key' must not contain whitespace or '='"
            }
        }
    }

    private fun validateDllOverrides(overrides: Map<String, String>, ctx: String, errors: MutableList<String>) {
        overrides.forEach { (dll, mode) ->
            if (dll.isBlank()) {
                errors += "$ctx key (DLL name) must not be blank"
                return@forEach
            }
            // An empty mode means "disabled" and is valid; otherwise every token must be recognized.
            if (mode.isNotEmpty()) {
                val bad = mode.split(',').map { it.trim() }.filter { it.isEmpty() || it !in DLL_OVERRIDE_TOKENS }
                if (bad.isNotEmpty()) {
                    errors += "$ctx['$dll'] has invalid override mode '$mode' (allowed tokens: ${DLL_OVERRIDE_TOKENS.joinToString()})"
                }
            }
        }
    }

    private fun validateRegistryPatch(patch: RegistryPatch, ctx: String, errors: MutableList<String>) {
        if (patch.key.isBlank()) errors += "$ctx.key must not be blank"
        if (patch.type == RegistryValueType.DWORD && !isParsableDword(patch.value)) {
            errors += "$ctx.value '${patch.value}' is not a valid dword (decimal or 0x-hex)"
        }
    }

    private fun validateComponentPins(recipe: GameRecipe, errors: MutableList<String>) {
        recipe.components.asMap().forEach { (kind, id) ->
            if (id.isBlank()) errors += "components.${kind.manifestKey} must not be blank when present"
        }
    }

    private fun validateDeviceOverride(
        o: DeviceOverride,
        ctx: String,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (o.whenCondition.isEmpty()) {
            errors += "$ctx.when must declare at least one predicate (gpuFamily/socContains/driverContains)"
        }
        if (o.set.isEmpty()) {
            warnings += "$ctx.set is empty — the override is a no-op"
        }
        validateEnv(o.set.env, "$ctx.set.env", errors)
        validateDllOverrides(o.set.dllOverrides, "$ctx.set.dllOverrides", errors)
        o.set.registry.forEachIndexed { i, p -> validateRegistryPatch(p, "$ctx.set.registry[$i]", errors) }
        o.set.dependencies.forEachIndexed { i, dep ->
            if (dep.isBlank()) errors += "$ctx.set.dependencies[$i] must not be blank"
        }
        o.set.components?.asMap()?.forEach { (kind, id) ->
            if (id.isBlank()) errors += "$ctx.set.components.${kind.manifestKey} must not be blank when present"
        }
    }

    private fun isParsableDword(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        return try {
            if (v.startsWith("0x", ignoreCase = true)) {
                v.substring(2).toLong(16)
            } else {
                v.toLong(10)
            }
            true
        } catch (_: NumberFormatException) {
            false
        }
    }
}
