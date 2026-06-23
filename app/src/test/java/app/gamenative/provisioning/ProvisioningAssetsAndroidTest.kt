package app.gamenative.provisioning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.provisioning.engine.GameHubBaseline
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the provisioning JSON loads through the **real Android `AssetManager`** — the exact codepath
 * used inside the installed APK. The pure-JVM tests only exercise the classpath fallback, which is
 * what masked the original on-device failure (the files were loaded via `ClassLoader` and came back
 * null in the APK). Robolectric reads the module's merged assets via the genuine `AssetManager`, so a
 * green test here is concrete evidence the on-device load works.
 */
@RunWith(RobolectricTestRunner::class)
class ProvisioningAssetsAndroidTest {

    @Test
    fun bundledProvisioningJsonIsReachableViaAndroidAssetManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Direct AssetManager access (bypasses the classpath fallback) — throws if not packaged.
        listOf(
            "provisioning/gamehub-baseline.json",
            "provisioning/gamehub-verbs.json",
            "provisioning/gamehub-recipes.json",
            "provisioning/migrated-fixes.json",
        ).forEach { path ->
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            assertTrue("$path should be a non-empty asset", text.isNotBlank())
        }
    }

    @Test
    fun loadersResolveThroughProvisioningAssetsOnAndroid() {
        ProvisioningAssets.init(ApplicationProvider.getApplicationContext())
        val baseline = ProvisioningAssets.readText("gamehub-baseline.json")
        assertNotNull("baseline must load via context.assets on Android", baseline)
        assertTrue(baseline!!.contains("gamehub-baseline"))
        // The lazy loader decodes it to a real recipe (end-to-end through the Android path).
        assertNotNull("GameHubBaseline.recipe should decode on Android", GameHubBaseline.recipe)
    }
}
