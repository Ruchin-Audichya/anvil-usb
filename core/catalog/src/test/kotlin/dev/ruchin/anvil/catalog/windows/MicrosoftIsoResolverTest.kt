package dev.ruchin.anvil.catalog.windows

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MicrosoftIsoResolverTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private var linksBody = ""

    private val pageHtml = """
        <select id="product-edition"><option value="" selected>Select Download</option>
        <option value="3813">Windows 11 (multi-edition ISO for x64 devices)</option></select>
        <table><tr><th>Country Locale</th><th>Hash Code</th></tr>
        <tr><td>English 64-bit</td><td>${"AB".repeat(32)}</td></tr>
        <tr><td>English International 64-bit</td><td>${"CD".repeat(32)}</td></tr>
        <tr><td>Chinese Simplified  64-bit</td><td>${"EF".repeat(32)}</td></tr>
        <tr><td>English 32-bit</td><td>${"00".repeat(32)}</td></tr></table>
    """.trimIndent()

    private val skuJson = """{"ValidationContainer":{"Errors":[]},"Skus":[
        {"Id":"27122","Language":"Arabic","LocalizedLanguage":"Arabic","ProductDisplayName":"Windows 11 Client - Build 26300.9457"},
        {"Id":"27150","Language":"English","LocalizedLanguage":"English (United States)","ProductDisplayName":"Windows 11 Client - Build 26300.9457"},
        {"Id":"27151","Language":"English (United Kingdom)","LocalizedLanguage":"English (United Kingdom)","ProductDisplayName":"Windows 11 Client - Build 26300.9457"}]}"""

    @BeforeEach fun setUp() {
        linksBody = """{"ProductDownloadOptions":[{"Name":"x","Uri":"https://software.download.prss.microsoft.com/dbazure/Win11_x64_en-us.iso?t=abc&e=1","ProductDisplayName":"Windows 11 Client - Build 26300.9457","Language":"English","DownloadType":1}],
            "ValidationContainer":{"Errors":[]},"DownloadExpirationDatetime":"2026-11-06T00:56:15.5461398Z"}"""
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/software-download/windows11") -> pageHtml
                    path == "/mdt.js" -> """window.dfp={url:"${server.url("/beacon")}?session_id=${request.url.queryParameter("session_id")}&w=ABC"};src+="&rticks="+1793840132942;"""
                    path == "/beacon" -> "ok"
                    path.endsWith("/getskuinformationbyproductedition") -> skuJson
                    path.endsWith("/GetProductDownloadLinksBySku") -> linksBody
                    else -> return MockResponse(code = 404)
                }
                return MockResponse(body = body)
            }
        }
        server.start()
    }

    @AfterEach fun tearDown() = server.close()

    private fun resolver() = MicrosoftIsoResolver(
        OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        ovdfScriptUrl = server.url("/mdt.js").toString() + "?instanceId=x&pageId=si&session_id=",
        clock = { 1234L },
    )

    @Test fun `resolves latest windows 11 english link with official hash`() = runBlocking {
        val link = resolver().resolveLatest(WindowsProduct.WINDOWS_11_X64, "English")
        assertThat(link.url).startsWith("https://software.download.prss.microsoft.com/")
        assertThat(link.fileName).isEqualTo("Win11_x64_en-us.iso")
        assertThat(link.sha256).isEqualTo("ab".repeat(32))
        assertThat(link.productDisplayName).isEqualTo("Windows 11 Client - Build 26300.9457")
        assertThat(link.expiresAt.toString()).startsWith("2026-11-06T00:56:15")

        val sku = seen.first { it.url.encodedPath.endsWith("getskuinformationbyproductedition") }
        assertThat(sku.url.queryParameter("ProductEditionId")).isEqualTo("3813")
        assertThat(sku.url.queryParameter("profile")).isEqualTo(MicrosoftIsoResolver.PROFILE)
        val sessionId = sku.url.queryParameter("sessionID")!!
        // session was registered with ov-df before the API call, using the same session id
        val beaconIdx = seen.indexOfFirst { it.url.encodedPath == "/beacon" }
        assertThat(beaconIdx).isLessThan(seen.indexOf(sku))
        assertThat(seen[beaconIdx].url.queryParameter("session_id")).isEqualTo(sessionId)
        assertThat(seen[beaconIdx].url.queryParameter("rticks")).isEqualTo("1793840132942")
        val links = seen.first { it.url.encodedPath.endsWith("GetProductDownloadLinksBySku") }
        assertThat(links.url.queryParameter("SKU")).isEqualTo("27150")
        assertThat(links.url.queryParameter("sessionID")).isEqualTo(sessionId)
        assertThat(links.headers["Referer"]).endsWith("/en-us/software-download/windows11")
    }

    @Test fun `uk english maps to english international hash`() = runBlocking {
        val r = resolver()
        val session = r.openSession(WindowsProduct.WINDOWS_11_X64)
        val uk = session.languages.first { it.language == "English (United Kingdom)" }
        assertThat(session.hashFor(uk.language)).isEqualTo("cd".repeat(32))
        assertThat(session.pickLanguage("Hindi").language).isEqualTo("English")
    }

    @Test fun `sentinel ban error becomes a helpful message`() {
        linksBody = """{"ValidationContainer":{"Errors":[{"Key":"ErrorSettings.SentinelReject","Value":"Sentinel marked this request as rejected. 715-123130","Type":9}]}}"""
        val e = assertThrows<MicrosoftDownloadException> {
            runBlocking { resolver().resolveLatest(WindowsProduct.WINDOWS_11_X64) }
        }
        assertThat(e.userMessage).contains("VPN")
    }

    @Test fun `parser handles real-world label variants`() {
        assertThat(MicrosoftPageParser.languageKey("Chinese (Simplified)")).isEqualTo(MicrosoftPageParser.languageKey("Chinese Simplified  64-bit"))
        assertThat(MicrosoftPageParser.languageKey("Spanish (Mexico)")).isEqualTo(MicrosoftPageParser.languageKey("Spanish (Mexico) 64-bit"))
        assertThat(MicrosoftPageParser.parseHashTable(pageHtml)).doesNotContainKey("english 32")
        assertThat(MicrosoftPageParser.parseHashTable(pageHtml)["english"]).isEqualTo("ab".repeat(32))
    }
}
