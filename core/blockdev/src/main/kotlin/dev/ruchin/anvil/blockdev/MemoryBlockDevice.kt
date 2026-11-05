package dev.ruchin.anvil.blockdev

/**
 * In-memory block device. Used by unit tests and by the "fake drive" developer
 * option, so the full write pipeline can be exercised without hardware.
 */
class MemoryBlockDevice(
    override val blockSize: Int = 512,
    override val blockCount: Long,
) : BlockDevice {
    init {
        require(blockSize.toLong() * blockCount <= Int.MAX_VALUE) { "MemoryBlockDevice limited to 2 GiB" }
    }

    val data = ByteArray((blockSize * blockCount).toInt())
    var writeCalls = 0; private set
    var flushCalls = 0; private set
    @Volatile var failAfterBytesWritten: Long = Long.MAX_VALUE
    private var bytesWritten = 0L

    override fun read(lba: Long, dst: ByteArray, offset: Int, blocks: Int) {
        check(lba, blocks)
        System.arraycopy(data, (lba * blockSize).toInt(), dst, offset, blocks * blockSize)
    }

    override fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int) {
        check(lba, blocks)
        bytesWritten += blocks.toLong() * blockSize
        if (bytesWritten > failAfterBytesWritten) {
            throw dev.ruchin.anvil.common.DeviceDisconnectedException("simulated unplug at $bytesWritten bytes")
        }
        writeCalls++
        System.arraycopy(src, offset, data, (lba * blockSize).toInt(), blocks * blockSize)
    }

    override fun flush() { flushCalls++ }
    override fun close() {}

    private fun check(lba: Long, blocks: Int) {
        require(lba >= 0 && blocks >= 0 && lba + blocks <= blockCount) {
            "Out of range: lba=$lba blocks=$blocks count=$blockCount"
        }
    }
}
