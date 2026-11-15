package dev.ruchin.anvil.catalog.linux

import dev.ruchin.anvil.common.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * [probeBytes] of the ISO) in parallel. Mirrors that don't answer with 206 Partial Content within
 * [headerTimeoutMs] are dropped (no resume = useless for a 3–6 GB file on a phone). Throughput is
 * measured over whatever arrives within [bodyBudgetMs], so on a slow connection, where parallel
 * probes share the bandwidth, a working mirror is still a working mirror, just a slower one.
 * Results are ordered by throughput, latency breaking ties, so callers can fall back down the list.
 */
class MirrorProber(
    client: OkHttpClient,
    private val probeBytes: Int = 128 * 1024,
    private val headerTimeoutMs: Long = 8_000,
    private val bodyBudgetMs: Long = 3_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = USER_AGENT,
) {
    private val client = client.newBuilder()
        .connectTimeout(headerTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(headerTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun probeAll(urls: List<String>): List<MirrorProbe> = coroutineScope {
        urls.map { url -> async(ioDispatcher) { probe(url) } }.awaitAll()
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
        val call = client.newCall(req)
        val resp = try {
            call.execute()
        } catch (e: Exception) {
            return MirrorProbe(url, false, error = e.message ?: e.javaClass.simpleName)
        }
        return resp.use {
            val headersAt = System.nanoTime()
            if (resp.code != 206) return MirrorProbe(url, false, error = "HTTP ${resp.code}")
            val deadline = headersAt + bodyBudgetMs * 1_000_000
            var read = 0L
            val buf = ByteArray(16 * 1024)
            try {
                resp.body.byteStream().use { input ->
                    while (read < probeBytes && System.nanoTime() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                    }
                }
            } catch (e: Exception) {
                // A slow or reset body after a good 206: keep what was measured.
                if (read == 0L) return MirrorProbe(url, false, error = "no data: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                call.cancel()
            }
            if (read == 0L) return MirrorProbe(url, false, error = "no data")
            val bodySeconds = ((System.nanoTime() - headersAt).coerceAtLeast(1_000_000)) / 1e9
            MirrorProbe(
                url, ok = true,
                latencyMs = (headersAt - start) / 1_000_000,
                bytesPerSecond = read / bodySeconds,
                totalBytes = resp.header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull(),
            )
        }
    }

    companion object {
        private const val TAG = "MirrorProber"
        const val USER_AGENT = "Anvil (Android USB writer; +https://github.com/Ruchin-Audichya/anvil-usb)"
    }
}
