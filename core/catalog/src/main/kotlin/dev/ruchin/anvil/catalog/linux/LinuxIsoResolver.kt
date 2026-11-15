package dev.ruchin.anvil.catalog.linux

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LinuxCatalogException(userMessage: String, technical: String) : AnvilException(userMessage, technical)

/** A distro ISO that is ready to download: exact file, signed SHA-256, mirrors fastest first. */
data class ResolvedLinuxIso(
    val entry: LinuxEntry,
    val fileName: String,
    /** Exact release from the checksum list ("26.04.1"), or the manifest's version for fixed names. */
    val version: String,
    /** SHA-256 from a checksum list whose signature was verified with [signer]. */
    val sha256: String,
    val sizeBytes: Long?,
    /** ISO URLs, fastest mirror first. */
    val mirrorUrls: List<String>,
    val probes: List<MirrorProbe>,
    val signer: PinnedKey,
) {
    val title get() = "${entry.distroName} $version ${entry.edition}".replace("  ", " ")
}

/**
 * Resolves a manifest entry into a verified download:
 *
 *  1. fetch the checksum list (and its detached signature) from the first mirror that serves it
 *  2. verify the signature with the distro key pinned in the app — stop here on any failure
 *  3. pick the ISO out of the *signed* text ([IsoPattern]) and take its SHA-256
 *  4. probe every mirror for that ISO and order them fastest first ([MirrorProber])
 *
 * The downloader then checks the ISO against the signed SHA-256, so a mirror can make a download
 * fail but can never make Anvil write a file the distro didn't sign.
 */
class LinuxIsoResolver(
    client: OkHttpClient,
    private val keys: PinnedKeys = PinnedKeys.load(),
    private val prober: MirrorProber = MirrorProber(client),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val client = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun resolve(entry: LinuxEntry): ResolvedLinuxIso = withContext(ioDispatcher) {
        val key = keys[entry.keyId]
            ?: throw LinuxCatalogException("This app can't verify ${entry.distroName} yet. Please update Anvil.", "key ${entry.keyId} not pinned")

        // 1–2. Signed checksum list.
        val (checksumBytes, servedBy) = fetchFirst(urlsFor(entry, entry.checksumUrl), "checksum list", entry)
        val signedText = if (entry.clearSigned) {
            SignatureVerifier.verifyClearSigned(checksumBytes, key)
        } else {
            val sigUrls = urlsFor(entry, entry.signatureUrl!!).let { all ->
                // Prefer the mirror that served the checksum list, so both files come from the same release snapshot.
                val same = all.filter { servedBy != null && it.startsWith(servedBy) }
                same + (all - same.toSet())
            }
            val (sig, _) = fetchFirst(sigUrls, "signature", entry)
            SignatureVerifier.verifyDetached(checksumBytes, sig, key)
            String(checksumBytes, Charsets.UTF_8)
        }
        DebugLog.i(TAG, "${entry.id}: checksum list signed by ${key.owner} (${key.fingerprint})")

        // 3. The ISO and its hash, from the signed text only.
        val dir = entry.isoPath.substringBeforeLast('/', "")
        val namePattern = entry.isoPath.substringAfterLast('/')
        val match = IsoPattern.match(namePattern, Checksums.parse(signedText))
            ?: throw LinuxCatalogException(
                "Couldn't find the ${entry.distroName} ${entry.edition} image in the official checksum list. " +
                    "The release may have moved; try again later or update Anvil.",
                "no match for ${entry.isoPath} in ${entry.checksumUrl}",
            )

        // 4. Fastest mirrors for that exact file.
        val isoUrls = entry.mirrors.map { root -> join(root, if (dir.isEmpty()) match.fileName else "$dir/${match.fileName}") }
        val ranked = prober.rank(isoUrls)
        if (ranked.isEmpty()) {
            throw LinuxCatalogException(
                "None of the ${entry.distroName} download mirrors answered. Check your connection and try again.",
                "all ${isoUrls.size} mirrors failed for ${match.fileName}",
            )
        }
        ResolvedLinuxIso(
            entry = entry,
            fileName = match.fileName,
            version = match.version ?: entry.version,
            sha256 = match.sha256,
            sizeBytes = ranked.firstNotNullOfOrNull { it.totalBytes },
            mirrorUrls = ranked.map { it.url },
            probes = ranked,
            signer = key,
        )
    }

    /** Absolute URL as-is; a relative path is tried on every mirror root in manifest order. */
    private fun urlsFor(entry: LinuxEntry, path: String): List<String> =
        if (path.startsWith("https://")) listOf(path) else entry.mirrors.map { join(it, path) }

    private fun fetchFirst(urls: List<String>, what: String, entry: LinuxEntry): Pair<ByteArray, String?> {
        val errors = mutableListOf<String>()
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", MirrorProber.USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { errors += "$url: HTTP ${resp.code}"; return@use }
                    val bytes = resp.body.bytes()
                    if (bytes.size > MAX_SMALL_FILE) { errors += "$url: ${bytes.size} bytes is too large for a $what"; return@use }
                    val root = entry.mirrors.firstOrNull { url.startsWith(it) }
                    return bytes to root
                }
            } catch (e: Exception) {
                errors += "$url: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw LinuxCatalogException(
            "Couldn't download the official ${entry.distroName} $what. Check your connection and try again.",
            errors.joinToString("; "),
        )
    }

    companion object {
        private const val TAG = "LinuxResolver"
        private const val MAX_SMALL_FILE = 1 shl 20

        fun join(root: String, path: String) = root.trimEnd('/') + "/" + path.trimStart('/')
    }
}
