package dev.ruchin.anvil.download

import dev.ruchin.anvil.common.sha256
import dev.ruchin.anvil.common.toHex
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

class ResumableDownloaderTest {
    private lateinit var server: MockWebServer
    private val payload = Random(7).nextBytes(1_000_000)
    private val payloadSha = sha256().digest(payload).toHex()
    @TempDir lateinit var tmp: File
    private val downloader = ResumableDownloader(OkHttpClient(), baseBackoffMs = 1, maxRetries = 3)

    /** Serves [payload] honoring Range/If-Range; can cut connections and expire URLs. */
    private inner class RangeDispatcher : Dispatcher() {
        var cutConnection = false
        val expiredPaths = mutableSetOf<String>()
        val requests = mutableListOf<RecordedRequest>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            if (request.url.encodedPath in expiredPaths) return MockResponse(code = 403)
            val range = request.headers["Range"]
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val etag = "\"v1\""
            val ifRange = request.headers["If-Range"]
            val honorRange = range != null && (ifRange == null || ifRange == etag)
            val from = if (honorRange) start else 0
            val slice = payload.copyOfRange(from, payload.size)
            val builder = MockResponse.Builder()
                .code(if (honorRange) 206 else 200)
                .headers(Headers.headersOf("ETag", etag))
                .body(Buffer().write(slice))
            if (honorRange) builder.addHeader("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
            if (cutConnection) {
                cutConnection = false
                // Sends roughly half the body, then kills the socket: a dropped mobile connection.
                builder.onResponseBody(SocketEffect.CloseSocket())
            }
            return builder.build()
        }
    }

    private lateinit var dispatcher: RangeDispatcher

    @BeforeEach fun setUp() {
        dispatcher = RangeDispatcher()
        server = MockWebServer().apply { this.dispatcher = this@ResumableDownloaderTest.dispatcher; start() }
    }

    @AfterEach fun tearDown() = server.close()

    @Test fun `downloads and verifies sha256`() = runBlocking {
        val dest = File(tmp, "a.iso")
        downloader.download(DownloadRequest({ server.url("/a.iso").toString() }, dest, payloadSha))
        assertThat(dest.readBytes()).isEqualTo(payload)
        assertThat(File(dest.path + ".part").exists()).isFalse()
    }

    @Test fun `resumes after connection drop using Range`() = runBlocking {
        dispatcher.cutConnection = true
        val dest = File(tmp, "b.iso")
        val stages = mutableListOf<DownloadStage>()
        downloader.download(DownloadRequest({ server.url("/b.iso").toString() }, dest, payloadSha)) { stages += it.stage }
        assertThat(dest.readBytes()).isEqualTo(payload)
        val second = dispatcher.requests[1]
        val resumedFrom = second.headers["Range"]!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertThat(resumedFrom).isGreaterThan(0L)
        assertThat(resumedFrom).isLessThan(payload.size.toLong())
        assertThat(second.headers["If-Range"]).isEqualTo("\"v1\"")
        assertThat(stages).contains(DownloadStage.WAITING_FOR_NETWORK)
    }

    @Test fun `refreshes expired signed url and keeps going`() = runBlocking {
        dispatcher.expiredPaths += "/old.iso"
        val dest = File(tmp, "c.iso")
        val asked = mutableListOf<Boolean>()
        downloader.download(DownloadRequest({ refresh ->
            asked += refresh
            server.url(if (refresh) "/new.iso" else "/old.iso").toString()
        }, dest, payloadSha))
        assertThat(asked).containsExactly(false, true).inOrder()
        assertThat(dest.readBytes()).isEqualTo(payload)
    }

    @Test fun `hash mismatch deletes the file`() {
        val dest = File(tmp, "d.iso")
        assertThrows<HashMismatchException> {
            runBlocking { downloader.download(DownloadRequest({ server.url("/d.iso").toString() }, dest, "00".repeat(32))) }
        }
        assertThat(dest.exists()).isFalse()
        assertThat(File(dest.path + ".part").exists()).isFalse()
    }

    @Test fun `content-range parsing`() {
        assertThat(ResumableDownloader.parseContentRangeTotal("bytes 0-1023/9047330816")).isEqualTo(9047330816L)
        assertThat(ResumableDownloader.parseContentRangeTotal(null)).isNull()
    }
}
