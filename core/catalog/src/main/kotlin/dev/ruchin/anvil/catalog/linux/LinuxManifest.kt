package dev.ruchin.anvil.catalog.linux

import dev.ruchin.anvil.common.DebugLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Linux catalog: which distros Anvil offers and where their official ISOs live.
 *
 * A copy ships inside the app (`assets/linux-manifest.json`); a newer copy is fetched from the
 * repository so new point releases appear without an app update. The manifest is *not* trusted for
 * integrity: it can only name a [LinuxEntry.keyId] that is pinned inside the app ([PinnedKeys]), and
 * every ISO hash comes from a checksum file signed by that key.
 */
@Serializable
data class LinuxManifest(
    val schema: Int,
    val updated: String? = null,
    val entries: List<LinuxEntry>,
) {
    companion object {
        const val SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(text: String): LinuxManifest = json.decodeFromString(serializer(), text)
    }
}

/**
 * One downloadable edition, e.g. Ubuntu 26.04 LTS Desktop.
 *
 * Paths are relative to each mirror root in [mirrors]. [isoPath] may contain one `{version}`
 * placeholder (digits, dots and dashes); it is matched against the *signed* checksum list and the
 * newest match wins, so `ubuntu-{version}-desktop-amd64.iso` picks 26.04.1 over 26.04 and ignores betas.
 */
@Serializable
data class LinuxEntry(
    /** Stable id, e.g. "ubuntu-lts-desktop". */
    val id: String,
    /** Distro id: "ubuntu", "mint", "fedora", "debian". */
    val distro: String,
    /** Display name of the distro, e.g. "Linux Mint". */
    val distroName: String,
    /** Display name of the edition, e.g. "Desktop LTS", "Cinnamon", "netinst". */
    val edition: String,
    /** Release shown before resolving, e.g. "26.04 LTS". The exact point release comes from the checksum file. */
    val version: String,
    val arch: String = "amd64",
    /** Mirror roots (https), official host first. */
    val mirrors: List<String>,
    val isoPath: String,
    /** Checksum file: relative to a mirror root, or an absolute https URL. */
    val checksumUrl: String,
    /** Detached signature of the checksum file. Null when the checksum file is clear-signed (Fedora's CHECKSUM). */
    val signatureUrl: String? = null,
    /** Which pinned key must have signed the checksum file. */
    val keyId: String,
    /** Approximate download size for the picker; the real size comes from the mirror. */
    val approxSizeMb: Int? = null,
    val description: String? = null,
) {
    val clearSigned: Boolean get() = signatureUrl == null

    /** Problems that make the entry unusable (never shown to users; logged and the entry dropped). */
    fun problems(keys: PinnedKeys): List<String> = buildList {
        if (mirrors.isEmpty()) add("no mirrors")
        mirrors.filterNot { it.startsWith("https://") }.forEach { add("mirror is not https: $it") }
        listOfNotNull(checksumUrl, signatureUrl).filter { it.contains("://") && !it.startsWith("https://") }
            .forEach { add("not https: $it") }
        if (!keys.contains(keyId)) add("key '$keyId' is not pinned in this app")
        if (isoPath.isBlank() || isoPath.startsWith("/") || ".." in isoPath) add("bad isoPath '$isoPath'")
        if (isoPath.split("{version}").size > 2) add("more than one {version} in isoPath")
    }
}

/** Keeps only entries this app can verify. */
fun LinuxManifest.usable(keys: PinnedKeys, tag: String = "LinuxManifest"): LinuxManifest {
    val ok = entries.filter { e ->
        val p = e.problems(keys)
        if (p.isNotEmpty()) DebugLog.w(tag, "dropping ${e.id}: ${p.joinToString()}")
        p.isEmpty()
    }
    return copy(entries = ok)
}
