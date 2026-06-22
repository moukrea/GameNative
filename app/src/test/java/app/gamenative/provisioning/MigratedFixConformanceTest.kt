package app.gamenative.provisioning

import app.gamenative.provisioning.engine.DllOverrides
import app.gamenative.provisioning.engine.InMemoryPrefixState
import app.gamenative.provisioning.engine.MigratedFixCatalog
import app.gamenative.provisioning.engine.ProvisioningEngine
import app.gamenative.provisioning.engine.ProvisioningResult
import app.gamenative.provisioning.engine.RecordingVerbContext
import app.gamenative.provisioning.model.DeviceProfile
import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.schema.RecipeValidator
import app.gamenative.provisioning.verbs.VerbRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One conformance assertion describing an expected effect of a migrated recipe. */
@Serializable
data class MigrationAssertion(
    val kind: String,
    val description: String = "",
    val key: String? = null,
    val name: String? = null,
    val hive: String? = null,
    val dll: String? = null,
    val mode: String? = null,
    val path: String? = null,
    val iniKey: String? = null,
    val verb: String? = null,
    val expected: String? = null,
    val contains: String? = null,
)

@Serializable
data class MigrationAssertionGroup(
    val recipeId: String,
    val assertions: List<MigrationAssertion>,
)

/**
 * Conformance for the migration of the legacy hardcoded GameFixesRegistry: every migrated recipe is
 * schema-valid, round-trips, applies to an in-memory prefix, and reproduces the original fix's
 * effects (its conformance assertions).
 */
class MigratedFixConformanceTest {

    private val assertionGroups: Map<String, List<MigrationAssertion>> by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("provisioning/migrated-assertions.json"))
        val text = stream.bufferedReader().use { it.readText() }
        RecipeCodec.json
            .decodeFromString(ListSerializer(MigrationAssertionGroup.serializer()), text)
            .associate { it.recipeId to it.assertions }
    }

    @Test
    fun allMigratedFixesAreValidApplyAndConform() = runBlocking {
        val recipes = MigratedFixCatalog.recipes
        assertEquals("expected the full migrated catalog", 31, recipes.size)

        val engine = ProvisioningEngine(VerbRegistry.builtin(), RecordingVerbContext())

        for (recipe in recipes) {
            val validation = RecipeValidator.validate(recipe)
            assertTrue("${recipe.id} is invalid: ${validation.errors}", validation.isValid)

            assertEquals("${recipe.id} does not round-trip", recipe, RecipeCodec.decode(RecipeCodec.encode(recipe)))

            val state = InMemoryPrefixState()
            val result = engine.apply(recipe, DeviceProfile.UNKNOWN, state)
            assertTrue("${recipe.id} failed to apply: $result", result is ProvisioningResult.Applied)

            val asserts = assertionGroups[recipe.id].orEmpty()
            assertTrue("${recipe.id} has no conformance assertions", asserts.isNotEmpty())
            asserts.forEach { checkAssertion(recipe.id, recipe, state, it) }
        }
    }

    private fun checkAssertion(
        id: String,
        recipe: app.gamenative.provisioning.model.GameRecipe,
        state: InMemoryPrefixState,
        a: MigrationAssertion,
    ) {
        when (a.kind) {
            "env" ->
                assertEquals("$id env ${a.key}", a.expected, state.env[a.key])
            "dllOverride" ->
                assertEquals("$id dllOverride ${a.dll}", a.mode, DllOverrides.parse(state.env["WINEDLLOVERRIDES"])[a.dll])
            "registry" -> {
                val hive = if (a.hive.equals("user", ignoreCase = true)) "USER" else "SYSTEM"
                assertEquals("$id registry ${a.key}\\${a.name}", a.expected, state.registry["$hive|${a.key}|${a.name}"])
            }
            "launchArg" -> {
                val args = state.getLaunchArgs()
                if (a.expected != null) {
                    assertEquals("$id launchArg", a.expected, args)
                } else if (a.contains != null) {
                    assertTrue("$id launchArg contains ${a.contains}", args?.contains(a.contains) == true)
                }
            }
            "file" -> {
                val content = state.readText(requireNotNull(a.path) { "$id file assertion missing path" })
                assertNotNull("$id file ${a.path} not written", content)
                a.contains?.let { assertTrue("$id file ${a.path} missing '$it'", content!!.contains(it)) }
            }
            "iniPatch" ->
                assertEquals("$id iniPatch ${a.path}/${a.iniKey}", a.expected, state.gameIni[a.path]?.get(a.iniKey))
            "cleanup" ->
                assertTrue("$id cleanup ${a.path}", recipe.cleanup.deletePaths.contains(a.path))
            "dependency" ->
                assertTrue("$id dependency ${a.verb}", recipe.dependencies.contains(a.verb))
            else -> fail("$id unknown assertion kind ${a.kind}")
        }
    }
}
