package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.PrefixFile
import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.model.matches
import app.gamenative.provisioning.verbs.VerbContext
import app.gamenative.provisioning.verbs.VerbOutcome
import app.gamenative.provisioning.verbs.VerbRegistry
import java.security.MessageDigest

/** Prefix marker recording that a specific resolved recipe (by content hash) has been applied. */
const val RECIPE_MARKER_PREFIX: String = "provisioning.recipe."

/**
 * Applies a [GameRecipe] to a prefix idempotently and transactionally.
 *
 * - **Device branching**: the recipe is first reduced to an effective recipe by overlaying every
 *   [app.gamenative.provisioning.model.DeviceOverride] whose condition matches the device.
 * - **Idempotence**: the effective recipe's content hash is recorded as a marker; re-applying the
 *   same recipe to an unchanged prefix is a no-op ([ProvisioningResult.AlreadyApplied]).
 * - **Transactional**: each mutating step records an inverse; if a later step throws, the prior
 *   steps are rolled back and the recipe is NOT marked applied, so the next launch retries cleanly.
 *
 * The engine is pure with respect to [PrefixState], which makes it fully unit-testable headlessly.
 */
class ProvisioningEngine(
    private val verbRegistry: VerbRegistry = VerbRegistry.builtin(),
    private val verbContext: VerbContext,
) {

    suspend fun apply(recipe: GameRecipe, device: DeviceProfile, state: PrefixState): ProvisioningResult {
        val effective = effectiveRecipe(recipe, device)
        val hash = recipeHash(effective)
        val marker = "$RECIPE_MARKER_PREFIX$hash"
        if (state.isMarked(marker)) return ProvisioningResult.AlreadyApplied(hash)

        val journal = Journal()
        val steps = mutableListOf<String>()
        var step = "start"
        return try {
            step = "components"
            for ((kind, id) in effective.components.asMap()) {
                val old = state.getComponentPin(kind)
                state.setComponentPin(kind, id)
                journal.record { old?.let { state.setComponentPin(kind, it) } }
                steps += "pin ${kind.manifestKey}=$id"
            }

            step = "env"
            for ((key, value) in effective.env) {
                val had = state.getEnv(key)
                state.setEnv(key, value)
                journal.record { if (had != null) state.setEnv(key, had) else state.removeEnv(key) }
                steps += "env $key"
            }

            step = "dllOverrides"
            for ((dll, mode) in effective.dllOverrides) {
                val had = state.getEnv(WINE_DLL_OVERRIDES_ENV)
                DllOverrides.apply(state, dll, mode)
                journal.record {
                    if (had != null) state.setEnv(WINE_DLL_OVERRIDES_ENV, had) else state.removeEnv(WINE_DLL_OVERRIDES_ENV)
                }
            }
            if (effective.dllOverrides.isNotEmpty()) steps += "dllOverrides(${effective.dllOverrides.size})"

            step = "registry"
            for (patch in effective.registry) {
                val had = state.getRegistryString(patch.hive, patch.key, patch.name)
                applyRegistryPatch(state, patch)
                journal.record {
                    if (had != null) {
                        state.setRegistryString(patch.hive, patch.key, patch.name, had)
                    } else {
                        state.removeRegistryValue(patch.hive, patch.key, patch.name)
                    }
                }
            }
            if (effective.registry.isNotEmpty()) steps += "registry(${effective.registry.size})"

            step = "files"
            for (file in effective.files) {
                applyFile(state, file, journal)
                steps += "file ${file.driveCRelativePath}"
            }

            step = "cleanup"
            for (path in effective.cleanup.deletePaths) {
                state.deletePath(path)
                steps += "delete $path"
            }

            step = "launch"
            effective.launch.args?.let { args ->
                val had = state.getLaunchArgs()
                if (had.isNullOrBlank()) {
                    state.setLaunchArgs(args)
                    journal.record { state.setLaunchArgs(had ?: "") }
                    steps += "launchArgs"
                }
            }

            // Dependency verbs are applied leniently: a verb that cannot install (e.g. offline)
            // is recorded but does NOT abort the launch and does NOT mark the recipe complete.
            step = "dependencies"
            val outcomes = mutableListOf<VerbOutcome>()
            for (dep in effective.dependencies) {
                val verb = verbRegistry.get(dep)
                if (verb == null) {
                    outcomes += VerbOutcome(dep, installed = false, skipped = false, error = "unknown verb")
                    continue
                }
                outcomes += verb.install(state, verbContext)
            }
            val pendingCommands = outcomes.filter { it.installed && it.command != null }.map { it.command!! }
            val complete = outcomes.all { it.installed || it.skipped }

            if (complete) state.mark(marker)
            state.commit()
            ProvisioningResult.Applied(hash, steps, outcomes, pendingCommands, complete)
        } catch (e: Exception) {
            journal.rollback()
            state.commit()
            ProvisioningResult.Failed(hash, step, e.message ?: e.toString(), rolledBack = true)
        }
    }

    private fun applyFile(state: PrefixState, file: PrefixFile, journal: Journal) {
        val existed = state.fileExists(file.driveCRelativePath)
        val before = if (existed) state.readFile(file.driveCRelativePath) else null
        when {
            file.content != null -> state.writeText(file.driveCRelativePath, file.content)
            file.iniMerge != null -> {
                val merged = IniMerge.merge(state.readText(file.driveCRelativePath), file.iniMerge)
                state.writeText(file.driveCRelativePath, merged)
            }
        }
        journal.record {
            if (before != null) state.writeFile(file.driveCRelativePath, before) else state.deletePath(file.driveCRelativePath)
        }
    }

    companion object {
        /** Reduces a recipe to its device-specific effective form (device overrides folded in). */
        fun effectiveRecipe(recipe: GameRecipe, device: DeviceProfile): GameRecipe {
            var components = recipe.components
            val env = LinkedHashMap(recipe.env)
            val dllOverrides = LinkedHashMap(recipe.dllOverrides)
            val registry = recipe.registry.toMutableList()
            val dependencies = recipe.dependencies.toMutableList()
            for (override in recipe.deviceOverrides) {
                if (!override.whenCondition.matches(device)) continue
                override.set.components?.let { components = it.overlayOnto(components) }
                env.putAll(override.set.env)
                dllOverrides.putAll(override.set.dllOverrides)
                registry.addAll(override.set.registry)
                dependencies.addAll(override.set.dependencies.filter { it !in dependencies })
            }
            return recipe.copy(
                components = components,
                env = env,
                dllOverrides = dllOverrides,
                registry = registry,
                dependencies = dependencies,
                deviceOverrides = emptyList(),
            )
        }

        /** Stable short content hash (first 16 hex chars of SHA-256 over canonical JSON). */
        fun recipeHash(recipe: GameRecipe): String {
            val json = RecipeCodec.encode(recipe)
            val digest = MessageDigest.getInstance("SHA-256").digest(json.encodeToByteArray())
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)
        }
    }
}

/** Records inverse operations so a partially-applied recipe can be rolled back on failure. */
private class Journal {
    private val undos = ArrayDeque<() -> Unit>()

    fun record(undo: () -> Unit) {
        undos.addLast(undo)
    }

    fun rollback() {
        while (undos.isNotEmpty()) {
            runCatching { undos.removeLast().invoke() }
        }
    }
}
