package app.gamenative.provisioning

/** Loads recipe JSON fixtures from the test classpath (app/src/test/resources/provisioning/recipes). */
internal object RecipeFixtures {
    fun load(name: String): String {
        val path = "provisioning/recipes/$name"
        val stream = RecipeFixtures::class.java.classLoader?.getResourceAsStream(path)
            ?: error("fixture not found on test classpath: $path")
        return stream.bufferedReader().use { it.readText() }
    }
}
