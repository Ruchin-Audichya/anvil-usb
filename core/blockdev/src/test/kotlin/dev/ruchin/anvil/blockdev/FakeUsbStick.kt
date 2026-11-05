package dev.ruchin.anvil.blockdev

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A simulated USB flash drive that speaks real Bulk-Only Transport + SCSI over a
 * RAM disk. Lets us test the exact bytes the Android transport would put on the
 * wire, plus failure modes (stalls, unit attention, unplug) that are painful to
 * reproduce with hardware.
 */
class FakeUsbStick(
    val blockSize: Int = 512,
    val blockCount: Long = 8192,
    override val maxTransferSize: Int = 16 * 1024,
    var writeProtected: Boolean = false,
    /** Number of initial TEST UNIT READY calls that fail with UNIT ATTENTION. */
    var unitAttentionCount: Int = 1,
    val vendor: String = "SanDisk",
    val product: String = "Ultra",
    val supportsSyncCache: Boolean = false,
) : UsbBulkTransport {
    val disk = ByteArray((blockSize * blockCount).toInt())
    override var isConnected = true

    /** Stall the next N data-IN stages (to test clear-halt + CSW recovery). */
    var stallNextDataIn = 0
    /** Unplug once this many bytes have been written. */
    var unplugAfterBytesWritten = Long.MAX_VALUE
    var bytesWritten = 0L; private set
    val commandLog = mutableListOf<Int>()
    var resets = 0; private set
    var clearHalts = 0; private set

    private enum class Phase { CBW, DATA_IN, DATA_OUT, CSW }
    private var phase = Phase.CBW
    private var tag = 0
    private var status = 0
    private var residue = 0
    private var dataIn = ByteArray(0)
    private var dataPos = 0
    private var expected = 0
    private var outLba = 0L
    private var outBuffer = ByteArray(0)
    private var sense = byteArrayOf(0, 0, 0)
    private var haltedIn = false

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (!isConnected) return -1
        return when (phase) {
            Phase.CBW -> { handleCbw(buffer, offset, length); length }
            Phase.DATA_OUT -> {
                if (bytesWritten + length > unplugAfterBytesWritten) { isConnected = false; return -1 }
                System.arraycopy(buffer, offset, outBuffer, dataPos, length)
                dataPos += length
                bytesWritten += length
                if (dataPos >= expected) finishWrite()
                length
            }
            else -> -1
        }
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (!isConnected) return -1
        if (haltedIn) return -1
        return when (phase) {
            Phase.DATA_IN -> {
                if (stallNextDataIn > 0) {
                    stallNextDataIn--
                    haltedIn = true
                    residue = expected
                    status = 1
                    setSense(0x0B, 0x00, 0x00) // ABORTED COMMAND
                    phase = Phase.CSW
                    return -1
                }
                val n = minOf(length, dataIn.size - dataPos)
                System.arraycopy(dataIn, dataPos, buffer, offset, n)
                dataPos += n
                if (dataPos >= dataIn.size) { residue = expected - dataIn.size; phase = Phase.CSW }
                n
            }
            Phase.CSW -> {
                require(length >= 13)
                val bb = ByteBuffer.wrap(buffer, offset, 13).order(ByteOrder.LITTLE_ENDIAN)
                bb.putInt(offset, BulkOnlyTransport.CSW_SIGNATURE)
                bb.putInt(offset + 4, tag)
                bb.putInt(offset + 8, residue)
                buffer[offset + 12] = status.toByte()
                phase = Phase.CBW
                13
            }
            else -> -1
        }
    }

    override fun clearHalt(endpointIn: Boolean): Boolean { clearHalts++; if (endpointIn) haltedIn = false; return true }
    override fun massStorageReset(): Boolean { resets++; phase = Phase.CBW; haltedIn = false; return true }
    override fun getMaxLun(): Int = 0
    override fun close() {}

    private fun handleCbw(b: ByteArray, off: Int, len: Int) {
        check(len == 31) { "CBW must be 31 bytes, got $len" }
        val bb = ByteBuffer.wrap(b, off, len).order(ByteOrder.LITTLE_ENDIAN)
        check(bb.getInt(off) == BulkOnlyTransport.CBW_SIGNATURE) { "bad CBW signature" }
        tag = bb.getInt(off + 4)
        expected = bb.getInt(off + 8)
        val dirIn = (b[off + 12].toInt() and 0x80) != 0
        val cdbLen = b[off + 14].toInt()
        val cdb = b.copyOfRange(off + 15, off + 15 + cdbLen)
        val op = cdb[0].toInt() and 0xFF
        commandLog += op
        status = 0; residue = 0; dataPos = 0
        val cbb = ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN)

        fun respond(data: ByteArray) {
            check(dirIn || expected == 0)
            dataIn = if (data.size > expected) data.copyOf(expected) else data
            phase = if (expected > 0) Phase.DATA_IN else Phase.CSW
            if (expected > 0 && dataIn.isEmpty()) { residue = expected; phase = Phase.CSW }
        }
        fun fail(key: Int, asc: Int, ascq: Int = 0) {
            setSense(key, asc, ascq); status = 1; residue = expected
            dataIn = ByteArray(0)
            // Real devices would stall a pending data stage; keep it simple: no data, straight to CSW.
            phase = if (expected > 0 && !dirIn) Phase.DATA_OUT.also { outBuffer = ByteArray(expected); outLba = -1 } else Phase.CSW
            if (dirIn && expected > 0) { haltedIn = false }
        }

        when (op) {
            Scsi.INQUIRY -> respond(ByteArray(36).also {
                it[0] = 0; it[1] = 0x80.toByte()
                vendor.padEnd(8).toByteArray().copyInto(it, 8)
                product.padEnd(16).toByteArray().copyInto(it, 16)
                "1.00".toByteArray().copyInto(it, 32)
            })
            Scsi.TEST_UNIT_READY -> if (unitAttentionCount > 0) { unitAttentionCount--; fail(0x06, 0x28) } else respond(ByteArray(0))
            Scsi.REQUEST_SENSE -> respond(ByteArray(18).also {
                it[0] = 0x70; it[2] = sense[0]; it[7] = 10; it[12] = sense[1]; it[13] = sense[2]
            }.also { setSense(0, 0, 0) })
            Scsi.READ_CAPACITY_10 -> respond(ByteBuffer.allocate(8).putInt((blockCount - 1).toInt()).putInt(blockSize).array())
            Scsi.MODE_SENSE_6 -> respond(byteArrayOf(3, 0, if (writeProtected) 0x80.toByte() else 0, 0))
            Scsi.SYNCHRONIZE_CACHE_10 -> if (supportsSyncCache) respond(ByteArray(0)) else fail(0x05, 0x20)
            Scsi.READ_10, Scsi.WRITE_10 -> {
                val lba = cbb.getInt(2).toLong() and 0xFFFFFFFFL
                val n = cbb.getShort(7).toInt() and 0xFFFF
                check(expected == n * blockSize) { "CBW length $expected != $n blocks" }
                check(lba + n <= blockCount) { "LBA out of range" }
                if (op == Scsi.READ_10) {
                    check(dirIn)
                    respond(disk.copyOfRange((lba * blockSize).toInt(), ((lba + n) * blockSize).toInt()))
                } else if (writeProtected) {
                    fail(0x07, 0x27)
                } else {
                    check(!dirIn)
                    outLba = lba; outBuffer = ByteArray(expected); phase = Phase.DATA_OUT
                }
            }
            Scsi.START_STOP_UNIT -> respond(ByteArray(0))
            else -> fail(0x05, 0x20) // INVALID COMMAND OPERATION CODE
        }
    }

    private fun finishWrite() {
        if (outLba >= 0) System.arraycopy(outBuffer, 0, disk, (outLba * blockSize).toInt(), outBuffer.size)
        phase = Phase.CSW
    }

    private fun setSense(key: Int, asc: Int, ascq: Int) { sense = byteArrayOf(key.toByte(), asc.toByte(), ascq.toByte()) }
}
