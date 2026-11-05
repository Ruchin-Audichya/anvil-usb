package dev.ruchin.anvil.blockdev

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SCSI Block Commands (SBC-3) / SPC-4 CDB builders and response parsers. */
object Scsi {
    const val TEST_UNIT_READY = 0x00
    const val REQUEST_SENSE = 0x03
    const val INQUIRY = 0x12
    const val MODE_SENSE_6 = 0x1A
    const val START_STOP_UNIT = 0x1B
    const val READ_CAPACITY_10 = 0x25
    const val READ_10 = 0x28
    const val WRITE_10 = 0x2A
    const val SYNCHRONIZE_CACHE_10 = 0x35
    const val READ_16 = 0x88
    const val WRITE_16 = 0x8A
    const val SERVICE_ACTION_IN_16 = 0x9E
    const val SA_READ_CAPACITY_16 = 0x10

    const val INQUIRY_LENGTH = 36
    const val SENSE_LENGTH = 18

    fun testUnitReady() = ByteArray(6)

    fun requestSense() = byteArrayOf(REQUEST_SENSE.toByte(), 0, 0, 0, SENSE_LENGTH.toByte(), 0)

    fun inquiry() = byteArrayOf(INQUIRY.toByte(), 0, 0, 0, INQUIRY_LENGTH.toByte(), 0)

    /** MODE SENSE(6), all pages, DBD=1. We only care about the WP bit in the header. */
    fun modeSense6(allocation: Int = 192) =
        byteArrayOf(MODE_SENSE_6.toByte(), 0x08, 0x3F, 0, allocation.toByte(), 0)

    fun readCapacity10() = ByteArray(10).also { it[0] = READ_CAPACITY_10.toByte() }

    fun readCapacity16() = ByteArray(16).also {
        it[0] = SERVICE_ACTION_IN_16.toByte()
        it[1] = SA_READ_CAPACITY_16.toByte()
        it[13] = 32
    }

    fun synchronizeCache10() = ByteArray(10).also { it[0] = SYNCHRONIZE_CACHE_10.toByte() }

    /** START STOP UNIT with START=0, LOEJ=1: asks the device to "eject" (safe removal). */
    fun ejectMedia() = byteArrayOf(START_STOP_UNIT.toByte(), 0, 0, 0, 0x02, 0)

    fun rw10(write: Boolean, lba: Long, blocks: Int): ByteArray {
        require(lba in 0..0xFFFF_FFFFL) { "LBA $lba needs 16-byte CDB" }
        require(blocks in 1..0xFFFF) { "blocks=$blocks" }
        val cdb = ByteArray(10)
        cdb[0] = (if (write) WRITE_10 else READ_10).toByte()
        ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(2, lba.toInt())
            putShort(7, blocks.toShort())
        }
        return cdb
    }

    fun rw16(write: Boolean, lba: Long, blocks: Int): ByteArray {
        require(blocks > 0)
        val cdb = ByteArray(16)
        cdb[0] = (if (write) WRITE_16 else READ_16).toByte()
        ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(2, lba)
            putInt(10, blocks)
        }
        return cdb
    }

    data class InquiryData(
        val peripheralDeviceType: Int,
        val removable: Boolean,
        val vendor: String,
        val product: String,
        val revision: String,
    ) {
        val isDirectAccess get() = peripheralDeviceType == 0x00
    }

    fun parseInquiry(b: ByteArray, len: Int = b.size): InquiryData {
        fun ascii(from: Int, to: Int) =
            if (len >= to) String(b, from, to - from, Charsets.US_ASCII).replace('\u0000', ' ').trim() else ""
        return InquiryData(
            peripheralDeviceType = b[0].toInt() and 0x1F,
            removable = (b[1].toInt() and 0x80) != 0,
            vendor = ascii(8, 16),
            product = ascii(16, 32),
            revision = ascii(32, 36),
        )
    }

    data class Capacity(val lastLba: Long, val blockSize: Int) {
        val blockCount get() = lastLba + 1
    }

    fun parseCapacity10(b: ByteArray): Capacity {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        return Capacity(bb.getInt(0).toLong() and 0xFFFF_FFFFL, bb.getInt(4))
    }

    fun parseCapacity16(b: ByteArray): Capacity {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        return Capacity(bb.getLong(0), bb.getInt(8))
    }

    data class Sense(val key: Int, val asc: Int, val ascq: Int) {
        val isUnitAttention get() = key == 0x06
        val isNotReady get() = key == 0x02
        val isWriteProtected get() = key == 0x07 && asc == 0x27
        val isMediumNotPresent get() = key == 0x02 && asc == 0x3A
        override fun toString() = "sense key=0x%x asc=0x%02x ascq=0x%02x".format(key, asc, ascq)
    }

    fun parseSense(b: ByteArray): Sense {
        val responseCode = b[0].toInt() and 0x7F
        return if (responseCode == 0x72 || responseCode == 0x73) {
            // Descriptor format
            Sense(b[1].toInt() and 0x0F, b[2].toInt() and 0xFF, b[3].toInt() and 0xFF)
        } else {
            Sense(b[2].toInt() and 0x0F, b[12].toInt() and 0xFF, b[13].toInt() and 0xFF)
        }
    }

    /** Mode parameter header (6): byte 2 bit 7 = WP. */
    fun parseWriteProtect(modeSense: ByteArray, len: Int): Boolean =
        len >= 4 && (modeSense[2].toInt() and 0x80) != 0
}
