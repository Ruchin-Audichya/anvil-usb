package dev.ruchin.anvil.catalog.linux

import dev.ruchin.anvil.common.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** How one mirror did in a probe. */
data class MirrorProbe(
    val url: String,
    /** True when the mirror served the file with HTTP 206, so downloads from it can resume. */
    val ok: Boolean,
    val latencyMs: Long? = null,
    val bytesPerSecond: Double = 0.0,
    /** Full file size from Content-Range, if the mirror sent it. */
    val totalBytes: Long? = null,
    val error: String? = null,
) {
    override fun toString() = if (ok) "%s: %d ms, %.1f MB/s".format(url, latencyMs, bytesPerSecond / 1e6) else "$url: FAILED ($error)"
}

/**
 * Picks the fastest mirror for a file: every candidate gets a small ranged GET (the first
 * [probeBytes] of the ISO) in parallel. Mirrors that don't answer with 206 Partial Content are
 * dropped (no resume = useless for a 3–6 GB file on a phone). The rest are ordered by throughput,
 * with latency as the tie-breaker, so the caller can fall back down the list on failure.
 */
class MirrorProber(
    client: OkHttpClient,
    private val probeBytes: Int = 256 * 1024,
    private val timeoutMs: Long = 8_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = USER_AGENT,
) {
    private val client = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    suspend fun probeAll(urls: List<String>): List<MirrorProbe> = coroutineScope {
        urls.map { url -> async(ioDispatcher) { withTimeoutOrNull(timeoutMs + 500) { probe(url) } ?: MirrorProbe(url, false, error = "timeout") } }
            .awaitAll()
    }

    /** Working mirrors, fastest first. Empty if none work. */
    suspend fun rank(urls: List<String>): List<MirrorProbe> {
        val results = probeAll(urls)
        results.forEach { DebugLog.i(TAG, it.toString()) }
        return results.filter { it.ok }.sortedWith(compareByDescending<MirrorProbe> { it.bytesPerSecond }.thenBy { it.latencyMs })
    }

    private fun probe(url: String): MirrorProbe {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=0-${probeBytes - 1}")
            .header("User-Agent", userAgent)
            .build()
        val start = System.nanoTime()
        return try {
            client.newCall(req).execute().use { resp ->
                val headersAt = System.nanoTime()
                if (resp.code != 206) return MirrorProbe(url, false, error = "HTTP ${resp.code}")
                var read = 0L
                val buf = ByteArray(32 * 1024)
                resp.body.byteStream().use { input ->
                    while (read < probeBytes) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                    }
                }
                val end = System.nanoTime()
                val bodySeconds = ((end - headersAt).coerceAtLeast(1_000_000)) / 1e9
                MirrorProbe(
                    url, ok = true,
                    latencyMs = (headersAt - start) / 1_000_000,
                    bytesPerSecond = read / bodySeconds,
                    totalBytes = resp.header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull(),
                )
            }
        } catch (e: Exception) {
            MirrorProbe(url, false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "MirrorProber"
        const val USER_AGENT = "Anvil (Android USB writer; +https://github.com/Ruchin-Audichya/anvil-usb)"
    }
}
