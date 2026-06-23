package app.gamenative.provisioning.engine

import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.GameRecipe
import app.gamenative.provisioning.model.PrefixFile
import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.model.matches
import app.gamenative.provisioning.verbs.NoopVerbContext
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
    private val verbContext: VerbContext = NoopVerbContext,
) {

    /**
     * Applies the recipe's declarative state only (components/env/DLL overrides/registry/files/ini
     * patches/cleanup/launch) — no dependency verbs, no downloads. Synchronous and safe to call on
     * the launch path. Dependency verbs are handled by [apply] (which needs network/extraction).
     */
    fun applyDeclarative(
        recipe: GameRecipe,
        device: DeviceProfile,
        state: PrefixState,
        force: Boolean = false,
    ): ProvisioningResult {
        val effective = effectiveRecipe(recipe, device)
        val hash = recipeHash(effective)
        val marker = "$RECIPE_MARKER_PREFIX$hash"
        if (!force && state.isMarked(marker)) return ProvisioningResult.AlreadyApplied(hash)

        val journal = Journal()
        val steps = mutableListOf<String>()
        val stepRef = arrayOf("start")
        return try {
            applyDeclarativeSteps(effective, state, journal, steps, stepRef, force)
            state.mark(marker)
            state.commit()
            ProvisioningResult.Applied(hash, steps, emptyList(), emptyList(), complete = true)
        } catch (e: Exception) {
            journal.rollback()
            state.commit()
            ProvisioningResult.Failed(hash, stepRef[0], e.message ?: e.toString(), rolledBack = true)
        }
    }

    suspend fun apply(recipe: GameRecipe, device: DeviceProfile, state: PrefixState): ProvisioningResult {
        val effective = effectiveRecipe(recipe, device)
        val hash = recipeHash(effective)
        val marker = "$RECIPE_MARKER_PREFIX$hash"
        if (state.isMarked(marker)) return ProvisioningResult.AlreadyApplied(hash)

        val journal = Journal()
        val steps = mutableListOf<String>()
        val stepRef = arrayOf("start")
        return try {
            applyDeclarativeSteps(effective, state, journal, steps, stepRef, force = false)

            // Dependency verbs are applied leniently: a verb that cannot install (e.g. offline)
            // is recorded but does NOT abort the launch and does NOT mark the recipe complete.
            stepRef[0] = "dependencies"
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
            ProvisioningResult.Failed(hash, stepRef[0], e.message ?: e.toString(), rolledBack = true)
        }
    }

    /** Applies components, env, DLL overrides, registry, files, INI patches, cleanup and launch. */
    private fun applyDeclarativeSteps(
        effective: GameRecipe,
        state: PrefixState,
        journal: Journal,
        steps: MutableList<String>,
        stepRef: Array<String>,
        force: Boolean,
    ) {
        // Passive (launch) application never overwrites an existing user value; an explicit
        // force-apply (e.g. the "apply recipe" action) does. files/iniPatches/cleanup are always
        // enforced (matching the legacy fixes' behaviour).
        stepRef[0] = "components"
        for ((kind, id) in effective.components.asMap()) {
            val old = state.getComponentPin(kind)
            if (!force && old != null) continue
            state.setComponentPin(kind, id)
            journal.record { old?.let { state.setComponentPin(kind, it) } }
            steps += "pin ${kind.manifestKey}=$id"
        }

        stepRef[0] = "env"
        for ((key, value) in effective.env) {
            val had = state.getEnv(key)
            if (!force && had != null) continue
            state.setEnv(key, value)
            journal.record { if (had != null) state.setEnv(key, had) else state.removeEnv(key) }
            steps += "env $key"
        }

        stepRef[0] = "dllOverrides"
        for ((dll, mode) in effective.dllOverrides) {
            val had = state.getEnv(WINE_DLL_OVERRIDES_ENV)
            DllOverrides.apply(state, dll, mode, force)
            journal.record {
                if (had != null) state.setEnv(WINE_DLL_OVERRIDES_ENV, had) else state.removeEnv(WINE_DLL_OVERRIDES_ENV)
            }
        }
        if (effective.dllOverrides.isNotEmpty()) steps += "dllOverrides(${effective.dllOverrides.size})"

        stepRef[0] = "registry"
        for (patch in effective.registry) {
            val had = state.getRegistryString(patch.hive, patch.key, patch.name)
            applyRegistryPatch(state, patch, force)
            journal.record {
                if (had != null) {
                    state.setRegistryString(patch.hive, patch.key, patch.name, had)
                } else {
                    state.removeRegistryValue(patch.hive, patch.key, patch.name)
                }
            }
        }
        if (effective.registry.isNotEmpty()) steps += "registry(${effective.registry.size})"

        stepRef[0] = "files"
        for (file in effective.files) {
            applyFile(state, file, journal)
            steps += "file ${file.driveCRelativePath}"
        }

        stepRef[0] = "iniPatches"
        for (patch in effective.iniPatches) {
            state.patchGameIni(patch.relativePath, patch.values)
            steps += "iniPatch ${patch.relativePath}"
        }

        stepRef[0] = "cleanup"
        for (path in effective.cleanup.deletePaths) {
            state.deletePath(path)
            steps += "delete $path"
        }

        stepRef[0] = "launch"
        effective.launch.args?.let { args ->
            val had = state.getLaunchArgs()
            if (force || had.isNullOrBlank()) {
                state.setLaunchArgs(args)
                journal.record { state.setLaunchArgs(had ?: "") }
                steps += "launchArgs"
            }
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
