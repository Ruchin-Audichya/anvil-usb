package dev.ruchin.anvil.catalog.windows

/** Regex-level parsing of Microsoft's software-download pages. Kept tiny and well-tested: if Microsoft changes markup, only this file changes. */
object MicrosoftPageParser {
    data class Edition(val id: String, val name: String)
    data class OvdfBeacon(val url: String, val rticks: String?)

    private val optionRe = Regex("""<option[^>]*\bvalue\s*=\s*"(\d+)"[^>]*>\s*([^<]+?)\s*</option>""", RegexOption.IGNORE_CASE)
    private val hashRowRe = Regex("""<td>\s*([^<]+?)\s*</td>\s*<td>\s*([A-Fa-f0-9]{64})\s*</td>""")
    private val ovdfRefRe = Regex("""id\s*=\s*"ov-df-ref"[^>]*\bvalue\s*=\s*"([^"]+)"""")
    private val beaconUrlRe = Regex("""\burl\s*:\s*"(https?://[^"]+)"""")
    private val rticksRe = Regex("""rticks="\s*\+\s*(\d+)""")

    /** `<option value="3813">Windows 11 (multi-edition ISO for x64 devices)</option>` → editions, newest first as listed. */
    fun parseProductEditions(html: String): List<Edition> =
        optionRe.findAll(html).map { Edition(it.groupValues[1], decode(it.groupValues[2])) }
            .filter { it.name.contains("Windows", ignoreCase = true) }
            .toList()

    /** "Verify your download" table: "English 64-bit" → hash. 32-bit rows are skipped. */
    fun parseHashTable(html: String): Map<String, String> = buildMap {
        for (m in hashRowRe.findAll(html)) {
            val label = decode(m.groupValues[1])
            if (label.contains("32-bit", true)) continue
            putIfAbsent(languageKey(label), m.groupValues[2].lowercase())
        }
    }

    fun parseOvdfRef(html: String): String? = ovdfRefRe.find(html)?.groupValues?.get(1)?.let(::decode)?.takeIf { it.startsWith("https://") }

    fun parseOvdfBeacon(script: String): OvdfBeacon? {
        val url = beaconUrlRe.find(script)?.groupValues?.get(1) ?: return null
        return OvdfBeacon(url, rticksRe.find(script)?.groupValues?.get(1))
    }

    /**
     * Maps both API language names ("English (United Kingdom)", "Chinese (Simplified)")
     * and hash-table labels ("English International 64-bit", "Chinese Simplified  64-bit")
     * onto the same key.
     */
    fun languageKey(name: String): String {
        val k = name.lowercase()
            .replace("64-bit", " ").replace("32-bit", " ")
            .replace(Regex("[()]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return ALIASES[k] ?: k
    }

    private val ALIASES = mapOf(
        "english united kingdom" to "english international",
        "english united states" to "english",
    )

    private fun decode(s: String) = s
        .replace("&amp;", "&").replace("&#61;", "=").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()
}
