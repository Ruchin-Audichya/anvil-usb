package dev.ruchin.anvil.blockdev

import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.DeviceDisconnectedException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Direction of the data stage of a SCSI command. */
enum class DataDirection { NONE, IN, OUT }

class CommandFailedException(val status: Int, val residue: Int) :
    IOException("SCSI command failed: CSW status=$status residue=$residue")

class PhaseErrorException(msg: String) : IOException(msg)

/**
 * USB Mass Storage Class, Bulk-Only Transport (BOT) — "USB MSC BOT 1.0" spec.
 *
 * Every command is three stages:
 *   1. CBW  (31 bytes, host→device)  wraps a SCSI CDB
 *   2. data (optional, either direction)
 *   3. CSW  (13 bytes, device→host)  status
 *
 * Error recovery follows spec §5.3.4 / §6.6: on a STALL clear the halt and read
 * the CSW; on a phase error or garbage CSW do a full Reset Recovery.
 */
class BulkOnlyTransport(
    private val usb: UsbBulkTransport,
    val lun: Int = 0,
    private val timeoutMs: Int = 10_000,
) {
    private var tag = 1
    private val cbw = ByteArray(CBW_LENGTH)
    private val csw = ByteArray(CSW_LENGTH)

    /**
     * Executes one SCSI command.
     * @return number of bytes actually transferred in the data stage.
     * @throws CommandFailedException when the device reports CHECK CONDITION (caller should REQUEST SENSE).
     */
    fun execute(
        cdb: ByteArray,
        direction: DataDirection,
        buffer: ByteArray? = null,
        offset: Int = 0,
        length: Int = 0,
        dataTimeoutMs: Int = timeoutMs,
    ): Int {
        ensureConnected()
        val currentTag = nextTag()
        encodeCbw(cbw, currentTag, length, direction, lun, cdb)
        if (!transferFully(true, cbw, 0, CBW_LENGTH, timeoutMs)) {
            resetRecovery()
            throw IOException("Failed to send CBW (tag=$currentTag, op=0x%02x)".format(cdb[0]))
        }

        var transferred = 0
        if (direction != DataDirection.NONE && length > 0) {
            transferred = dataStage(direction == DataDirection.OUT, buffer!!, offset, length, dataTimeoutMs)
        }

        val status = readCsw(currentTag)
        when (status.status) {
            CSW_STATUS_PASSED -> return transferred
            CSW_STATUS_FAILED -> throw CommandFailedException(status.status, status.residue)
            else -> {
                resetRecovery()
                throw PhaseErrorException("Phase error (op=0x%02x)".format(cdb[0]))
            }
        }
    }

    /** Moves the data stage in max-transfer-size chunks; stops early on short packet or stall. */
    private fun dataStage(out: Boolean, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        var done = 0
        val chunk = usb.maxTransferSize
        while (done < length) {
            val n = minOf(chunk, length - done)
            val r = if (out) usb.bulkOut(buffer, offset + done, n, timeout)
            else usb.bulkIn(buffer, offset + done, n, timeout)
            if (r < 0) {
                // STALL (or timeout). Spec: clear the halt on that pipe, then read CSW.
                ensureConnected()
                DebugLog.w(TAG, "data stage ${if (out) "OUT" else "IN"} stalled at $done/$length")
                usb.clearHalt(endpointIn = !out)
                return done
            }
            done += r
            if (r < n) break // short packet: device ended the data stage
        }
        return done
    }

    private data class Csw(val status: Int, val residue: Int)

    private fun readCsw(expectedTag: Int): Csw {
        var attempts = 0
        while (true) {
            val r = usb.bulkIn(csw, 0, CSW_LENGTH, timeoutMs)
            if (r == CSW_LENGTH) break
            ensureConnected()
            attempts++
            if (r < 0 && attempts < 2) {
                // Stalled CSW: clear halt and retry once (spec figure 2).
                usb.clearHalt(endpointIn = true)
                continue
            }
            resetRecovery()
            throw IOException("Invalid CSW length $r")
        }
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = bb.getInt(0)
        val tag = bb.getInt(4)
        if (sig != CSW_SIGNATURE || tag != expectedTag) {
            resetRecovery()
            throw PhaseErrorException("Bad CSW: sig=0x%08x tag=%d expected=%d".format(sig, tag, expectedTag))
        }
        return Csw(status = csw[12].toInt() and 0xFF, residue = bb.getInt(8))
    }

    private fun transferFully(out: Boolean, buf: ByteArray, off: Int, len: Int, timeout: Int): Boolean {
        val r = if (out) usb.bulkOut(buf, off, len, timeout) else usb.bulkIn(buf, off, len, timeout)
        return r == len
    }

    /** Spec §5.3.4: Bulk-Only Mass Storage Reset, then clear HALT on both endpoints. */
    fun resetRecovery() {
        DebugLog.w(TAG, "reset recovery")
        ensureConnected()
        usb.massStorageReset()
        usb.clearHalt(endpointIn = true)
        usb.clearHalt(endpointIn = false)
    }

    private fun ensureConnected() {
        if (!usb.isConnected) throw DeviceDisconnectedException("USB device detached")
    }

    private fun nextTag(): Int {
        tag = if (tag == Int.MAX_VALUE) 1 else tag + 1
        return tag
    }

    companion object {
        private const val TAG = "BOT"
        const val CBW_LENGTH = 31
        const val CSW_LENGTH = 13
        const val CBW_SIGNATURE = 0x43425355 // "USBC"
        const val CSW_SIGNATURE = 0x53425355 // "USBS"
        const val CSW_STATUS_PASSED = 0
        const val CSW_STATUS_FAILED = 1
        const val CSW_STATUS_PHASE_ERROR = 2

        fun encodeCbw(out: ByteArray, tag: Int, length: Int, direction: DataDirection, lun: Int, cdb: ByteArray) {
            require(cdb.size in 1..16) { "CDB length ${cdb.size}" }
            out.fill(0)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(0, CBW_SIGNATURE)
            bb.putInt(4, tag)
            bb.putInt(8, length)
            out[12] = if (direction == DataDirection.IN) 0x80.toByte() else 0
            out[13] = (lun and 0x0F).toByte()
            out[14] = cdb.size.toByte()
            System.arraycopy(cdb, 0, out, 15, cdb.size)
        }
    }
}
