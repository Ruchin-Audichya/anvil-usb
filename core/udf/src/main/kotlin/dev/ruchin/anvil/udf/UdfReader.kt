package dev.ruchin.anvil.udf

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.RandomAccessSource
import dev.ruchin.anvil.common.Segment
import dev.ruchin.anvil.common.VirtualFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A file or directory inside a UDF volume. */
sealed class UdfNode {
    abstract val name: String
    abstract val path: String

    class File(override val name: String, override val path: String, val content: VirtualFile) : UdfNode() {
        val size get() = content.size
        /** Position of the first byte in the image, for reading files in disk order. */
        val firstOffset: Long get() = (content.segments.firstOrNull() as? Segment.Range)?.offset ?: Long.MAX_VALUE
    }

    class Directory(override val name: String, override val path: String, val children: List<UdfNode>) : UdfNode() {
        fun walk(): Sequence<UdfNode> = sequence {
            for (c in children) {
                yield(c)
                if (c is Directory) yieldAll(c.walk())
            }
        }
        fun files() = walk().filterIsInstance<File>()
        fun find(path: String): UdfNode? {
            var cur: UdfNode = this
            for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
                cur = (cur as? Directory)?.children?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
            return cur
        }
    }
}

class UdfException(technical: String) : AnvilException(
    "This ISO's file system couldn't be read. The file may be damaged — download it again.", technical,
)

/**
 * Minimal read-only UDF (ECMA-167 / OSTA UDF 1.02–2.01) reader — enough for
 * Windows installation ISOs, whose ISO9660 tree is only a stub README.
 *
 * Path: Anchor Volume Descriptor Pointer (sector 256) → Volume Descriptor
 * Sequence (Partition + Logical Volume descriptors) → File Set Descriptor →
 * root directory ICB → File Entries / File Identifier Descriptors.
 *
 * Nothing is copied: every file is returned as a [VirtualFile] pointing at its
 * extents inside the image.
 */
class UdfReader(private val image: RandomAccessSource) {
    private val sector = SECTOR
    private var partitionStart = 0L
    private var blockSize = SECTOR
    var volumeIdentifier: String = ""
        private set

    fun readRoot(): UdfNode.Directory {
        val avdp = readTagged(256L * sector, sector, TAG_AVDP) ?: throw UdfException("no UDF anchor at sector 256")
        val vdsLen = le32(avdp, 16)
        val vdsLoc = le32(avdp, 20)
        var fsdLocation: LongAd? = null
        var partNumber = -1
        var partitionMapPartNumber = -1
        // Walk the Main Volume Descriptor Sequence.
        var i = 0L
        while (i * sector < vdsLen) {
            val buf = image.readBytes((vdsLoc + i) * sector, sector)
            val tag = le16(buf, 0)
            when (tag) {
                TAG_PVD -> volumeIdentifier = dstring(buf, 24, 32)
                TAG_PARTITION -> {
                    partNumber = le16(buf, 22)
                    partitionStart = le32(buf, 188)
                }
                TAG_LVD -> {
                    blockSize = le32(buf, 212).toInt()
                    fsdLocation = longAd(buf, 248)
                    val nMaps = le32(buf, 268).toInt()
                    if (nMaps > 0) {
                        val type = buf[440].toInt() and 0xFF
                        if (type != 1) throw UdfException("unsupported partition map type $type (UDF 2.5 metadata partition?)")
                        partitionMapPartNumber = le16(buf, 444)
                    }
                }
                TAG_TERMINATOR -> break
            }
            i++
        }
        if (blockSize != sector) throw UdfException("unsupported UDF block size $blockSize")
        if (partNumber < 0 || fsdLocation == null) throw UdfException("missing partition or logical volume descriptor")
        if (partitionMapPartNumber >= 0 && partitionMapPartNumber != partNumber) throw UdfException("partition map mismatch")

        val fsd = readTagged(blockPos(fsdLocation.lbn), sector, TAG_FSD) ?: throw UdfException("no File Set Descriptor")
        val rootIcb = longAd(fsd, 400)
        return readDirectory("", "", rootIcb.lbn, depth = 0)
    }

    private fun readDirectory(name: String, path: String, icbLbn: Long, depth: Int): UdfNode.Directory {
        if (depth > 64) throw UdfException("directory nesting too deep at $path")
        val entry = readFileEntry(icbLbn)
        if (!entry.isDirectory) throw UdfException("$path is not a directory")
        val dirBytes = entry.content.asSource().let { it.readBytes(0, it.size.toInt()) }
        val children = ArrayList<UdfNode>()
        var p = 0
        while (p + 38 <= dirBytes.size) {
            val tag = le16(dirBytes, p)
            if (tag != TAG_FID) throw UdfException("bad FID tag $tag in $path at $p")
            val characteristics = dirBytes[p + 18].toInt() and 0xFF
            val lFi = dirBytes[p + 19].toInt() and 0xFF
            val icb = longAd(dirBytes, p + 20)
            val lIu = le16(dirBytes, p + 36)
            val nameOff = p + 38 + lIu
            val isParent = characteristics and 0x08 != 0
            val isDeleted = characteristics and 0x04 != 0
            if (!isParent && !isDeleted && lFi > 0) {
                val childName = decodeName(dirBytes, nameOff, lFi)
                val childPath = "$path/$childName"
                if (characteristics and 0x02 != 0) {
                    children += readDirectory(childName, childPath, icb.lbn, depth + 1)
                } else {
                    val fe = readFileEntry(icb.lbn)
                    children += UdfNode.File(childName, childPath, fe.content)
                }
            }
            p += ((38 + lIu + lFi) + 3) and 3.inv()
        }
        return UdfNode.Directory(name, path.ifEmpty { "/" }, children)
    }

