package dev.ruchin.anvil.blockdev

import dev.ruchin.anvil.common.VerificationFailedException
import dev.ruchin.anvil.common.sha256
import dev.ruchin.anvil.common.toHex
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Wraps a device, hashing every block written (in write order) and remembering
 * where it went. [verify] re-reads the same ranges in the same order and
 * compares digests — catching bad flash, fake-capacity drives and bus errors
 * for file-system writes the way a whole-image hash does for raw writes.
 *
 * Requires that no region is written twice (true for Anvil's single-pass writers).
 */
class RecordingBlockDevice(private val inner: BlockDevice) : BlockDevice by inner {
    private val digest: MessageDigest = sha256()
    private val ranges = ArrayList<LongArray>() // [lba, blocks], merged when contiguous
    var bytesRecorded = 0L; private set

    override fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int) {
        inner.write(lba, src, offset, blocks)
        digest.update(src, offset, blocks * blockSize)
        bytesRecorded += blocks.toLong() * blockSize
        val last = ranges.lastOrNull()
        if (last != null && last[0] + last[1] == lba) last[1] += blocks.toLong() else ranges += longArrayOf(lba, blocks.toLong())
    }

    fun verify(bufferBytes: Int = 4 * 1024 * 1024, checkCancelled: () -> Unit = {}, onBytes: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val expected = digest.clone().let { (it as MessageDigest).digest().toHex() }
        val check = sha256()
        val maxBlocks = (bufferBytes / blockSize).coerceAtLeast(1)
        val buf = ByteArray(maxBlocks * blockSize)
        var done = 0L
        for (r in ranges) {
            var lba = r[0]
            var left = r[1]
            while (left > 0) {
                checkCancelled()
                val n = minOf(left, maxBlocks.toLong()).toInt()
                var off = 0
                while (off < n) {
                    val k = minOf(inner.maxBlocksPerTransfer, n - off)
                    inner.read(lba + off, buf, off * blockSize, k)
                    off += k
                }
                check.update(buf, 0, n * blockSize)
                lba += n; left -= n
                done += n.toLong() * blockSize
                onBytes(done, bytesRecorded)
            }
        }
        val actual = check.digest().toHex()
        if (actual != expected) throw VerificationFailedException("read-back sha256 $actual != written $expected over ${ranges.size} ranges")
    }
}

/** A disk-image file pretending to be a USB stick (sparse; for the dev CLI, tests and QEMU boot checks). */
class FileBlockDevice(file: File, sizeBytes: Long, override val blockSize: Int = 512) : BlockDevice {
    private val raf = RandomAccessFile(file, "rw").apply { if (length() != sizeBytes) setLength(sizeBytes) }
    override val blockCount: Long = sizeBytes / blockSize
    override val maxBlocksPerTransfer: Int get() = 1 shl 16

    @Synchronized override fun read(lba: Long, dst: ByteArray, offset: Int, blocks: Int) {
        raf.seek(lba * blockSize); raf.readFully(dst, offset, blocks * blockSize)
    }
    @Synchronized override fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int) {
        require(lba >= 0 && lba + blocks <= blockCount) { "write out of range lba=$lba blocks=$blocks" }
        raf.seek(lba * blockSize); raf.write(src, offset, blocks * blockSize)
    }
    override fun flush() { raf.fd.sync() }
    override fun close() = raf.close()
}
