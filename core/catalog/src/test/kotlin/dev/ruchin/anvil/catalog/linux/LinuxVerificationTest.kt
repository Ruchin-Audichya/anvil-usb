package dev.ruchin.anvil.catalog.linux

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Fixtures under resources/linux are the real files published by each distro (Nov 2026). */
internal fun fixture(name: String): ByteArray =
    LinuxVerificationTest::class.java.getResourceAsStream("/linux/$name")!!.use { it.readBytes() }

class LinuxVerificationTest {
    private val keys = PinnedKeys.load()

    @Test fun `every pinned key loads and matches its fingerprint`() {
        assertThat(keys.ids).containsExactlyElementsIn(PinnedKeys.PINNED.keys)
        for (id in keys.ids) {
            val k = keys[id]!!
            assertThat(PinnedKeys.fingerprintHex(k.ring.publicKey.fingerprint)).isEqualTo(PinnedKeys.PINNED.getValue(id).first)
        }
    }

    @Test fun `a key file that does not contain the pinned fingerprint is rejected`() {
        val mint = PinnedKeys::class.java.getResourceAsStream("/dev/ruchin/anvil/catalog/linux/keys/linuxmint-iso.asc")!!
        assertThrows<IllegalStateException> {
            PinnedKeys.fromStream("ubuntu-cdimage", PinnedKeys.PINNED.getValue("ubuntu-cdimage").first, "x", mint)
        }
    }

    @Test fun `real detached signatures verify with the pinned keys`() {
        SignatureVerifier.verifyDetached(fixture("ubuntu-26.04-SHA256SUMS"), fixture("ubuntu-26.04-SHA256SUMS.gpg"), keys["ubuntu-cdimage"]!!)
        SignatureVerifier.verifyDetached(fixture("ubuntu-26.10-SHA256SUMS"), fixture("ubuntu-26.10-SHA256SUMS.gpg"), keys["ubuntu-cdimage"]!!)
        SignatureVerifier.verifyDetached(fixture("mint-22.3-sha256sum.txt"), fixture("mint-22.3-sha256sum.txt.gpg"), keys["linuxmint-iso"]!!)
        SignatureVerifier.verifyDetached(fixture("debian-netinst-SHA256SUMS"), fixture("debian-netinst-SHA256SUMS.sign"), keys["debian-cd"]!!)
        SignatureVerifier.verifyDetached(fixture("debian-live-SHA256SUMS"), fixture("debian-live-SHA256SUMS.sign"), keys["debian-cd"]!!)
    }

    @Test fun `real Fedora clear-signed CHECKSUM files verify and return only the signed text`() {
        val text45 = SignatureVerifier.verifyClearSigned(fixture("fedora-45-CHECKSUM"), keys["fedora-45"]!!)
        assertThat(text45).contains("SHA256 (Fedora-Workstation-Live-45-1.4.x86_64.iso) = 68338c6bfe4f0d64151f2e47efe9f44a8855ad05b460e56b844fb3fa2c9a3beb")
        assertThat(text45).doesNotContain("BEGIN PGP")
        SignatureVerifier.verifyClearSigned(fixture("fedora-44-CHECKSUM"), keys["fedora-44"]!!)
    }

    @Test fun `a changed hash breaks the detached signature`() {
        val sums = String(fixture("ubuntu-26.04-SHA256SUMS")).replace("601e30fb", "601e30fc").toByteArray()
        val e = assertThrows<SignatureVerificationException> {
            SignatureVerifier.verifyDetached(sums, fixture("ubuntu-26.04-SHA256SUMS.gpg"), keys["ubuntu-cdimage"]!!)
        }
        assertThat(e.userMessage).contains("Ubuntu CD Image Automatic Signing Key")
    }

    @Test fun `a changed hash breaks the clear-signed Fedora list`() {
        val doc = String(fixture("fedora-45-CHECKSUM")).replace("68338c6b", "68338c6c").toByteArray()
        assertThrows<SignatureVerificationException> { SignatureVerifier.verifyClearSigned(doc, keys["fedora-45"]!!) }
    }

    @Test fun `a line added outside the clear-signed text is not returned`() {
        val evil = "SHA256 (evil.iso) = " + "0".repeat(64) + "\n"
        val doc = (evil + String(fixture("fedora-45-CHECKSUM"))).toByteArray()
        // Text before the armor header isn't part of the signed message: it must never reach the parser.
        val signed = runCatching { SignatureVerifier.verifyClearSigned(doc, keys["fedora-45"]!!) }.getOrNull()
        if (signed != null) assertThat(signed).doesNotContain("evil.iso")
    }

    @Test fun `a signature by a different distro key is rejected`() {
        assertThrows<SignatureVerificationException> {
            SignatureVerifier.verifyDetached(fixture("ubuntu-26.04-SHA256SUMS"), fixture("ubuntu-26.04-SHA256SUMS.gpg"), keys["linuxmint-iso"]!!)
        }
        assertThrows<SignatureVerificationException> {
            SignatureVerifier.verifyClearSigned(fixture("fedora-45-CHECKSUM"), keys["fedora-44"]!!)
        }
    }

