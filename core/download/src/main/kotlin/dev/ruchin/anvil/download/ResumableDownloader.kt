package dev.ruchin.anvil.download

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.TransferMeter
import dev.ruchin.anvil.common.hexDigestEquals
import dev.ruchin.anvil.common.sha256
import dev.ruchin.anvil.common.toHex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class DownloadStage { CONNECTING, DOWNLOADING, WAITING_FOR_NETWORK, VERIFYING, DONE }

data class DownloadProgress(
    val stage: DownloadStage,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val retryInSeconds: Int? = null,
) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

data class DownloadRequest(
    /** Called for the initial URL and again when a signed URL expires (HTTP 403/404/410). */
    val urlProvider: suspend (refresh: Boolean) -> String,
    val destination: File,
    val expectedSha256: String? = null,
    val expectedSize: Long? = null,
    val userAgent: String = DEFAULT_UA,
) {
    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36"
    }
}

class HashMismatchException(expected: String, actual: String) : AnvilException(
    userMessage = "The downloaded file is damaged (checksum mismatch). It has been deleted — please download again.",
    technical = "sha256 expected=$expected actual=$actual",
)

/**
 * Downloads large files (Windows ISOs are 6–9 GB) with:
 *  - resume via `Range` + `If-Range` (ETag/Last-Modified), persisted in a sidecar
 *    `.part.json` so a resume survives process death / reboot
 *  - automatic retry with exponential backoff on network loss
 *  - signed-URL refresh: Microsoft links expire after 24 h; on 403/410 we ask the
 *    [DownloadRequest.urlProvider] for a fresh one and keep the bytes we have
 *  - SHA-256 verification against the officially published hash
 */
