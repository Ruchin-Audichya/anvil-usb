package dev.ruchin.anvil.blockdev

/**
 * How big each USB transfer and each SCSI READ/WRITE command may be.
 *
 * The default favours speed. **Compatibility mode** uses 16 KiB bulk transfers and 64 KiB SCSI
 * commands: slower, but it gets along with OEM kernels that mishandle large `bulkTransfer` calls,
 * cheap OTG hubs, and sticks whose controllers choke on big commands (Linux's usb-storage caps many
 * of them at 120 KiB for the same reason).
 */
data class UsbTuning(val maxTransferBytes: Int, val maxCommandBytes: Int) {
    init {
        require(maxTransferBytes > 0 && maxCommandBytes >= maxTransferBytes) { "bad tuning $this" }
    }

    companion object {
        val DEFAULT = UsbTuning(maxTransferBytes = 128 * 1024, maxCommandBytes = 1024 * 1024)
        val COMPATIBILITY = UsbTuning(maxTransferBytes = 16 * 1024, maxCommandBytes = 64 * 1024)

        /** Android before 9 silently capped `bulkTransfer` at 16 KiB, so it gets small transfers either way. */
        fun forAndroid(sdkInt: Int, compatibilityMode: Boolean): UsbTuning = when {
            compatibilityMode -> COMPATIBILITY
            sdkInt < 28 -> DEFAULT.copy(maxTransferBytes = 16 * 1024)
            else -> DEFAULT
        }
    }
}
