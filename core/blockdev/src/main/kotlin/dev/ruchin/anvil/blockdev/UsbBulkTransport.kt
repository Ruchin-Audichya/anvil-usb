package dev.ruchin.anvil.blockdev

import java.io.Closeable

/**
 * The minimal set of USB operations the Bulk-Only Transport needs. On Android
 * this is implemented on top of UsbDeviceConnection (no root, no kernel driver:
 * we claim the interface and speak the protocol ourselves). In tests it's a
 * simulated USB stick.
 *
 * Return values follow UsbDeviceConnection.bulkTransfer: bytes transferred, or
 * a negative value on error/stall/timeout.
 */
interface UsbBulkTransport : Closeable {
    /** Largest single bulk transfer the host stack accepts (16 KiB before Android 9). */
    val maxTransferSize: Int

    fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** CLEAR_FEATURE(ENDPOINT_HALT) on the IN or OUT endpoint. */
    fun clearHalt(endpointIn: Boolean): Boolean

    /** Class-specific Bulk-Only Mass Storage Reset (bRequest 0xFF). */
    fun massStorageReset(): Boolean

    /** Class-specific Get Max LUN (bRequest 0xFE). Returns 0 if the device stalls it. */
    fun getMaxLun(): Int

    /** False once the device has been detached. */
    val isConnected: Boolean
}
