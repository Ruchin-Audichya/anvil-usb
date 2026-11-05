package dev.ruchin.anvil.fat

import dev.ruchin.anvil.blockdev.BlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32

enum class PartitionScheme {
    /** GPT + protective MBR. Best for UEFI PCs (everything since ~2012, required for Windows 11). */
    GPT,
    /** Classic MBR with one active FAT32 partition. For firmware that only boots MBR removable media. */
    MBR,
}

/** A single partition spanning (almost) the whole drive. */
data class PartitionLayout(val scheme: PartitionScheme, val startLba: Long, val sectorCount: Long)

/**
 * Writes a fresh partition table with one data partition, 1 MiB aligned.
 * Clears the first and last MiB first, so remnants of a previous ISO9660/GPT
 * (e.g. an Ubuntu stick) can't confuse firmware or Windows.
 */
object Partitioner {
    /** "Microsoft basic data" — what Windows and Rufus use for a FAT32 install stick. */
    val BASIC_DATA: UUID = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    private const val ALIGN_BYTES = 1024 * 1024L
    private const val GPT_ENTRIES = 128
    private const val GPT_ENTRY_SIZE = 128

    fun plan(device: BlockDevice, scheme: PartitionScheme): PartitionLayout {
        val bs = device.blockSize.toLong()
        val align = ALIGN_BYTES / bs
        val total = device.blockCount
        val start = align
        val lastUsable = when (scheme) {
            PartitionScheme.GPT -> total - 1 - entrySectors(bs) - 1
            PartitionScheme.MBR -> total - 1
        }
        val end = ((lastUsable + 1) / align) * align // exclusive, aligned
        require(end > start + align) { "device too small for a partition" }
        return PartitionLayout(scheme, start, end - start)
    }

    /**
     * Each sector is written exactly once: the first and last MiB are composed in
     * memory (zeros + MBR/GPT) and written in one go, which keeps read-back
     * verification exact.
     */
    fun write(device: BlockDevice, layout: PartitionLayout, partitionName: String = "ANVIL", random: SecureRandom = SecureRandom()) {
        val bs = device.blockSize
        val align = (ALIGN_BYTES / bs).toInt()
        val head = ByteArray(align * bs)
        val tail = ByteArray(align * bs)
        val tailLba = device.blockCount - align

        when (layout.scheme) {
            PartitionScheme.MBR -> {
                writeMbrEntry(head, 0, status = 0x80, type = 0x0C, startLba = layout.startLba, sectors = layout.sectorCount)
                finishMbr(head, random)
            }
            PartitionScheme.GPT -> composeGpt(device, layout, partitionName, random, head, tail, tailLba)
        }
        device.write(0, head, 0, align)
        device.write(tailLba, tail, 0, align)
    }

    private fun composeGpt(
        device: BlockDevice, layout: PartitionLayout, name: String, random: SecureRandom,
        head: ByteArray, tail: ByteArray, tailLba: Long,
    ) {
        val bs = device.blockSize
        val total = device.blockCount
        val entrySectors = entrySectors(bs.toLong()).toInt()

        // Protective MBR
        writeMbrEntry(head, 0, status = 0, type = 0xEE, startLba = 1, sectors = minOf(total - 1, 0xFFFF_FFFFL), protective = true)
        finishMbr(head, random, signature = false)

        // Partition entry array
        val entries = ByteArray(entrySectors * bs)
        val eb = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
        putGuid(entries, 0, BASIC_DATA)
        putGuid(entries, 16, randomGuid(random))
        eb.putLong(32, layout.startLba)
        eb.putLong(40, layout.startLba + layout.sectorCount - 1)
        eb.putLong(48, 0)
        val n = name.take(36)
        for (i in n.indices) eb.putShort(56 + i * 2, n[i].code.toShort())
        val entriesCrc = crc32(entries, 0, GPT_ENTRIES * GPT_ENTRY_SIZE)

        val diskGuid = randomGuid(random)
        val firstUsable = 2L + entrySectors
        val lastUsable = total - 2 - entrySectors
        fun header(myLba: Long, altLba: Long, entriesLba: Long): ByteArray {
            val h = ByteArray(bs)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
            b.putInt(8, 0x00010000)
            b.putInt(12, 92)
            b.putLong(24, myLba)
            b.putLong(32, altLba)
            b.putLong(40, firstUsable)
            b.putLong(48, lastUsable)
            putGuid(h, 56, diskGuid)
            b.putLong(72, entriesLba)
            b.putInt(80, GPT_ENTRIES)
            b.putInt(84, GPT_ENTRY_SIZE)
            b.putInt(88, entriesCrc.toInt())
            b.putInt(16, crc32(h, 0, 92).toInt())
            return h
        }
        header(1, total - 1, 2).copyInto(head, 1 * bs)
        entries.copyInto(head, 2 * bs)
        val backupEntriesLba = total - 1 - entrySectors
        entries.copyInto(tail, ((backupEntriesLba - tailLba) * bs).toInt())
        header(total - 1, 1, backupEntriesLba).copyInto(tail, ((total - 1 - tailLba) * bs).toInt())
    }

    private fun writeMbrEntry(mbr: ByteArray, index: Int, status: Int, type: Int, startLba: Long, sectors: Long, protective: Boolean = false) {
        val o = 446 + index * 16
        val b = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        mbr[o] = status.toByte()
        // CHS values are meaningless on modern disks; use the "LBA only" markers.
        if (protective) { mbr[o + 1] = 0x00; mbr[o + 2] = 0x02; mbr[o + 3] = 0x00 } else { mbr[o + 1] = 0xFE.toByte(); mbr[o + 2] = 0xFF.toByte(); mbr[o + 3] = 0xFF.toByte() }
        mbr[o + 4] = type.toByte()
        mbr[o + 5] = 0xFE.toByte(); mbr[o + 6] = 0xFF.toByte(); mbr[o + 7] = 0xFF.toByte()
        b.putInt(o + 8, startLba.toInt())
        b.putInt(o + 12, minOf(sectors, 0xFFFF_FFFFL).toInt())
    }

    private fun finishMbr(mbr: ByteArray, random: SecureRandom, signature: Boolean = true) {
        if (signature) ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN).putInt(440, random.nextInt())
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
    }

    private fun entrySectors(bs: Long) = (GPT_ENTRIES * GPT_ENTRY_SIZE + bs - 1) / bs

    private fun randomGuid(r: SecureRandom): UUID {
        val b = ByteArray(16).also(r::nextBytes)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long)
    }

    /** GPT GUIDs are "mixed endian": first three fields little-endian, rest big-endian. */
    fun putGuid(dst: ByteArray, off: Int, g: UUID) {
        val be = ByteBuffer.allocate(16).putLong(g.mostSignificantBits).putLong(g.leastSignificantBits).array()
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        for (i in 0 until 16) dst[off + i] = be[order[i]]
    }

    private fun crc32(b: ByteArray, off: Int, len: Int) = CRC32().apply { update(b, off, len) }.value
}
