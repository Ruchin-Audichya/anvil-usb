package dev.ruchin.anvil.wim

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.Segment
import dev.ruchin.anvil.common.VirtualFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Resource header: where a blob lives inside a WIM. */
data class ResHdr(val sizeInWim: Long, val flags: Int, val offset: Long, val originalSize: Long) {
    val isMetadata get() = flags and FLAG_METADATA != 0
    val isSolid get() = flags and FLAG_SOLID != 0

    fun write(bb: ByteBuffer, at: Int) {
        bb.putLong(at, (sizeInWim and 0x00FF_FFFF_FFFF_FFFFL) or (flags.toLong() shl 56))
        bb.putLong(at + 8, offset)
        bb.putLong(at + 16, originalSize)
    }

    companion object {
        const val FLAG_FREE = 0x01
        const val FLAG_METADATA = 0x02
        const val FLAG_COMPRESSED = 0x04
        const val FLAG_SPANNED = 0x08
        const val FLAG_SOLID = 0x10
        const val DISK_SIZE = 24

        fun read(bb: ByteBuffer, at: Int): ResHdr {
            val first = bb.getLong(at)
            return ResHdr(first and 0x00FF_FFFF_FFFF_FFFFL, (first ushr 56).toInt() and 0xFF, bb.getLong(at + 8), bb.getLong(at + 16))
        }
    }
}

data class BlobEntry(val res: ResHdr, val partNumber: Int, val refCount: Int, val sha1: ByteArray)

class WimHeader(val raw: ByteArray) {
    private val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val magic get() = String(raw, 0, 8, Charsets.US_ASCII)
    val headerSize get() = bb.getInt(8)
    val version get() = bb.getInt(12)
    val flags get() = bb.getInt(16)
    val chunkSize get() = bb.getInt(20)
    val partNumber get() = bb.getShort(40).toInt() and 0xFFFF
    val totalParts get() = bb.getShort(42).toInt() and 0xFFFF
    val imageCount get() = bb.getInt(44)
    val blobTable get() = ResHdr.read(bb, 48)
    val xmlData get() = ResHdr.read(bb, 72)
    val bootMetadata get() = ResHdr.read(bb, 96)
    val bootIndex get() = bb.getInt(120)
    val integrityTable get() = ResHdr.read(bb, 124)

    companion object {
        const val SIZE = 208
        const val MAGIC = "MSWIM\u0000\u0000\u0000"
        const val FLAG_SPANNED = 0x08
        const val FLAG_RP_FIX = 0x80
        const val FLAG_READONLY = 0x04
        const val FLAG_WRITE_IN_PROGRESS = 0x40
        const val COMPRESSION_MASK = 0x00FF_0002
    }
}

/** One output .swm part, described as segments (no bytes are copied until it's written). */
class SwmPart(val fileName: String, val content: VirtualFile)

class WimSplitException(userMessage: String, technical: String) : AnvilException(userMessage, technical)

/**
 * Splits a (non-solid) WIM into .swm parts that each fit on FAT32 — the same
 * on-disk layout `wimlib_split()` and DISM `/Split-Image` produce, which Windows
 * Setup reads natively (`install.swm`, `install2.swm`, …).
 *
 * No recompression: compressed resources are copied verbatim, so the split is
 * pure byte plumbing. Each part is: header (SPANNED, shared GUID, part n/N,
 * boot index 0) → its blobs → [part 1 only: image metadata resources] →
 * its own uncompressed blob table → the original XML.
 */
object WimSplitter {
    /** FAT32's per-file limit is 4 GiB − 1; leave room for the blob table and XML. */
    const val DEFAULT_MAX_PART = 4000L * 1024 * 1024
    private const val ENTRY_SIZE = ResHdr.DISK_SIZE + 2 + 4 + 20

    fun needsSplit(size: Long) = size > 0xFFFF_FFFFL

