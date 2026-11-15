package dev.ruchin.anvil.catalog.linux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/** A fake distro mirror: serves the real Ubuntu 26.04 / Fedora 45 checksum files and a stand-in ISO. */
private class FakeMirror(var slowMs: Long = 0) : Dispatcher() {
    val files = mutableMapOf(
        "/26.04/SHA256SUMS" to fixture("ubuntu-26.04-SHA256SUMS"),
        "/26.04/SHA256SUMS.gpg" to fixture("ubuntu-26.04-SHA256SUMS.gpg"),
        "/45/iso/Fedora-Workstation-45-1.4-x86_64-CHECKSUM" to fixture("fedora-45-CHECKSUM"),
    )
    val isos = mutableSetOf("/26.04/ubuntu-26.04.1-desktop-amd64.iso", "/45/iso/Fedora-Workstation-Live-45-1.4.x86_64.iso")
    var down = false
    val paths = mutableListOf<String>()
    private val iso = ByteArray(512 * 1024) { it.toByte() }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        paths += path
        if (down) return MockResponse(code = 503)
        files[path]?.let { return MockResponse.Builder().body(Buffer().write(it)).build() }
        if (path in isos) {
            val range = request.headers["Range"] ?: return MockResponse.Builder().body(Buffer().write(iso)).build()
            val (a, b) = range.removePrefix("bytes=").split('-').map { it.toInt() }
            val end = minOf(b, iso.size - 1)
            return MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $a-$end/6482067456")
                .body(Buffer().write(iso.copyOfRange(a, end + 1)))
                .apply { if (slowMs > 0) bodyDelay(slowMs, TimeUnit.MILLISECONDS) }
                .build()
        }
        return MockResponse(code = 404)
    }
}

class LinuxIsoResolverTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer
    private val ma = FakeMirror()
    private val mb = FakeMirror()

    @BeforeEach fun setUp() {
        a = MockWebServer().apply { dispatcher = ma; start() }
        b = MockWebServer().apply { dispatcher = mb; start() }
    }

    @AfterEach fun tearDown() { a.close(); b.close() }

    private fun root(s: MockWebServer) = s.url("/").toString().trimEnd('/')

    private fun ubuntu(vararg mirrors: String) = LinuxEntry(
        id = "ubuntu-lts-desktop", distro = "ubuntu", distroName = "Ubuntu", edition = "Desktop LTS", version = "26.04 LTS",
        mirrors = mirrors.toList(), isoPath = "26.04/ubuntu-{version}-desktop-amd64.iso",
        checksumUrl = "26.04/SHA256SUMS", signatureUrl = "26.04/SHA256SUMS.gpg", keyId = "ubuntu-cdimage",
    )

    private val resolver = LinuxIsoResolver(OkHttpClient(), prober = MirrorProber(OkHttpClient(), probeBytes = 64 * 1024, timeoutMs = 3_000))

    @Test fun `resolves the newest signed ISO and ranks the faster mirror first`() = runBlocking {
        ma.slowMs = 600
        val r = resolver.resolve(ubuntu(root(a), root(b)))
        assertThat(r.fileName).isEqualTo("ubuntu-26.04.1-desktop-amd64.iso")
        assertThat(r.version).isEqualTo("26.04.1")
        assertThat(r.sha256).isEqualTo("601e30fbf5d97759367c632e2c33630665039b7e2158fd068403da3ccf1bda1f")
        assertThat(r.sizeBytes).isEqualTo(6482067456)
        assertThat(r.signer.id).isEqualTo("ubuntu-cdimage")
        assertThat(r.mirrorUrls).containsExactly(
            root(b) + "/26.04/ubuntu-26.04.1-desktop-amd64.iso",
            root(a) + "/26.04/ubuntu-26.04.1-desktop-amd64.iso",
        ).inOrder()
        assertThat(r.title).isEqualTo("Ubuntu 26.04.1 Desktop LTS")
    }

    @Test fun `falls back to the next mirror for the checksum list`(): Unit = runBlocking {
        ma.down = true
        val r = resolver.resolve(ubuntu(root(a), root(b)))
        assertThat(r.mirrorUrls).containsExactly(root(b) + "/26.04/ubuntu-26.04.1-desktop-amd64.iso")
        assertThat(ma.paths).contains("/26.04/SHA256SUMS")
    }

    @Test fun `bad signature stops before any ISO request`() {
        ma.files["/26.04/SHA256SUMS"] = String(fixture("ubuntu-26.04-SHA256SUMS")).replace("601e30fb", "deadbeef").toByteArray()
        assertThrows<SignatureVerificationException> { runBlocking { resolver.resolve(ubuntu(root(a))) } }
        assertThat(ma.paths.none { it.endsWith(".iso") }).isTrue()
    }

    @Test fun `missing signature stops before any ISO request`() {
        ma.files.remove("/26.04/SHA256SUMS.gpg")
        assertThrows<LinuxCatalogException> { runBlocking { resolver.resolve(ubuntu(root(a))) } }
        assertThat(ma.paths.none { it.endsWith(".iso") }).isTrue()
    }

    @Test fun `no working mirror is a clear error`() {
        ma.isos.clear(); mb.isos.clear()
        val e = assertThrows<LinuxCatalogException> { runBlocking { resolver.resolve(ubuntu(root(a), root(b))) } }
        assertThat(e.userMessage).contains("mirrors")
    }

    @Test fun `Fedora clear-signed CHECKSUM resolves`() = runBlocking {
        val fedora = LinuxEntry(
            id = "fedora-workstation", distro = "fedora", distroName = "Fedora", edition = "Workstation", version = "45",
            mirrors = listOf(root(a)), isoPath = "45/iso/Fedora-Workstation-Live-{version}.x86_64.iso",
            checksumUrl = "45/iso/Fedora-Workstation-45-1.4-x86_64-CHECKSUM", keyId = "fedora-45",
        )
        val r = resolver.resolve(fedora)
        assertThat(r.fileName).isEqualTo("Fedora-Workstation-Live-45-1.4.x86_64.iso")
        assertThat(r.sha256).isEqualTo("68338c6bfe4f0d64151f2e47efe9f44a8855ad05b460e56b844fb3fa2c9a3beb")
    }

    @Test fun `unpinned key is refused`() {
        assertThrows<LinuxCatalogException> { runBlocking { resolver.resolve(ubuntu(root(a)).copy(keyId = "nope")) } }
    }
}

class LinuxManifestSourceTest {
    private lateinit var server: MockWebServer
    private var remoteBody = ""
    private var remoteCode = 200
    private val bundledText = java.io.File(System.getProperty("anvil.linuxManifest")).readText()

    @BeforeEach fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse(code = remoteCode, body = remoteBody)
            }
            start()
        }
    }

    @AfterEach fun tearDown() = server.close()

    private fun source() = LinuxManifestSource(OkHttpClient(), bundled = { bundledText }, remoteUrl = server.url("/m.json").toString())

    @Test fun `uses a valid remote manifest`() = runBlocking {
        remoteBody = bundledText.replace("\"updated\": \"2026-11-15\"", "\"updated\": \"2099-01-01\"")
        val m = source().load()
        assertThat(m.fromRemote).isTrue()
        assertThat(m.manifest.updated).isEqualTo("2099-01-01")
    }

    @Test fun `falls back to the bundled manifest when the remote is down, broken or unverifiable`() = runBlocking {
        remoteCode = 500
        assertThat(source().load().fromRemote).isFalse()
        remoteCode = 200; remoteBody = "{not json"
        assertThat(source().load().fromRemote).isFalse()
        remoteBody = bundledText.replace(Regex("\"keyId\": \"[a-z0-9-]+\""), "\"keyId\": \"attacker\"")
        val m = source().load()
        assertThat(m.fromRemote).isFalse()
        assertThat(m.manifest.entries).isNotEmpty()
        remoteBody = bundledText.replace("\"schema\": 1", "\"schema\": 99")
        assertThat(source().load().fromRemote).isFalse()
    }
}
