package dev.ruchin.anvil.writer

import dev.ruchin.anvil.blockdev.BlockDevice
import dev.ruchin.anvil.blockdev.MemoryBlockDevice
import dev.ruchin.anvil.common.DeviceDisconnectedException
import dev.ruchin.anvil.common.DeviceTooSmallException
import dev.ruchin.anvil.common.VerificationFailedException
import dev.ruchin.anvil.common.sha256
import dev.ruchin.anvil.common.toHex
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class RawImageWriterTest {
    private val writer = RawImageWriter(bufferBytes = 64 * 1024, ioDispatcher = Dispatchers.Default)

    @Test
    fun `writes image byte-for-byte, pads last block, verifies`() = runBlocking {
        val image = Random(1).nextBytes(300_000 + 123) // not block aligned
        val dev = MemoryBlockDevice(blockCount = 4096)
        dev.data.fill(0x5A)
        val stages = mutableListOf<WriteStage>()
        val result = writer.write(ByteArrayImageSource(image), dev) { stages += it.stage }

        assertThat(dev.data.copyOfRange(0, image.size)).isEqualTo(image)
        // padding after the image within the last block is zeroed
        val lastBlockEnd = ((image.size + 511) / 512) * 512
        assertThat(dev.data.copyOfRange(image.size, lastBlockEnd).all { it == 0.toByte() }).isTrue()
        // stale backup-GPT area at the end of the drive is cleared
        assertThat(dev.data.copyOfRange(dev.data.size - 1024 * 1024, dev.data.size).all { it == 0.toByte() }).isTrue()
        assertThat(result.sha256).isEqualTo(sha256().digest(image).toHex())
        assertThat(result.verified).isTrue()
        assertThat(stages).containsAtLeast(WriteStage.PREPARING, WriteStage.WRITING, WriteStage.FLUSHING, WriteStage.VERIFYING, WriteStage.DONE).inOrder()
        assertThat(dev.flushCalls).isEqualTo(1)
    }

    @Test
    fun `refuses drive that is too small before writing anything`() {
        val dev = MemoryBlockDevice(blockCount = 16)
        assertThrows<DeviceTooSmallException> {
            runBlocking { writer.write(ByteArrayImageSource(ByteArray(10_000)), dev) }
        }
        assertThat(dev.writeCalls).isEqualTo(0)
    }

    @Test
    fun `detects fake-capacity drive via verification`() {
        // A counterfeit drive: reports 4096 blocks but only stores 256, wrapping addresses.
        val fake = object : BlockDevice {
            val real = MemoryBlockDevice(blockCount = 256)
            override val blockSize = 512
            override val blockCount = 4096L
            override fun read(lba: Long, dst: ByteArray, offset: Int, blocks: Int) {
                for (i in 0 until blocks) real.read((lba + i) % 256, dst, offset + i * 512, 1)
            }
            override fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int) {
                for (i in 0 until blocks) real.write((lba + i) % 256, src, offset + i * 512, 1)
            }
            override fun flush() {}
            override fun close() {}
        }
        assertThrows<VerificationFailedException> {
            runBlocking { writer.write(ByteArrayImageSource(Random(2).nextBytes(512 * 1000)), fake) }
        }
    }

    @Test
    fun `unplug mid-write propagates`() {
        val dev = MemoryBlockDevice(blockCount = 4096).apply { failAfterBytesWritten = 1_100_000 }
        assertThrows<DeviceDisconnectedException> {
            runBlocking { writer.write(ByteArrayImageSource(ByteArray(1_500_000)), dev) }
        }
    }
}