    fun split(
        wim: VirtualFile,
        baseName: String = "install",
        maxPartSize: Long = DEFAULT_MAX_PART,
        guid: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
    ): List<SwmPart> {
        val src = wim.asSource()
        if (src.size < WimHeader.SIZE) throw invalid("file too small")
        val hdr = WimHeader(src.readBytes(0, WimHeader.SIZE))
        if (hdr.magic != WimHeader.MAGIC) throw invalid("bad magic '${hdr.magic}' (pipable or not a WIM)")
        if (hdr.headerSize != WimHeader.SIZE) throw invalid("header size ${hdr.headerSize}")
        if (hdr.totalParts != 1) throw invalid("already split (${hdr.totalParts} parts)")
        if (hdr.flags and WimHeader.FLAG_WRITE_IN_PROGRESS != 0) throw invalid("WIM marked write-in-progress")

        val blobs = readBlobTable(src.readBytes(hdr.blobTable.offset, hdr.blobTable.sizeInWim.toInt()))
        if (blobs.any { it.res.isSolid }) {
            throw WimSplitException(
                "This Windows image uses solid (ESD) compression and can't be split for FAT32. " +
                    "Use the NTFS layout in Advanced options instead.",
                "solid resources present",
            )
        }
        val metadata = blobs.filter { it.res.isMetadata }
        if (metadata.size != hdr.imageCount) throw invalid("found ${metadata.size} metadata resources for ${hdr.imageCount} images")
        // wimlib order: data blobs in on-disk order; metadata goes first in part 1.
        val data = blobs.filter { !it.res.isMetadata && it.res.flags and ResHdr.FLAG_FREE == 0 }.sortedBy { it.res.offset }
        val xml = src.readBytes(hdr.xmlData.offset, hdr.xmlData.sizeInWim.toInt())

        // --- Assign blobs to parts (wimlib's add_blob_to_swm) ---
        val partBlobs = mutableListOf(mutableListOf<BlobEntry>())
        val partSizes = mutableListOf(metadata.sumOf { it.res.sizeInWim })
        for (b in data) {
            val cur = partSizes.last()
            if (cur + b.res.sizeInWim >= maxPartSize && cur != 0L) {
                partBlobs += mutableListOf<BlobEntry>()
                partSizes += 0L
            }
            partBlobs.last() += b
            partSizes[partSizes.lastIndex] = partSizes.last() + b.res.sizeInWim
        }
        val total = partBlobs.size

        return partBlobs.mapIndexed { idx, list ->
            val partNo = idx + 1
            val segments = ArrayList<Segment>()
            val table = ArrayList<BlobEntry>()
            var pos = WimHeader.SIZE.toLong()
            segments += Segment.Bytes(ByteArray(0)) // header placeholder, filled below
            fun copy(b: BlobEntry, refCount: Int): BlobEntry {
                segments += wim.slice(b.res.offset, b.res.sizeInWim)
                val moved = b.copy(res = b.res.copy(offset = pos), partNumber = partNo, refCount = refCount)
                pos += b.res.sizeInWim
                return moved
            }
            for (b in list) table += copy(b, b.refCount)
            val metaMoved = if (partNo == 1) metadata.map { copy(it, 1) } else emptyList()
            // Metadata entries come first in the blob table, then data blobs sorted by new offset.
            val ordered = metaMoved + table.sortedBy { it.res.offset }
            val tableBytes = encodeBlobTable(ordered)
            val tableHdr = ResHdr(tableBytes.size.toLong(), ResHdr.FLAG_METADATA, pos, tableBytes.size.toLong())
            segments += Segment.Bytes(tableBytes)
            pos += tableBytes.size
            val xmlHdr = ResHdr(xml.size.toLong(), ResHdr.FLAG_METADATA, pos, xml.size.toLong())
            segments += Segment.Bytes(xml)
            pos += xml.size

            segments[0] = Segment.Bytes(buildHeader(hdr, guid, partNo, total, tableHdr, xmlHdr))
            val name = if (partNo == 1) "$baseName.swm" else "$baseName$partNo.swm"
            SwmPart(name, VirtualFile(segments).coalesced())
        }
    }

    private fun buildHeader(orig: WimHeader, guid: ByteArray, part: Int, total: Int, table: ResHdr, xml: ResHdr): ByteArray {
        val out = ByteArray(WimHeader.SIZE)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        System.arraycopy(WimHeader.MAGIC.toByteArray(Charsets.US_ASCII), 0, out, 0, 8)
        bb.putInt(8, WimHeader.SIZE)
        bb.putInt(12, orig.version)
        val keep = orig.flags and (WimHeader.FLAG_RP_FIX or WimHeader.FLAG_READONLY or WimHeader.COMPRESSION_MASK)
        bb.putInt(16, keep or if (total != 1) WimHeader.FLAG_SPANNED else 0)
        bb.putInt(20, orig.chunkSize)
        System.arraycopy(guid, 0, out, 24, 16)
        bb.putShort(40, part.toShort())
        bb.putShort(42, total.toShort())
        bb.putInt(44, orig.imageCount)
        table.write(bb, 48)
        xml.write(bb, 72)
        // boot metadata (96) zero, boot index (120) = 0 for split WIMs, integrity table (124) zero.
        return out
    }

    fun readBlobTable(bytes: ByteArray): List<BlobEntry> {
        if (bytes.size % ENTRY_SIZE != 0) throw invalid("blob table size ${bytes.size} not a multiple of $ENTRY_SIZE")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until bytes.size / ENTRY_SIZE).map { i ->
            val o = i * ENTRY_SIZE
            BlobEntry(
                res = ResHdr.read(bb, o),
                partNumber = bb.getShort(o + 24).toInt() and 0xFFFF,
                refCount = bb.getInt(o + 26),
                sha1 = bytes.copyOfRange(o + 30, o + 50),
            )
        }
    }

    fun encodeBlobTable(entries: List<BlobEntry>): ByteArray {
        val out = ByteArray(entries.size * ENTRY_SIZE)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        entries.forEachIndexed { i, e ->
            val o = i * ENTRY_SIZE
            e.res.write(bb, o)
            bb.putShort(o + 24, e.partNumber.toShort())
            bb.putInt(o + 26, e.refCount)
            System.arraycopy(e.sha1, 0, out, o + 30, 20)
        }
        return out
    }

    private fun invalid(t: String) = WimSplitException("install.wim in this ISO looks damaged and can't be split.", "WIM: $t")
}
