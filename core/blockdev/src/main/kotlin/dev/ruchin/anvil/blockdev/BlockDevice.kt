package dev.ruchin.anvil.blockdev

import java.io.Closeable

/**
 * A raw, LBA-addressed block device. Everything above this layer (raw image
 * writer, partitioner, FAT32 formatter) talks only to this interface, so it runs
 * identically against a real USB stick or an in-memory fake in tests.
 */
interface BlockDevice : Closeable {
    val blockSize: Int
    val blockCount: Long
    val sizeBytes: Long get() = blockSize.toLong() * blockCount

    /** Max blocks per single [read]/[write] call the device handles efficiently. */
    val maxBlocksPerTransfer: Int get() = 65535

    fun read(lba: Long, dst: ByteArray, offset: Int, blocks: Int)
    fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int)

    /** Make sure everything written is on the medium (SYNCHRONIZE CACHE). */
    fun flush()
}

/** Static information about a block device, for display and safety checks. */
data class DeviceIdentity(
    val vendor: String,
    val product: String,
    val revision: String,
    val removable: Boolean,
    val writeProtected: Boolean,
) {
    val displayName: String
        get() = listOf(vendor, product).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "USB drive" }
}
