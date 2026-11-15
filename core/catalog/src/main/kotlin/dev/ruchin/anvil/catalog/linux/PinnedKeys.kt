package dev.ruchin.anvil.catalog.linux

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection
import java.io.InputStream

/** A distro release-signing key shipped inside the app. */
class PinnedKey(
    val id: String,
    /** Full v4 fingerprint, upper-case hex, no spaces. */
    val fingerprint: String,
    /** Who signs with it, for the UI ("Ubuntu CD Image Automatic Signing Key (2012)"). */
    val owner: String,
    val ring: PGPPublicKeyRing,
)

/**
 * Distro signing keys pinned inside the app. Each key file under `keys/` must hash to the
 * fingerprint written here, so neither a remote manifest nor a swapped resource can introduce a
 * key. Fingerprints were checked against each project's own verification page:
 *
 *  - Ubuntu: ubuntu.com/tutorials/how-to-verify-ubuntu (key D94AA3F0EFE21092)
 *  - Linux Mint: linuxmint-installation-guide.readthedocs.io/en/latest/verify.html
 *  - Fedora: fedoraproject.org/security
 *  - Debian: www.debian.org/CD/verify
 */
class PinnedKeys(private val keys: Map<String, PinnedKey>) {
    operator fun get(id: String): PinnedKey? = keys[id]
    fun contains(id: String) = id in keys
    val ids: Set<String> get() = keys.keys

    companion object {
        /** id → (fingerprint, owner). Adding a distro release key means adding it here AND under resources/keys. */
        val PINNED: Map<String, Pair<String, String>> = linkedMapOf(
            "ubuntu-cdimage" to ("843938DF228D22F7B3742BC0D94AA3F0EFE21092" to "Ubuntu CD Image Automatic Signing Key (2012)"),
            "linuxmint-iso" to ("27DEB15644C6B3CF3BD7D291300F846BA25BAE09" to "Linux Mint ISO Signing Key"),
            "fedora-44" to ("36F612DCF27F7D1A48A835E4DBFCF71C6D9F90A6" to "Fedora (44)"),
            "fedora-45" to ("4F50A6114CD5C6976A7F1179655A4B02F577861E" to "Fedora (45)"),
            "fedora-46" to ("D924B10D3E810DABDD8B56B596E7E91491211FCE" to "Fedora (46)"),
            "debian-cd" to ("DF9B9C49EAA9298432589D76DA87E80D6294BE9B" to "Debian CD signing key"),
        )

        private const val RESOURCE_DIR = "/dev/ruchin/anvil/catalog/linux/keys/"

        /** Loads every pinned key from the bundled resources and checks its fingerprint. */
        fun load(): PinnedKeys = PinnedKeys(PINNED.mapValues { (id, v) ->
            val stream = PinnedKeys::class.java.getResourceAsStream("$RESOURCE_DIR$id.asc")
                ?: error("pinned key resource missing: $id.asc")
            stream.use { fromStream(id, v.first, v.second, it) }
        })

        fun fromStream(id: String, fingerprint: String, owner: String, input: InputStream): PinnedKey {
            val rings = BcPGPPublicKeyRingCollection(PGPUtil.getDecoderStream(input))
            val ring = rings.keyRings.asSequence().firstOrNull { fingerprintHex(it.publicKey.fingerprint) == fingerprint }
                ?: error("key file $id.asc does not contain the pinned key $fingerprint")
            return PinnedKey(id, fingerprint, owner, ring)
        }

        fun fingerprintHex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
    }
}
