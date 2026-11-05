package dev.ruchin.anvil.writer

import dev.ruchin.anvil.blockdev.BlockDevice
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.DeviceTooSmallException
import dev.ruchin.anvil.common.TransferMeter
import dev.ruchin.anvil.common.VerificationFailedException
import dev.ruchin.anvil.common.sha256
import dev.ruchin.anvil.common.toHex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

enum class WriteStage { PREPARING, CLEARING, WRITING, FLUSHING, VERIFYING, DONE }

data class WriteProgress(
    val stage: WriteStage,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

data class WriteResult(val bytesWritten: Long, val sha256: String, val verified: Boolean, val durationMs: Long)

/**
 * dd-style writer for hybrid ISOs and disk images.
 *
 * Reading the source and writing USB run concurrently (producer/consumer with a
 * small buffer pool) so a slow SAF/network read never idles the USB bus. The
 * SHA-256 of the source is computed on the fly; verification reads the drive
 * back and compares digests, which also catches fake-capacity drives that
 * silently wrap around.
 */
class RawImageWriter(
    private val bufferBytes: Int = 4 * 1024 * 1024,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val meterFactory: () -> TransferMeter = { TransferMeter() },
    private val progressIntervalNanos: Long = 200_000_000L,
) {

    suspend fun write(
        source: ImageSource,
        device: BlockDevice,
        verify: Boolean = true,
        onProgress: (WriteProgress) -> Unit = {},
    ): WriteResult = withContext(ioDispatcher) {
        val started = System.nanoTime()
        val total = source.sizeBytes
        val bs = device.blockSize
        require(bufferBytes % bs == 0) { "buffer must be a multiple of the block size" }
        if (total <= 0) throw AnvilException("The image file is empty or its size is unknown.")
        if (total > device.sizeBytes) throw DeviceTooSmallException(total, device.sizeBytes)
        onProgress(WriteProgress(WriteStage.PREPARING, 0, total))
        DebugLog.i(TAG, "raw write ${source.displayName}: $total bytes to ${device.sizeBytes}-byte device")

        clearTail(device, total, onProgress)

        val sourceHash = writeAll(source, device, total, onProgress)
        ensureActive()

        onProgress(WriteProgress(WriteStage.FLUSHING, total, total))
        device.flush()

        if (verify) {
            val readBack = readBackHash(device, total, onProgress)
            if (readBack != sourceHash) {
                throw VerificationFailedException("sha256 mismatch: source=$sourceHash device=$readBack")
            }
        }
        onProgress(WriteProgress(WriteStage.DONE, total, total))
        WriteResult(total, sourceHash, verify, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * Zero the last MiB of the drive when it lies beyond the image. That's where a
     * previous GPT's backup header lives; leaving it makes Windows/firmware see a
     * "corrupt" GPT on the freshly written stick.
     */
    private fun clearTail(device: BlockDevice, imageBytes: Long, onProgress: (WriteProgress) -> Unit) {
        val bs = device.blockSize.toLong()
        val tailBlocks = (1024 * 1024 / bs).coerceAtMost(device.blockCount)
        val firstTail = device.blockCount - tailBlocks
        val imageEndBlock = (imageBytes + bs - 1) / bs
        val start = maxOf(firstTail, imageEndBlock)
        val count = (device.blockCount - start).toInt()
        if (count <= 0) return
        onProgress(WriteProgress(WriteStage.CLEARING, 0, imageBytes))
        device.write(start, ByteArray(count * device.blockSize), 0, count)
    }

    private class Chunk(val buf: ByteArray, var len: Int)

    private suspend fun writeAll(
        source: ImageSource,
        device: BlockDevice,
        total: Long,
        onProgress: (WriteProgress) -> Unit,
    ): String = coroutineScope {
        val free = Channel<Chunk>(POOL)
        val filled = Channel<Chunk>(POOL)
        repeat(POOL) { free.trySend(Chunk(ByteArray(bufferBytes), 0)) }
        val digest = sha256()

        // Producer: source -> buffers (+ hashing)
        val producer = launch(ioDispatcher) {
            source.open().use { input ->
                var remaining = total
                while (remaining > 0) {
                    ensureActive()
                    val chunk = free.receive()
                    val want = minOf(bufferBytes.toLong(), remaining).toInt()
                    val got = readFully(input, chunk.buf, want)
                    if (got < want) {
                        throw AnvilException(
                            "The image file ended early (${total - remaining + got} of $total bytes). It may be incomplete — download it again.",
                        )
                    }
                    digest.update(chunk.buf, 0, got)
                    chunk.len = got
                    remaining -= got
                    filled.send(chunk)
                }
            }
            filled.close()
        }

        // Consumer: buffers -> USB
        val meter = meterFactory().also { it.reset() }
        val bs = device.blockSize
        var lba = 0L
        var written = 0L
        var lastEmit = 0L
        for (chunk in filled) {
            ensureActive()
            var len = chunk.len
            if (len % bs != 0) { // last partial block: zero-pad
                val padded = ((len + bs - 1) / bs) * bs
                chunk.buf.fill(0, len, padded)
                len = padded
            }
            val blocks = len / bs
            var off = 0
            while (off < blocks) {
                val n = minOf(device.maxBlocksPerTransfer, blocks - off)
                device.write(lba + off, chunk.buf, off * bs, n)
                off += n
            }
            lba += blocks
            written += chunk.len
            free.send(chunk)

            val now = System.nanoTime()
            if (now - lastEmit >= progressIntervalNanos || written == total) {
                lastEmit = now
                val rate = meter.update(written)
                onProgress(WriteProgress(WriteStage.WRITING, written, total, rate, meter.etaSeconds(written, total)))
            }
        }
        producer.join()
        digest.digest().toHex()
    }

    private suspend fun readBackHash(device: BlockDevice, total: Long, onProgress: (WriteProgress) -> Unit): String {
        val bs = device.blockSize
        val buf = ByteArray(bufferBytes)
        val digest = sha256()
        val meter = meterFactory().also { it.reset() }
        var done = 0L
        var lba = 0L
        var lastEmit = 0L
        onProgress(WriteProgress(WriteStage.VERIFYING, 0, total))
        while (done < total) {
            currentCoroutineContext().ensureActive()
            val want = minOf(bufferBytes.toLong(), total - done).toInt()
            val blocks = (want + bs - 1) / bs
            var off = 0
            while (off < blocks) {
                val n = minOf(device.maxBlocksPerTransfer, blocks - off)
                device.read(lba + off, buf, off * bs, n)
                off += n
            }
            digest.update(buf, 0, want)
            lba += blocks
            done += want
            val now = System.nanoTime()
            if (now - lastEmit >= progressIntervalNanos || done == total) {
                lastEmit = now
                val rate = meter.update(done)
                onProgress(WriteProgress(WriteStage.VERIFYING, done, total, rate, meter.etaSeconds(done, total)))
            }
        }
        return digest.digest().toHex()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = try { input.read(buf, total, len - total) } catch (e: IOException) {
                throw AnvilException("Couldn't read the image file: ${e.message}", cause = e)
            }
            if (r < 0) break
            total += r
        }
        return total
    }

    companion object {
        private const val TAG = "RawImageWriter"
        private const val POOL = 3
    }
}
