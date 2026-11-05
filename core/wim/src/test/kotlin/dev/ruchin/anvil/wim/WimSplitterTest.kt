package dev.ruchin.anvil.wim

import dev.ruchin.anvil.common.ByteArraySource
import dev.ruchin.anvil.common.VirtualFile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WimSplitterTest {
    private val fixture = javaClass.getResource("/fixture.wim")!!.readBytes()
    @TempDir lateinit var tmp: File

    private fun splitFixture(maxPart: Long): List<Pair<String, ByteArray>> =
        WimSplitter.split(VirtualFile.of(ByteArraySource(fixture)), maxPartSize = maxPart).map {
            it.fileName to it.content.asSource().readBytes(0, it.content.size.toInt())
        }

    @Test fun `splits into consistent parts`() {
        val parts = splitFixture(maxPart = 25_000)
        assertThat(parts.size).isAtLeast(3)
        assertThat(parts.map { it.first }.take(3)).containsExactly("install.swm", "install2.swm", "install3.swm").inOrder()
        val orig = WimHeader(fixture.copyOf(WimHeader.SIZE))
        val origBlobs = WimSplitter.readBlobTable(fixture.copyOfRange(orig.blobTable.offset.toInt(), (orig.blobTable.offset + orig.blobTable.sizeInWim).toInt()))
        val guid = parts[0].second.copyOfRange(24, 40)
        val seen = mutableListOf<BlobEntry>()
        parts.forEachIndexed { i, (_, bytes) ->
            val h = WimHeader(bytes.copyOf(WimHeader.SIZE))
            assertThat(h.partNumber).isEqualTo(i + 1)
            assertThat(h.totalParts).isEqualTo(parts.size)
            assertThat(h.flags and WimHeader.FLAG_SPANNED).isNotEqualTo(0)
            assertThat(h.bootIndex).isEqualTo(0)
            assertThat(h.imageCount).isEqualTo(orig.imageCount)
            assertThat(bytes.copyOfRange(24, 40)).isEqualTo(guid)
            val table = WimSplitter.readBlobTable(bytes.copyOfRange(h.blobTable.offset.toInt(), (h.blobTable.offset + h.blobTable.sizeInWim).toInt()))
            for (e in table) {
                assertThat(e.partNumber).isEqualTo(i + 1)
                // The compressed bytes moved verbatim.
                val o = origBlobs.first { it.sha1.contentEquals(e.sha1) }
                assertThat(bytes.copyOfRange(e.res.offset.toInt(), (e.res.offset + e.res.sizeInWim).toInt()))
                    .isEqualTo(fixture.copyOfRange(o.res.offset.toInt(), (o.res.offset + o.res.sizeInWim).toInt()))
            }
            if (i == 0) assertThat(table.take(orig.imageCount).all { it.res.isMetadata }).isTrue()
            else assertThat(table.none { it.res.isMetadata }).isTrue()
            seen += table
            // XML copied unchanged
            assertThat(bytes.copyOfRange(h.xmlData.offset.toInt(), bytes.size))
                .isEqualTo(fixture.copyOfRange(orig.xmlData.offset.toInt(), (orig.xmlData.offset + orig.xmlData.sizeInWim).toInt()))
        }
        assertThat(seen.map { it.sha1.toList() }).containsExactlyElementsIn(origBlobs.filter { it.res.flags and ResHdr.FLAG_FREE == 0 }.map { it.sha1.toList() })
    }

    @Test fun `single part when everything fits`() {
        assertThat(splitFixture(maxPart = 10_000_000)).hasSize(1)
    }

    @Test fun `rejects solid wims`() {
        val bad = fixture.copyOf()
        val h = WimHeader(bad.copyOf(WimHeader.SIZE))
        val bb = ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN)
        val first = h.blobTable.offset.toInt()
        bad[first + 7] = (bad[first + 7].toInt() or ResHdr.FLAG_SOLID).toByte() // flags byte of first entry
        assertThat(bb.get(first + 7).toInt() and ResHdr.FLAG_SOLID).isNotEqualTo(0)
        assertThrows<WimSplitException> { WimSplitter.split(VirtualFile.of(ByteArraySource(bad))) }
    }

    /** Cross-check with the reference implementation: wimlib must accept and fully verify our split. */
    @Test fun `wimlib verifies our split`() {
        val wimlib = System.getProperty("anvil.wimlib")?.let(::File)
        assumeTrue(wimlib != null && wimlib.canExecute(), "set -Danvil.wimlib=/path/to/wimlib-imagex")
        val parts = splitFixture(maxPart = 25_000)
        parts.forEach { (name, bytes) -> File(tmp, name).writeBytes(bytes) }
        val p = ProcessBuilder(wimlib!!.path, "verify", File(tmp, "install.swm").path, "--ref=${tmp.path}/install*.swm")
            .redirectErrorStream(true).also { it.environment()["LD_LIBRARY_PATH"] = wimlib.parentFile.resolve("../lib").path }.start()
        val out = p.inputStream.bufferedReader().readText()
        assertThat(p.waitFor()).isEqualTo(0)
        assertThat(out).contains("was successfully verified")
    }
}
