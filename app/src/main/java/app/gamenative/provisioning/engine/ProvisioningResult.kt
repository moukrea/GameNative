package app.gamenative.provisioning.engine

import app.gamenative.provisioning.verbs.VerbOutcome

/** Outcome of applying a recipe to a prefix. */
sealed interface ProvisioningResult {
    val recipeHash: String

    /**
     * The declarative state (components/env/DLL overrides/registry/files/launch) was applied.
     *
     * [complete] is true only when every dependency verb is satisfied; when false (e.g. a verb
     * could not download while offline) the recipe is intentionally NOT marked applied, so the next
     * launch re-runs and self-heals — the declarative steps are idempotent, so re-applying is cheap.
     */
    data class Applied(
        override val recipeHash: String,
        val steps: List<String>,
        val verbOutcomes: List<VerbOutcome>,
        /** Guest commands the launcher must run for installer-style verbs, in order. */
        val pendingCommands: List<String>,
        val complete: Boolean,
    ) : ProvisioningResult

    /** State had not drifted (recipe marker already present) — nothing to do. */
    data class AlreadyApplied(override val recipeHash: String) : ProvisioningResult

    /** A step failed; prior steps were rolled back and the recipe was NOT marked applied. */
    data class Failed(
        override val recipeHash: String,
        val failedStep: String,
        val error: String,
        val rolledBack: Boolean,
    ) : ProvisioningResult
}
