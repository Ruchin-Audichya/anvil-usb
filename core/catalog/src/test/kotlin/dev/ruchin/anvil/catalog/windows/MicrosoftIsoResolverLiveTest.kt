package dev.ruchin.anvil.catalog.windows

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Hits Microsoft for real. Run with: ./gradlew :core:catalog:liveTest */
@Tag("live")
class MicrosoftIsoResolverLiveTest {
    @Test fun `resolve real windows 11 link`() = runBlocking {
        assumeTrue(System.getProperty("anvil.live") == "true")
        val client = OkHttpClient()
        for (product in WindowsProduct.entries) {
            val link = MicrosoftIsoResolver(client).resolveLatest(product, "English")
            // Only print the URL without its signature.
            println("${product.name}: ${link.productDisplayName} | ${link.fileName} | sha256=${link.sha256} | expires=${link.expiresAt} | ${link.url.substringBefore('?')}")
            assertThat(link.url).startsWith("https://")
            assertThat(link.fileName).endsWith(".iso")
            assertThat(link.sha256).hasLength(64)
            // The signed URL must serve the ISO and support resume.
            val req = Request.Builder().url(link.url).header("Range", "bytes=0-0").build()
            client.newCall(req).execute().use { resp ->
                assertThat(resp.code).isEqualTo(206)
                println("  size: ${resp.header("Content-Range")}")
            }
        }
    }
}
