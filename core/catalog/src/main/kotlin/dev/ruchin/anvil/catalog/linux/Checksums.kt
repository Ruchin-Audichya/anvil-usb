package dev.ruchin.anvil.catalog.linux

/** File name → lower-case SHA-256, from a distro checksum list. */
object Checksums {
    private val GNU = Regex("""^([0-9a-fA-F]{64})\s+\*?(\S.*?)\s*$""")
    private val BSD = Regex("""^SHA256\s*\((.+)\)\s*=\s*([0-9a-fA-F]{64})\s*$""")

    /**
     * Parses both common formats:
     *  - GNU coreutils, used by Ubuntu, Debian and Mint: `<hash> *file.iso` or `<hash>  file.iso`
     *  - BSD tag style, used by Fedora: `SHA256 (file.iso) = <hash>`
     * Comments, blank lines and other digests (SHA1, MD5) are ignored.
     */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            GNU.matchEntire(line)?.let { out[it.groupValues[2].removePrefix("./")] = it.groupValues[1].lowercase(); continue }
            BSD.matchEntire(line)?.let { out[it.groupValues[1].removePrefix("./")] = it.groupValues[2].lowercase() }
        }
        return out
    }
}

/** An ISO picked out of a signed checksum list. */
data class IsoMatch(val fileName: String, val version: String?, val sha256: String)

/**
 * Matches the file-name part of a manifest `isoPath` against a checksum list. A `{version}`
 * placeholder matches digits, dots and dashes ("26.04.1", "13.7.0", "45-1.4"), which skips
 * betas and sibling images (debian-edu-…, …-beta-desktop…). The newest version wins.
 */
object IsoPattern {
    private const val PLACEHOLDER = "{version}"

    fun match(fileNamePattern: String, checksums: Map<String, String>): IsoMatch? {
        if (PLACEHOLDER !in fileNamePattern) {
            return checksums[fileNamePattern]?.let { IsoMatch(fileNamePattern, null, it) }
        }
        val (before, after) = fileNamePattern.split(PLACEHOLDER, limit = 2)
        val re = Regex(Regex.escape(before) + "([0-9][0-9.\\-]*)" + Regex.escape(after))
        return checksums.mapNotNull { (name, hash) ->
            re.matchEntire(name)?.let { IsoMatch(name, it.groupValues[1].trimEnd('.', '-'), hash) }
        }.maxWithOrNull { a, b -> compareVersions(a.version!!, b.version!!) }
    }

    /** Natural order: 26.04.1 > 26.04, 13.10.0 > 13.9.0, 45-1.4 > 45-1.2. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { -1 }).compareTo(pb.getOrElse(i) { -1 })
            if (c != 0) return c
        }
        return 0
    }
}