    private class FileEntry(val isDirectory: Boolean, val content: VirtualFile)

    private fun readFileEntry(lbn: Long): FileEntry {
        val pos = blockPos(lbn)
        val buf = image.readBytes(pos, sector)
        val tag = le16(buf, 0)
        val (adStart, lEa, lAd) = when (tag) {
            TAG_FE -> Triple(176, le32(buf, 168).toInt(), le32(buf, 172).toInt())
            TAG_EFE -> Triple(216, le32(buf, 208).toInt(), le32(buf, 212).toInt())
            else -> throw UdfException("expected File Entry at block $lbn, found tag $tag")
        }
        val fileType = buf[27].toInt() and 0xFF
        val icbFlags = le16(buf, 34)
        val infoLength = le64(buf, 56)
        val adOff = adStart + lEa
        if (adOff + lAd > sector) throw UdfException("allocation descriptors overflow the File Entry at block $lbn")
        val segments = ArrayList<Segment>()
        when (icbFlags and 7) {
            AD_INLINE -> segments += Segment.Bytes(buf.copyOfRange(adOff, adOff + infoLength.toInt()))
            AD_SHORT -> {
                var o = adOff
                while (o + 8 <= adOff + lAd) {
                    val lenRaw = le32(buf, o)
                    val len = lenRaw and 0x3FFFFFFF
                    val type = (lenRaw ushr 30).toInt()
                    if (len == 0L) break
                    if (type == 3) throw UdfException("continued allocation descriptors are not supported (block $lbn)")
                    segments += if (type == 0) Segment.Range(image, blockPos(le32(buf, o + 4)), len) else Segment.Zeros(len)
                    o += 8
                }
            }
            AD_LONG -> {
                var o = adOff
                while (o + 16 <= adOff + lAd) {
                    val lenRaw = le32(buf, o)
                    val len = lenRaw and 0x3FFFFFFF
                    val type = (lenRaw ushr 30).toInt()
                    if (len == 0L) break
                    if (type == 3) throw UdfException("continued allocation descriptors are not supported (block $lbn)")
                    segments += if (type == 0) Segment.Range(image, blockPos(le32(buf, o + 4)), len) else Segment.Zeros(len)
                    o += 16
                }
            }
            else -> throw UdfException("unsupported allocation descriptor type ${icbFlags and 7}")
        }
        // Extents are allocated in whole blocks; trim to the real file length.
        val vf = VirtualFile(segments)
        val content = when {
            vf.size == infoLength -> vf
            vf.size > infoLength -> VirtualFile(vf.slice(0, infoLength))
            else -> throw UdfException("file at block $lbn: extents ${vf.size} < length $infoLength")
        }
        return FileEntry(fileType == FILETYPE_DIRECTORY, content)
    }

    private data class LongAd(val length: Long, val lbn: Long, val partRef: Int)

    private fun longAd(b: ByteArray, off: Int) = LongAd(le32(b, off) and 0x3FFFFFFF, le32(b, off + 4), le16(b, off + 8))

    private fun blockPos(lbn: Long) = (partitionStart + lbn) * sector

    private fun readTagged(pos: Long, len: Int, expected: Int): ByteArray? {
        if (pos + len > image.size) return null
        val b = image.readBytes(pos, len)
        if (le16(b, 0) != expected) return null
        // Tag checksum: sum of bytes 0-15 except byte 4.
        var sum = 0
        for (k in 0 until 16) if (k != 4) sum += b[k].toInt() and 0xFF
        if ((sum and 0xFF) != (b[4].toInt() and 0xFF)) return null
        return b
    }

    companion object {
        const val SECTOR = 2048
        private const val TAG_PVD = 1
        private const val TAG_AVDP = 2
        private const val TAG_PARTITION = 5
        private const val TAG_LVD = 6
        private const val TAG_TERMINATOR = 8
        private const val TAG_FSD = 256
        private const val TAG_FID = 257
        private const val TAG_FE = 261
        private const val TAG_EFE = 266
        private const val FILETYPE_DIRECTORY = 4
        private const val AD_SHORT = 0
        private const val AD_LONG = 1
        private const val AD_INLINE = 3

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        private fun le64(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long

        /** OSTA CS0 "compressed unicode": first byte 8 = Latin-1, 16 = UTF-16BE. */
        internal fun decodeName(b: ByteArray, off: Int, len: Int): String {
            if (len <= 1) return ""
            return when (b[off].toInt() and 0xFF) {
                8 -> String(b, off + 1, len - 1, Charsets.ISO_8859_1)
                16 -> String(b, off + 1, len - 1, Charsets.UTF_16BE)
                else -> throw UdfException("bad compression id ${b[off]}")
            }
        }

        /** dstring: CS0 bytes with the used length in the last byte. */
        private fun dstring(b: ByteArray, off: Int, fieldLen: Int): String {
            val used = b[off + fieldLen - 1].toInt() and 0xFF
            return if (used == 0) "" else decodeName(b, off, minOf(used, fieldLen - 1)).trim()
        }
    }
}
