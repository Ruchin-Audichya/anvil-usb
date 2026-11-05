package dev.ruchin.anvil.blockdev

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.DeviceDisconnectedException
import dev.ruchin.anvil.common.DeviceWriteProtectedException
import java.io.IOException

/**
 * A USB flash drive accessed as a raw SCSI block device over Bulk-Only Transport.
 * This is what lets Anvil write a whole drive without root: Android only has
 * to grant USB permission; the kernel's usb-storage driver is never involved.
 */
class ScsiBlockDevice private constructor(
    private val usb: UsbBulkTransport,
    private val bot: BulkOnlyTransport,
    val identity: DeviceIdentity,
    override val blockSize: Int,
    override val blockCount: Long,
    /** Bytes moved per READ/WRITE command. 1 MiB is a good speed/latency trade-off on most sticks. */
    private val maxCommandBytes: Int,
) : BlockDevice {

    private val use16ByteCdb = blockCount - 1 > 0xFFFF_FFFFL
    override val maxBlocksPerTransfer: Int =
        (maxCommandBytes / blockSize).coerceIn(1, if (use16ByteCdb) Int.MAX_VALUE else 0xFFFF)

    override fun read(lba: Long, dst: ByteArray, offset: Int, blocks: Int) =
        transfer(write = false, lba, dst, offset, blocks)

    override fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int) {
        if (identity.writeProtected) throw DeviceWriteProtectedException()
        transfer(write = true, lba, src, offset, blocks)
    }

    private fun transfer(write: Boolean, lba: Long, buf: ByteArray, offset: Int, blocks: Int) {
        require(lba >= 0 && blocks >= 0 && lba + blocks <= blockCount) {
            "Out of range: lba=$lba blocks=$blocks count=$blockCount"
        }
        var done = 0
        while (done < blocks) {
            val n = minOf(maxBlocksPerTransfer, blocks - done)
            val curLba = lba + done
            val cdb = if (use16ByteCdb) Scsi.rw16(write, curLba, n) else Scsi.rw10(write, curLba, n)
            val bytes = n * blockSize
            runWithRetry(if (write) "WRITE" else "READ", curLba) {
                val moved = bot.execute(
                    cdb,
                    if (write) DataDirection.OUT else DataDirection.IN,
                    buf, offset + done * blockSize, bytes,
                    dataTimeoutMs = DATA_TIMEOUT_MS,
                )
                if (moved != bytes) throw IOException("Short ${if (write) "write" else "read"}: $moved/$bytes at LBA $curLba")
            }
            done += n
        }
    }

    override fun flush() {
        runWithRetry("SYNCHRONIZE CACHE", 0) {
            try {
                bot.execute(Scsi.synchronizeCache10(), DataDirection.NONE, dataTimeoutMs = DATA_TIMEOUT_MS)
            } catch (e: CommandFailedException) {
                // Many cheap sticks don't implement SYNC CACHE (ILLEGAL REQUEST). They have no write cache either.
                val s = requestSense(bot)
                if (s?.key != 0x05) throw e
            }
        }
    }

    /** Best-effort "safely remove". */
    fun eject() {
        runCatching { bot.execute(Scsi.ejectMedia(), DataDirection.NONE) }
    }

    override fun close() = usb.close()

    private inline fun runWithRetry(what: String, lba: Long, block: () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                block(); return
            } catch (e: DeviceDisconnectedException) {
                throw e
            } catch (e: IOException) {
                if (!usb.isConnected) throw DeviceDisconnectedException("$what @LBA $lba: device gone", e)
                attempt++
                val sense = if (e is CommandFailedException) requestSense(bot) else null
                DebugLog.w(TAG, "$what @LBA $lba failed (attempt $attempt): ${e.message} $sense")
                if (sense?.isWriteProtected == true) throw DeviceWriteProtectedException()
                if (sense?.isMediumNotPresent == true) throw DeviceDisconnectedException("medium not present", e)
                if (attempt >= MAX_RETRIES) {
                    throw AnvilException(
                        userMessage = "The USB drive stopped responding while ${if (what == "READ") "reading" else "writing"}. " +
                            "Try a different OTG adapter or USB drive.",
                        technical = "$what failed at LBA $lba after $attempt attempts: ${e.message} $sense",
                        cause = e,
                    )
                }
                Thread.sleep(200L * attempt)
            }
        }
    }

    companion object {
        private const val TAG = "ScsiBlockDevice"
        private const val MAX_RETRIES = 3
        private const val DATA_TIMEOUT_MS = 30_000

        /**
         * Probes the device: INQUIRY → TEST UNIT READY (with retries; drives report
         * UNIT ATTENTION right after power-up) → READ CAPACITY → MODE SENSE (write-protect).
         */
        fun open(usb: UsbBulkTransport, maxCommandBytes: Int = 1024 * 1024): ScsiBlockDevice {
            val bot = BulkOnlyTransport(usb, lun = 0)
            val buf = ByteArray(256)

            val inquiry = retrying(bot, "INQUIRY") {
                val n = bot.execute(Scsi.inquiry(), DataDirection.IN, buf, 0, Scsi.INQUIRY_LENGTH)
                Scsi.parseInquiry(buf, n)
            }
            if (!inquiry.isDirectAccess) {
                throw AnvilException(
                    "This USB device isn't a storage drive (type ${inquiry.peripheralDeviceType}).",
                )
            }

            waitUntilReady(bot)

            var cap = retrying(bot, "READ CAPACITY(10)") {
                bot.execute(Scsi.readCapacity10(), DataDirection.IN, buf, 0, 8)
                Scsi.parseCapacity10(buf)
            }
            if (cap.lastLba == 0xFFFF_FFFFL) {
                cap = retrying(bot, "READ CAPACITY(16)") {
                    bot.execute(Scsi.readCapacity16(), DataDirection.IN, buf, 0, 32)
                    Scsi.parseCapacity16(buf)
                }
            }
            if (cap.blockSize !in listOf(512, 1024, 2048, 4096)) {
                throw AnvilException("Unsupported USB drive (sector size ${cap.blockSize}).")
            }

            val wp = runCatching {
                val n = bot.execute(Scsi.modeSense6(), DataDirection.IN, buf, 0, 192)
                Scsi.parseWriteProtect(buf, n)
            }.getOrElse {
                requestSense(bot) // clear the CHECK CONDITION some sticks raise for MODE SENSE
                false
            }

            val identity = DeviceIdentity(
                vendor = inquiry.vendor, product = inquiry.product, revision = inquiry.revision,
                removable = inquiry.removable, writeProtected = wp,
            )
            DebugLog.i(TAG, "opened $identity, ${cap.blockCount} x ${cap.blockSize}")
            return ScsiBlockDevice(usb, bot, identity, cap.blockSize, cap.blockCount, maxCommandBytes)
        }

        private fun waitUntilReady(bot: BulkOnlyTransport) {
            repeat(20) { attempt ->
                try {
                    bot.execute(Scsi.testUnitReady(), DataDirection.NONE)
                    return
                } catch (e: CommandFailedException) {
                    val sense = requestSense(bot)
                    DebugLog.i(TAG, "TEST UNIT READY #$attempt: $sense")
                    if (sense?.isMediumNotPresent == true) {
                        throw AnvilException("No media in this USB device. Is it a card reader without a card?")
                    }
                    Thread.sleep(if (sense?.isUnitAttention == true) 50 else 250)
                }
            }
            throw AnvilException("The USB drive never became ready. Re-plug it and try again.")
        }

        internal fun requestSense(bot: BulkOnlyTransport): Scsi.Sense? = runCatching {
            val b = ByteArray(Scsi.SENSE_LENGTH)
            bot.execute(Scsi.requestSense(), DataDirection.IN, b, 0, b.size)
            Scsi.parseSense(b)
        }.getOrNull()

        private inline fun <T> retrying(bot: BulkOnlyTransport, what: String, block: () -> T): T {
            var last: Exception? = null
            repeat(3) {
                try { return block() } catch (e: DeviceDisconnectedException) { throw e } catch (e: IOException) {
                    last = e
                    val sense = if (e is CommandFailedException) requestSense(bot) else null
                    DebugLog.w(TAG, "$what failed: ${e.message} $sense")
                }
            }
            throw AnvilException("Couldn't talk to the USB drive ($what failed).", cause = last)
        }
    }
}