    @Test fun `garbage instead of a signature is rejected`() {
        assertThrows<SignatureVerificationException> {
            SignatureVerifier.verifyDetached(fixture("ubuntu-26.04-SHA256SUMS"), "<html>404</html>".toByteArray(), keys["ubuntu-cdimage"]!!)
        }
        assertThrows<SignatureVerificationException> {
            SignatureVerifier.verifyClearSigned(fixture("ubuntu-26.04-SHA256SUMS"), keys["ubuntu-cdimage"]!!)
        }
    }
}

class ChecksumsTest {
    @Test fun `parses GNU and BSD formats`() {
        val gnu = Checksums.parse(String(fixture("debian-netinst-SHA256SUMS")))
        assertThat(gnu["debian-13.7.0-amd64-netinst.iso"]).isEqualTo("a7ef94ac2fb9a7fec454552abd629b7cc9d5155c886165a45649f5ce6167e355")
        val star = Checksums.parse(String(fixture("mint-22.3-sha256sum.txt")))
        assertThat(star.keys).contains("linuxmint-22.3-cinnamon-64bit.iso")
        val bsd = Checksums.parse("# Fedora-x.iso: 1 bytes\nSHA256 (Fedora-x.iso) = ${"AB".repeat(32)}\nMD5 (Fedora-x.iso) = 00")
        assertThat(bsd).containsExactly("Fedora-x.iso", "ab".repeat(32))
    }

    @Test fun `version pattern picks the newest point release and skips betas and sibling images`() {
        val u2604 = Checksums.parse(String(fixture("ubuntu-26.04-SHA256SUMS")))
        assertThat(IsoPattern.match("ubuntu-{version}-desktop-amd64.iso", u2604))
            .isEqualTo(IsoMatch("ubuntu-26.04.1-desktop-amd64.iso", "26.04.1", "601e30fbf5d97759367c632e2c33630665039b7e2158fd068403da3ccf1bda1f"))
        val u2610 = Checksums.parse(String(fixture("ubuntu-26.10-SHA256SUMS")))
        assertThat(IsoPattern.match("ubuntu-{version}-desktop-amd64.iso", u2610)!!.fileName).isEqualTo("ubuntu-26.10-desktop-amd64.iso")
        val deb = Checksums.parse(String(fixture("debian-netinst-SHA256SUMS")))
        assertThat(IsoPattern.match("debian-{version}-amd64-netinst.iso", deb)!!.fileName).isEqualTo("debian-13.7.0-amd64-netinst.iso")
        val live = Checksums.parse(String(fixture("debian-live-SHA256SUMS")))
        assertThat(IsoPattern.match("debian-live-{version}-amd64-gnome.iso", live)!!.fileName).isEqualTo("debian-live-13.7.0-amd64-gnome.iso")
        val fed = Checksums.parse("SHA256 (Fedora-Workstation-Live-45-1.4.x86_64.iso) = ${"ab".repeat(32)}")
        assertThat(IsoPattern.match("Fedora-Workstation-Live-{version}.x86_64.iso", fed)!!.version).isEqualTo("45-1.4")
        assertThat(IsoPattern.match("ubuntu-{version}-desktop-arm64.iso", u2604)).isNull()
    }

    @Test fun `natural version order`() {
        assertThat(IsoPattern.compareVersions("26.04.1", "26.04")).isGreaterThan(0)
        assertThat(IsoPattern.compareVersions("13.10.0", "13.9.0")).isGreaterThan(0)
        assertThat(IsoPattern.compareVersions("45-1.4", "45-1.2")).isGreaterThan(0)
        assertThat(IsoPattern.compareVersions("22.3", "22.3")).isEqualTo(0)
    }
}

class BundledManifestTest {
    private val keys = PinnedKeys.load()
    private val manifest = LinuxManifest.parse(java.io.File(System.getProperty("anvil.linuxManifest")).readText())

    @Test fun `bundled manifest parses and every entry is verifiable`() {
        assertThat(manifest.schema).isEqualTo(LinuxManifest.SCHEMA)
        for (e in manifest.entries) assertThat(e.problems(keys)).isEmpty()
        assertThat(manifest.usable(keys).entries).hasSize(manifest.entries.size)
        assertThat(manifest.entries.map { it.id }).containsNoDuplicates()
    }

    @Test fun `bundled manifest covers the 0_5_0 distros`() {
        val ids = manifest.entries.map { it.id }
        assertThat(ids).containsAtLeast("ubuntu-lts-desktop", "ubuntu-latest-desktop", "mint-cinnamon", "fedora-workstation", "debian-netinst", "debian-live-gnome")
        assertThat(manifest.entries.filter { it.distro == "fedora" }.all { it.clearSigned }).isTrue()
        assertThat(manifest.entries.filter { it.distro != "fedora" }.none { it.clearSigned }).isTrue()
    }

    @Test fun `entries with unpinned keys or plain http are dropped`() {
        val e = manifest.entries.first()
        val bad = manifest.copy(entries = listOf(e.copy(id = "a", keyId = "someone-else"), e.copy(id = "b", mirrors = listOf("http://example.com")), e))
        assertThat(bad.usable(keys).entries.map { it.id }).containsExactly(e.id)
    }
}
