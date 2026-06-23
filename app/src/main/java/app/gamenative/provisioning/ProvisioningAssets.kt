package app.gamenative.provisioning

import android.content.Context
import timber.log.Timber

/**
 * Loads bundled provisioning JSON (recipes, verbs, baseline) at runtime.
 *
 * IMPORTANT: on Android these files must live in `src/main/assets/provisioning/` and be read via
 * [Context.getAssets], NOT via `ClassLoader.getResourceAsStream`. The classpath approach passes in
 * JVM unit tests (the files are on the test classpath) but returns null inside the installed APK,
 * which silently disabled the entire provisioning feature on-device. This holder is initialized with
 * the application context at startup ([init]) so every loader resolves the same way the rest of the
 * app loads bundled JSON (`context.assets.open(...)`).
 *
 * The classpath fallback is kept solely so the pure-JVM unit tests (which have no [Context]) can
 * still read the same files; the unit-test source set adds `src/main/assets` to its resources.
 */
object ProvisioningAssets {
    private const val DIR = "provisioning"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Reads `provisioning/<fileName>` as text, or null if it cannot be found. */
    fun readText(fileName: String): String? {
        val assetPath = "$DIR/$fileName"
        appContext?.let { ctx ->
            runCatching {
                return ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
            }.onFailure { Timber.tag("Provisioning").w(it, "asset read failed: %s", assetPath) }
        }
        // JVM/unit-test fallback (no Android context): read from the classpath.
        return javaClass.classLoader?.getResourceAsStream(assetPath)
            ?.bufferedReader()?.use { it.readText() }
    }
}
