package dev.ruchin.anvil.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import dev.ruchin.anvil.blockdev.UsbBulkTransport
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog

/**
 * Bulk-Only Transport pipe over Android's USB Host API. We claim the mass-storage
 * interface with force=true, which detaches the kernel driver (if the phone had
 * auto-mounted the stick) — this is what makes raw writes possible without root.
 */
class AndroidUsbTransport private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : UsbBulkTransport {

    // Before Android 9, UsbDeviceConnection.bulkTransfer silently capped transfers at 16 KiB.
    override val maxTransferSize: Int = if (Build.VERSION.SDK_INT >= 28) 128 * 1024 else 16 * 1024

    @Volatile private var closed = false

    override val isConnected: Boolean
        get() = !closed && manager.deviceList.values.any { it.deviceName == device.deviceName }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(outEp, buffer, offset, length, timeoutMs)

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(inEp, buffer, offset, length, timeoutMs)

    override fun clearHalt(endpointIn: Boolean): Boolean {
        val ep = if (endpointIn) inEp else outEp
        // CLEAR_FEATURE(ENDPOINT_HALT): bmRequestType=0x02 (standard, endpoint), bRequest=1, wValue=0, wIndex=ep address
        return connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, CONTROL_TIMEOUT) >= 0
    }

    override fun massStorageReset(): Boolean =
        // bmRequestType=0x21 (class, interface, host->device), bRequest=0xFF
        connection.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, CONTROL_TIMEOUT) >= 0

    override fun getMaxLun(): Int {
        val b = ByteArray(1)
        val r = connection.controlTransfer(0xA1, 0xFE, 0, intf.id, b, 1, CONTROL_TIMEOUT)
        return if (r == 1) b[0].toInt() and 0x0F else 0
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
    }

    companion object {
        private const val CONTROL_TIMEOUT = 5_000

        /** Mass Storage (class 8) / SCSI transparent (subclass 6) / Bulk-Only (protocol 0x50) interface, or null. */
        fun findMassStorageInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol == 0x50 &&
                    (it.interfaceSubclass == 6 || it.interfaceSubclass == 0) // some sticks report 0
            }

        fun open(manager: UsbManager, device: UsbDevice): AndroidUsbTransport {
            val intf = findMassStorageInterface(device)
                ?: throw AnvilException("This USB device isn't a USB flash drive (no mass-storage interface).")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) throw AnvilException("Unsupported USB drive (missing bulk endpoints).")
            val conn = manager.openDevice(device)
                ?: throw AnvilException("Android refused to open the USB drive. Unplug it, plug it back in and allow access.")
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw AnvilException("Couldn't take control of the USB drive. Close other apps using it and re-plug.")
            }
            DebugLog.i("UsbTransport", "opened ${device.deviceName} vid=%04x pid=%04x in=0x%02x(%d) out=0x%02x(%d)".format(
                device.vendorId, device.productId, inEp.address, inEp.maxPacketSize, outEp.address, outEp.maxPacketSize,
            ))
            return AndroidUsbTransport(manager, device, conn, intf, inEp, outEp)
        }
    }
}
