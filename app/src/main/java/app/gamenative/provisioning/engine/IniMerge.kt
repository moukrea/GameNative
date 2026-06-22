package app.gamenative.provisioning.engine

/** Merges key/values into an INI document by section, preserving existing entries. Idempotent. */
object IniMerge {

    fun merge(existing: String?, patch: Map<String, Map<String, String>>): String {
        val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val order = ArrayList<String>()
        var current = ""
        sections[current] = LinkedHashMap()
        order.add(current)

        existing?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith(";") || line.startsWith("#") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    current = line.substring(1, line.length - 1).trim()
                    if (current !in sections) {
                        sections[current] = LinkedHashMap()
                        order.add(current)
                    }
                }
                else -> {
                    val eq = line.indexOf('=')
                    if (eq >= 0) {
                        sections.getValue(current)[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                    }
                }
            }
        }

        for ((section, kv) in patch) {
            val target = sections.getOrPut(section) {
                order.add(section)
                LinkedHashMap()
            }
            target.putAll(kv)
        }

        val sb = StringBuilder()
        for (section in order) {
            val kv = sections[section] ?: continue
            if (section.isEmpty() && kv.isEmpty()) continue
            if (section.isNotEmpty()) sb.append('[').append(section).append("]\n")
            for ((k, v) in kv) sb.append(k).append('=').append(v).append('\n')
        }
        return sb.toString()
    }
}
