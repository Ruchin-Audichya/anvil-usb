package dev.ruchin.anvil.catalog.windows

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.UUID

/** Windows editions offered by Microsoft's public Software Download pages. */
enum class WindowsProduct(val pageSlug: String, val displayName: String, val arch: String) {
    WINDOWS_11_X64("windows11", "Windows 11", "x64"),
    WINDOWS_11_ARM64("windows11arm64", "Windows 11 (Arm64)", "arm64"),
    WINDOWS_10_X64("windows10ISO", "Windows 10 22H2", "x64"),
}

data class WindowsLanguage(
    val skuId: String,
    /** English name used by the API and the hash table, e.g. "English (United Kingdom)". */
    val language: String,
    val localizedLanguage: String,
    val productDisplayName: String,
)

/** A resolved Microsoft download session for one product: languages + published hashes. */
data class WindowsCatalogSession(
    val product: WindowsProduct,
    val productEditionId: String,
    val productEditionName: String,
    val sessionId: String,
    val languages: List<WindowsLanguage>,
    /** Normalized language key → SHA-256 published on the download page. */
    val publishedHashes: Map<String, String>,
) {
    fun hashFor(language: String): String? = publishedHashes[MicrosoftPageParser.languageKey(language)]

    fun pickLanguage(preferred: String?): WindowsLanguage {
        val wanted = preferred ?: "English"
        return languages.firstOrNull { it.language.equals(wanted, true) }
            ?: languages.firstOrNull { it.language.startsWith(wanted, true) }
            ?: languages.firstOrNull { it.language == "English" }
            ?: languages.first()
    }
}

data class WindowsDownloadLink(
    val product: WindowsProduct,
    val language: String,
    val productDisplayName: String,
    val url: String,
    val fileName: String,
    val expiresAt: Instant?,
    /** Official SHA-256 from Microsoft's "Verify your download" table (null if not published). */
    val sha256: String?,
)

class MicrosoftDownloadException(userMessage: String, technical: String) : AnvilException(userMessage, technical)

/**
 * Fetches fresh, official Windows ISO links from Microsoft's own servers — the
 * same calls the www.microsoft.com/software-download page makes in a browser
 * (and that the open-source Fido script automates). We never host or mirror ISOs.
 *
 * Flow (verified against the live site, Nov 2026):
 *  1. GET  /<locale>/software-download/<page>      → product edition id (`<option value>`) + SHA-256 table
 *  2. new session id (UUID v4)
 *  3. GET  ov-df.microsoft.com/mdt.js?…&session_id → returns a beacon URL; GET it to whitelist the session
 *  4. GET  software-download-connector/api/getskuinformationbyproductedition → languages (SKU ids)
 *  5. GET  software-download-connector/api/GetProductDownloadLinksBySku (with Referer) → signed ISO URL (valid 24 h)
 */
