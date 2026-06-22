package app.gamenative.provisioning

import app.gamenative.provisioning.model.RecipeCodec
import app.gamenative.provisioning.schema.RecipeValidationResult
import app.gamenative.provisioning.schema.RecipeValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeValidatorTest {

    private fun validate(fixture: String): RecipeValidationResult =
        RecipeValidator.validate(RecipeCodec.decode(RecipeFixtures.load(fixture)))

    @Test
    fun fullRecipeIsValid() {
        val res = validate("valid_full.json")
        assertTrue(res.errors.joinToString(), res.isValid)
    }

    @Test
    fun minimalRecipeIsValid() {
        assertTrue(validate("valid_minimal.json").isValid)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val res = validate("invalid_schema_version.json")
        assertFalse(res.isValid)
        assertTrue(res.errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun rejectsInvalidDllOverride() {
        val res = validate("invalid_dll_override.json")
        assertFalse(res.isValid)
        assertTrue(res.errors.any { it.contains("override mode") })
    }

    @Test
    fun rejectsInvalidDword() {
        val res = validate("invalid_dword.json")
        assertFalse(res.isValid)
        assertTrue(res.errors.any { it.contains("dword") })
    }

    @Test
    fun rejectsEmptyDeviceCondition() {
        val res = validate("invalid_empty_device_condition.json")
        assertFalse(res.isValid)
        assertTrue(res.errors.any { it.contains("when") })
    }

    @Test
    fun rejectsBlankId() {
        val res = validate("invalid_blank_id.json")
        assertFalse(res.isValid)
        assertTrue(res.errors.any { it.contains("id must not be blank") })
    }

    @Test
    fun rejectsFileWithBothContentAndIni() {
        val res = validate("invalid_file_both.json")
        assertFalse(res.isValid)
        assertTrue(res.errors.any { it.contains("exactly one of content") })
    }
}
