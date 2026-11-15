package dev.ruchin.anvil.catalog.linux

import dev.ruchin.anvil.common.AnvilException
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class SignatureVerificationException(userMessage: String, technical: String) : AnvilException(userMessage, technical)

/**
 * OpenPGP signature checks for distro checksum files, with Bouncy Castle's lightweight API
 * (no JCA provider, so it behaves the same on the JVM and on Android).
 *
 *  - detached: Ubuntu `SHA256SUMS.gpg`, Debian `SHA256SUMS.sign`, Mint `sha256sum.txt.gpg`
 *  - clear-signed: Fedora `…-CHECKSUM`
 *
 * Only signatures made by the one pinned key are accepted, and only with SHA-2 digests.
 */
object SignatureVerifier {

    /** Verifies [signature] (armored or binary) over [data]. Throws if it isn't a good signature by [key]. */
    fun verifyDetached(data: ByteArray, signature: ByteArray, key: PinnedKey) {
        val sigs = readSignatures(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)))
        check(sigs, key) { sig, pub ->
            sig.init(BcPGPContentVerifierBuilderProvider(), pub)
            sig.update(data)
            sig.verify()
        }
    }

    /**
     * Verifies a clear-signed document and returns the text that was actually signed. Callers must
     * parse *this* text, never the raw file, so nothing outside the signature can sneak in.
     */
    fun verifyClearSigned(document: ByteArray, key: PinnedKey): String {
        val armored = ArmoredInputStream(ByteArrayInputStream(document))
        val lines = readClearText(armored)
        if (lines == null) throw invalid(key, "not a clear-signed document")
        val sigs = readSignatures(armored)
        check(sigs, key) { sig, pub ->
            sig.init(BcPGPContentVerifierBuilderProvider(), pub)
            // RFC 4880 §7.1: trailing whitespace is ignored, lines are joined with CRLF, no final line break.
            lines.forEachIndexed { i, line ->
                if (i > 0) sig.update(CRLF)
                sig.update(line)
            }
            sig.verify()
        }
        return lines.joinToString("\n") { String(it, Charsets.UTF_8) }
    }

    private fun check(sigs: List<PGPSignature>, key: PinnedKey, verify: (PGPSignature, PGPPublicKey) -> Boolean) {
        if (sigs.isEmpty()) throw invalid(key, "no signature packets")
        val reasons = mutableListOf<String>()
        for (sig in sigs) {
            if (sig.hashAlgorithm !in ACCEPTED_HASHES) { reasons += "weak digest ${sig.hashAlgorithm}"; continue }
            val pub = signerKey(sig, key)
            if (pub == null) { reasons += "signed by %016X, not ${key.fingerprint}".format(sig.keyID); continue }
            val ok = try { verify(sig, pub) } catch (e: PGPException) { reasons += "pgp: ${e.message}"; false }
            if (ok) return
            reasons += "bad signature by %016X".format(sig.keyID)
        }
        throw invalid(key, reasons.joinToString("; "))
    }

    private fun signerKey(sig: PGPSignature, key: PinnedKey): PGPPublicKey? =
        key.ring.getPublicKey(sig.keyID)
            ?: sig.hashedSubPackets?.issuerFingerprint?.fingerprint?.let { key.ring.getPublicKey(it) }

    private fun readSignatures(input: InputStream): List<PGPSignature> {
        var factory = BcPGPObjectFactory(input)
        while (true) {
            val obj = try { factory.nextObject() } catch (e: IOException) { return emptyList() } ?: return emptyList()
            when (obj) {
                is PGPSignatureList -> return obj.toList()
                is PGPCompressedData -> factory = BcPGPObjectFactory(obj.dataStream)
                else -> Unit // skip markers etc.
            }
        }
    }

    /**
     * Reads the clear text of a clear-signed message the way RFC 4880 defines it: dash-escaping is
     * undone by [ArmoredInputStream]; each line loses its line ending and trailing spaces/tabs.
     */
    private fun readClearText(armored: ArmoredInputStream): List<ByteArray>? {
        val lines = mutableListOf<ByteArray>()
        val cur = ByteArrayOutputStream()
        var c = armored.read()
        if (c < 0 || !armored.isClearText) return null
        while (c >= 0 && armored.isClearText) {
            if (c == '\n'.code) {
                lines += stripTrailing(cur.toByteArray()); cur.reset()
            } else {
                cur.write(c)
            }
            c = armored.read()
        }
        // The line break before "-----BEGIN PGP SIGNATURE-----" belongs to the armor, not the text;
        // ArmoredInputStream has already handed us that '\n', so the last line is complete.
        if (cur.size() > 0) lines += stripTrailing(cur.toByteArray())
        return lines
    }

    private fun stripTrailing(line: ByteArray): ByteArray {
        var end = line.size
        while (end > 0 && line[end - 1].toInt().toChar().let { it == ' ' || it == '\t' || it == '\r' }) end--
        return line.copyOf(end)
    }

    private fun invalid(key: PinnedKey, technical: String) = SignatureVerificationException(
        "The checksum list isn't signed by ${key.owner}. Anvil stopped before downloading or writing anything. " +
            "This can mean a broken or tampered mirror; try again later.",
        "signature check failed for ${key.id}: $technical",
    )

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private val ACCEPTED_HASHES = setOf(
        HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA224,
    )
}