class MicrosoftIsoResolver(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://www.microsoft.com",
    private val ovdfScriptUrl: String = DEFAULT_OVDF_SCRIPT,
    private val urlLocale: String = "en-us",
    private val apiLocale: String = "en-US",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Steps 1–4: everything needed to show a language picker. */
    suspend fun openSession(product: WindowsProduct): WindowsCatalogSession = withContext(ioDispatcher) {
        val pageUrl = pageUrl(product)
        val html = get(pageUrl, referer = null)
        val edition = MicrosoftPageParser.parseProductEditions(html).firstOrNull()
            ?: throw MicrosoftDownloadException(
                "Microsoft's download page changed and Anvil couldn't read it. Please update the app.",
                "no product edition <option> on $pageUrl",
            )
        val hashes = MicrosoftPageParser.parseHashTable(html)
        val sessionId = UUID.randomUUID().toString()
        val ovdf = MicrosoftPageParser.parseOvdfRef(html) ?: ovdfScriptUrl
        registerSession(ovdf, sessionId)

        val skuJson = get(
            api("getskuinformationbyproductedition", sessionId, "ProductEditionId" to edition.id, "SKU" to "undefined"),
            referer = pageUrl,
        )
        val skus = json.decodeFromString(SkuResponse.serializer(), skuJson)
        throwIfErrors(skus.errors())
        if (skus.skus.isEmpty()) throw MicrosoftDownloadException("Microsoft returned no languages for ${product.displayName}.", "empty Skus")
        DebugLog.i(TAG, "${product.name}: edition ${edition.id} '${edition.name}', ${skus.skus.size} languages, ${hashes.size} hashes")
        WindowsCatalogSession(
            product = product,
            productEditionId = edition.id,
            productEditionName = edition.name,
            sessionId = sessionId,
            languages = skus.skus.map { WindowsLanguage(it.id, it.language, it.localizedLanguage ?: it.language, it.productDisplayName ?: product.displayName) },
            publishedHashes = hashes,
        )
    }

    /** Step 5: the actual signed ISO link for a language. Valid ~24 h. */
    suspend fun resolve(session: WindowsCatalogSession, language: WindowsLanguage): WindowsDownloadLink = withContext(ioDispatcher) {
        val body = get(
            api("GetProductDownloadLinksBySku", session.sessionId, "ProductEditionId" to "undefined", "SKU" to language.skuId),
            referer = pageUrl(session.product),
        )
        val resp = json.decodeFromString(LinksResponse.serializer(), body)
        throwIfErrors(resp.errors())
        val option = pickOption(resp.options, session.product)
            ?: throw MicrosoftDownloadException("Microsoft didn't return a download link.", "no ProductDownloadOptions: $body")
        val url = option.uri.toHttpUrl()
        WindowsDownloadLink(
            product = session.product,
            language = language.language,
            productDisplayName = option.productDisplayName ?: language.productDisplayName,
            url = option.uri,
            fileName = url.pathSegments.lastOrNull()?.takeIf { it.endsWith(".iso", true) } ?: "Windows.iso",
            expiresAt = resp.expiration?.let { runCatching { Instant.parse(it) }.getOrNull() },
            sha256 = session.hashFor(language.language),
        )
    }

    /** One call: latest ISO link for [product] in [preferredLanguage] (English names, e.g. "English", "Hindi" falls back to English). */
    suspend fun resolveLatest(product: WindowsProduct, preferredLanguage: String? = "English"): WindowsDownloadLink {
        val session = openSession(product)
        return resolve(session, session.pickLanguage(preferredLanguage))
    }

    private fun pickOption(options: List<DownloadOption>, product: WindowsProduct): DownloadOption? {
        val isos = options.filter { it.uri.isNotBlank() }
        return when (product.arch) {
            "arm64" -> isos.firstOrNull { it.uri.contains("arm64", true) || it.uri.contains("A64", false) } ?: isos.firstOrNull()
            else -> isos.firstOrNull { it.downloadType == DOWNLOAD_TYPE_X64 } ?: isos.firstOrNull { it.uri.contains("x64", true) } ?: isos.firstOrNull()
        }
    }

    private fun registerSession(scriptPrefix: String, sessionId: String) {
        // Microsoft's anti-abuse service must see the session before the API will hand out links.
        runCatching {
            val script = get(scriptPrefix + sessionId, referer = baseUrl + "/")
            val beacon = MicrosoftPageParser.parseOvdfBeacon(script)
            if (beacon == null) {
                DebugLog.w(TAG, "ov-df script had no beacon url")
            } else {
                get(beacon.url + "&mdt=" + clock() + (beacon.rticks?.let { "&rticks=$it" } ?: ""), referer = baseUrl + "/")
            }
        }.onFailure { DebugLog.w(TAG, "session registration failed (continuing)", it) }
    }

    private fun throwIfErrors(errors: List<ApiError>) {
        if (errors.isEmpty()) return
        val e = errors.first()
        val technical = "Microsoft API error type=${e.type} key=${e.key} value=${e.value}"
        throw if (e.type == ERROR_TYPE_SENTINEL || e.value?.contains("715-123130") == true) {
            MicrosoftDownloadException(
                "Microsoft temporarily blocked this download request (error 715-123130). This usually happens on VPNs, " +
                    "proxies or shared networks. Turn off any VPN, switch between Wi-Fi and mobile data, and try again — " +
                    "or wait a few hours.",
                technical,
            )
        } else {
            MicrosoftDownloadException("Microsoft's download service returned an error: ${e.value ?: e.key}", technical)
        }
    }

    private fun pageUrl(product: WindowsProduct) = "$baseUrl/$urlLocale/software-download/${product.pageSlug}"

    private fun api(action: String, sessionId: String, vararg params: Pair<String, String>): String {
        val b = "$baseUrl/software-download-connector/api/$action".toHttpUrl().newBuilder()
            .addQueryParameter("profile", PROFILE)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        b.addQueryParameter("friendlyFileName", "undefined")
        b.addQueryParameter("Locale", apiLocale)
        b.addQueryParameter("sessionID", sessionId)
        return b.build().toString()
    }

    private fun get(url: String, referer: String?): String {
        val req = Request.Builder().url(url)
            // A non-Windows UA matters: on Windows, the Windows 10 page serves the Media Creation Tool instead of ISOs.
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "$apiLocale,en;q=0.8")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw MicrosoftDownloadException(
                    "Couldn't reach Microsoft's download servers (HTTP ${resp.code}). Check your connection and try again.",
                    "HTTP ${resp.code} for ${url.substringBefore('?')}",
                )
            }
            return resp.body.string()
        }
    }

    // --- API models ---------------------------------------------------------

    @Serializable
    internal data class ApiError(
        @SerialName("Key") val key: String? = null,
        @SerialName("Value") val value: String? = null,
        @SerialName("Type") val type: Int? = null,
    )

    @Serializable
    internal data class ValidationContainer(@SerialName("Errors") val errors: List<ApiError> = emptyList())

    @Serializable
    internal data class Sku(
        @SerialName("Id") val id: String,
        @SerialName("Language") val language: String,
        @SerialName("LocalizedLanguage") val localizedLanguage: String? = null,
        @SerialName("ProductDisplayName") val productDisplayName: String? = null,
    )

    @Serializable
    internal data class SkuResponse(
        @SerialName("Skus") val skus: List<Sku> = emptyList(),
        @SerialName("ValidationContainer") val validation: ValidationContainer? = null,
        @SerialName("Errors") val topErrors: List<ApiError>? = null,
    ) { fun errors() = (validation?.errors.orEmpty() + topErrors.orEmpty()) }

    @Serializable
    internal data class DownloadOption(
        @SerialName("Uri") val uri: String = "",
        @SerialName("DownloadType") val downloadType: Int? = null,
        @SerialName("Language") val language: String? = null,
        @SerialName("ProductDisplayName") val productDisplayName: String? = null,
    )

    @Serializable
    internal data class LinksResponse(
        @SerialName("ProductDownloadOptions") val options: List<DownloadOption> = emptyList(),
        @SerialName("DownloadExpirationDatetime") val expiration: String? = null,
        @SerialName("ValidationContainer") val validation: ValidationContainer? = null,
        @SerialName("Errors") val topErrors: List<ApiError>? = null,
    ) { fun errors() = (validation?.errors.orEmpty() + topErrors.orEmpty()) }

    companion object {
        private const val TAG = "MsResolver"
        const val PROFILE = "606624d44113"
        const val DEFAULT_OVDF_SCRIPT =
            "https://ov-df.microsoft.com/mdt.js?instanceId=560dc9f3-1aa5-4a2f-b63c-9e18f8d0e175&pageId=si&session_id="
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private const val DOWNLOAD_TYPE_X64 = 1
        private const val ERROR_TYPE_SENTINEL = 9
    }
}