class ResumableDownloader(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxRetries: Int = 8,
    private val baseBackoffMs: Long = 2_000,
    private val progressIntervalNanos: Long = 250_000_000L,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class PartMeta(val validator: String?, val total: Long)

    suspend fun download(req: DownloadRequest, onProgress: (DownloadProgress) -> Unit = {}): File =
        withContext(ioDispatcher) {
            val dest = req.destination
            if (dest.exists() && req.expectedSha256 != null) {
                // Already downloaded earlier (history / cached ISO re-use).
                if (verify(dest, req.expectedSha256, onProgress)) return@withContext dest
                dest.delete()
            }
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            val metaFile = File(dest.path + ".part.json")
            var meta = readMeta(metaFile, part)

            var url = req.urlProvider(false)
            var failures = 0
            var urlRefreshes = 0
            var completed = false
            val meter = TransferMeter()

            while (true) {
                ensureActive()
                val have = if (part.exists()) part.length() else 0L
                if (meta != null && meta.total > 0 && have == meta.total) break
                onProgress(DownloadProgress(DownloadStage.CONNECTING, have, meta?.total ?: req.expectedSize ?: -1))

                val builder = Request.Builder().url(url).header("User-Agent", req.userAgent)
                if (have > 0) {
                    builder.header("Range", "bytes=$have-")
                    meta?.validator?.let { builder.header("If-Range", it) }
                }
                try {
                    client.newCall(builder.build()).execute().use { resp ->
                        when {
                            resp.code == 416 && meta != null && have >= meta.total -> return@use // complete
                            resp.code in listOf(401, 403, 404, 410) && urlRefreshes < MAX_URL_REFRESHES -> {
                                DebugLog.w(TAG, "HTTP ${resp.code}: refreshing download link")
                                url = req.urlProvider(true)
                                urlRefreshes++
                                return@use
                            }
                            !resp.isSuccessful -> throw HttpStatusException(resp.code)
                        }
                        val body = resp.body
                        val resumed = resp.code == 206
                        val start = if (resumed) have else 0L
                        val total = if (resumed) {
                            parseContentRangeTotal(resp.header("Content-Range")) ?: (have + body.contentLength())
                        } else body.contentLength()
                        if (req.expectedSize != null && total > 0 && total != req.expectedSize) {
                            throw AnvilException("The server sent a file of unexpected size.", "size $total != ${req.expectedSize}")
                        }
                        val validator = resp.header("ETag")?.takeUnless { it.startsWith("W/") } ?: resp.header("Last-Modified")
                        if (!resumed && have > 0) DebugLog.w(TAG, "server ignored Range; restarting from 0")
                        meta = PartMeta(validator, total).also { writeMeta(metaFile, it) }

                        RandomAccessFile(part, "rw").use { raf ->
                            raf.setLength(start)
                            raf.seek(start)
                            val buf = ByteArray(BUFFER)
                            var done = start
                            var lastEmit = 0L
                            meter.reset(done)
                            body.byteStream().use { input ->
                                while (true) {
                                    ensureActive()
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    raf.write(buf, 0, r)
                                    done += r
                                    failures = 0
                                    urlRefreshes = 0
                                    val now = System.nanoTime()
                                    if (now - lastEmit >= progressIntervalNanos) {
                                        lastEmit = now
                                        val rate = meter.update(done)
                                        onProgress(DownloadProgress(DownloadStage.DOWNLOADING, done, total, rate, meter.etaSeconds(done, total)))
                                    }
                                }
                            }
                            if (total > 0 && done < total) throw IOException("connection closed at $done/$total")
                            completed = true
                        }
                    }
                } catch (e: IOException) {
                    failures++
                    if (failures > maxRetries) {
                        throw AnvilException(
                            "Download keeps failing — check your internet connection. Progress is saved; tap Resume to continue.",
                            technical = "giving up after $failures failures: ${e.message}", cause = e,
                        )
                    }
                    val backoff = (baseBackoffMs shl (failures - 1).coerceAtMost(5)).coerceAtMost(60_000)
                    DebugLog.w(TAG, "network error (#$failures), retrying in ${backoff}ms", e)
                    val doneNow = if (part.exists()) part.length() else 0L
                    onProgress(DownloadProgress(DownloadStage.WAITING_FOR_NETWORK, doneNow, meta?.total ?: -1, retryInSeconds = (backoff / 1000).toInt()))
                    delay(backoff)
                }
                // Loop: either completed (checked at top) or resume from the new part length.
                val m = meta
                if (m != null && m.total > 0 && part.length() == m.total) break
                if (completed) break // unknown length, server closed cleanly
            }

            if (req.expectedSha256 != null && !verify(part, req.expectedSha256, onProgress)) {
                part.delete(); metaFile.delete()
                throw HashMismatchException(req.expectedSha256, "mismatch")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed: $part -> $dest")
            metaFile.delete()
            onProgress(DownloadProgress(DownloadStage.DONE, dest.length(), dest.length()))
            dest
        }

    /** Streams the file through SHA-256 with progress. */
    suspend fun verify(file: File, expected: String, onProgress: (DownloadProgress) -> Unit = {}): Boolean {
        val total = file.length()
        val digest = sha256()
        val buf = ByteArray(BUFFER)
        var done = 0L
        var lastEmit = 0L
        val meter = TransferMeter().also { it.reset() }
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
                done += r
                val now = System.nanoTime()
                if (now - lastEmit >= progressIntervalNanos) {
                    lastEmit = now
                    val rate = meter.update(done)
                    onProgress(DownloadProgress(DownloadStage.VERIFYING, done, total, rate, meter.etaSeconds(done, total)))
                }
            }
        }
        val actual = digest.digest().toHex()
        DebugLog.i(TAG, "sha256 ${file.name}: $actual (expected $expected)")
        return hexDigestEquals(actual, expected)
    }

    private fun readMeta(metaFile: File, part: File): PartMeta? {
        if (!metaFile.exists() || !part.exists()) { part.delete(); metaFile.delete(); return null }
        return runCatching { json.decodeFromString(PartMeta.serializer(), metaFile.readText()) }.getOrNull()
    }

    private fun writeMeta(metaFile: File, meta: PartMeta) {
        val tmp = File(metaFile.path + ".tmp")
        tmp.writeText(json.encodeToString(PartMeta.serializer(), meta))
        tmp.renameTo(metaFile)
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    companion object {
        private const val TAG = "Downloader"
        private const val BUFFER = 256 * 1024
        private const val MAX_URL_REFRESHES = 2

        /** "bytes 100-199/1000" -> 1000 */
        fun parseContentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
    }
}
