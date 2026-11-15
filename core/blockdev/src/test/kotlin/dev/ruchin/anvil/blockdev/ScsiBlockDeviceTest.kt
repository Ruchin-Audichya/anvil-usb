package dev.ruchin.anvil.blockdev

import dev.ruchin.anvil.common.DeviceDisconnectedException
import dev.ruchin.anvil.common.DeviceWriteProtectedException
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class ScsiBlockDeviceTest {

    @Test
    fun `open probes identity and capacity`() {
        val stick = FakeUsbStick(blockCount = 4096, vendor = "Kingston", product = "DataTraveler")
        val dev = ScsiBlockDevice.open(stick)
        assertThat(dev.blockSize).isEqualTo(512)
        assertThat(dev.blockCount).isEqualTo(4096)
        assertThat(dev.identity.displayName).isEqualTo("Kingston DataTraveler")
        assertThat(dev.identity.removable).isTrue()
        assertThat(dev.identity.writeProtected).isFalse()
        // INQUIRY, TUR (unit attention), REQUEST SENSE, TUR, READ CAPACITY, MODE SENSE
        assertThat(stick.commandLog).containsAtLeast(0x12, 0x00, 0x03, 0x25, 0x1A).inOrder()
    }

    @Test
    fun `write then read round-trips across multiple commands and usb chunks`() {
        val stick = FakeUsbStick(blockCount = 4096, maxTransferSize = 4096)
        val dev = ScsiBlockDevice.open(stick, maxCommandBytes = 64 * 1024)
        val data = Random(42).nextBytes(512 * 1000)
        dev.write(17, data, 0, 1000)
        assertThat(stick.disk.copyOfRange(17 * 512, 1017 * 512)).isEqualTo(data)
        val back = ByteArray(data.size)
        dev.read(17, back, 0, 1000)
        assertThat(back).isEqualTo(data)
        // 1000 blocks / 128 blocks per command = 8 WRITE(10) commands
        assertThat(stick.commandLog.count { it == Scsi.WRITE_10 }).isEqualTo(8)
    }

    @Test
    fun `flush tolerates devices without SYNCHRONIZE CACHE`() {
        val dev = ScsiBlockDevice.open(FakeUsbStick(supportsSyncCache = false))
        dev.flush() // must not throw
    }

    @Test
    fun `write-protected stick is reported and refused`() {
        val dev = ScsiBlockDevice.open(FakeUsbStick(writeProtected = true))
        assertThat(dev.identity.writeProtected).isTrue()
        assertThrows<DeviceWriteProtectedException> { dev.write(0, ByteArray(512), 0, 1) }
    }

    @Test
    fun `stalled read is recovered by clear-halt and retry`() {
        val stick = FakeUsbStick()
        val dev = ScsiBlockDevice.open(stick)
        stick.disk.fill(7, 0, 512)
        stick.stallNextDataIn = 1
        val buf = ByteArray(512)
        dev.read(0, buf, 0, 1)
        assertThat(buf.all { it == 7.toByte() }).isTrue()
        assertThat(stick.clearHalts).isAtLeast(1)
    }

    @Test
    fun `unplug mid-write surfaces DeviceDisconnectedException`() {
        val stick = FakeUsbStick(blockCount = 4096)
        val dev = ScsiBlockDevice.open(stick)
        stick.unplugAfterBytesWritten = 100_000
        assertThrows<DeviceDisconnectedException> { dev.write(0, ByteArray(512 * 1024), 0, 1024) }
    }

    @Test
    fun `cdb encoding`() {
        val cdb = Scsi.rw10(write = true, lba = 0x01020304, blocks = 0x0506)
        assertThat(cdb.map { it.toInt() and 0xFF }).containsExactly(0x2A, 0, 1, 2, 3, 4, 0, 5, 6, 0).inOrder()
        val cbw = ByteArray(31)
        BulkOnlyTransport.encodeCbw(cbw, tag = 7, length = 512, direction = DataDirection.IN, lun = 0, cdb = Scsi.inquiry())
        assertThat(String(cbw.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("USBC")
        assertThat(cbw[12]).isEqualTo(0x80.toByte())
        assertThat(cbw[14]).isEqualTo(6.toByte())
    }

    @Test
    fun `sense parsing fixed and descriptor formats`() {
        val fixed = ByteArray(18).also { it[0] = 0x70; it[2] = 0x07; it[12] = 0x27 }
        assertThat(Scsi.parseSense(fixed).isWriteProtected).isTrue()
        val desc = byteArrayOf(0x72, 0x02, 0x3A, 0x00)
        assertThat(Scsi.parseSense(desc).isMediumNotPresent).isTrue()
    }
}

class UsbTuningTest {
    @Test
    fun `compatibility mode uses 16 KiB transfers and 64 KiB commands`() {
        assertThat(UsbTuning.forAndroid(35, compatibilityMode = true)).isEqualTo(UsbTuning(16 * 1024, 64 * 1024))
        assertThat(UsbTuning.forAndroid(35, compatibilityMode = false)).isEqualTo(UsbTuning.DEFAULT)
        assertThat(UsbTuning.forAndroid(26, compatibilityMode = false).maxTransferBytes).isEqualTo(16 * 1024)
    }

    @Test
    fun `a compatibility-mode device round-trips data in small commands`() {
        val tuning = UsbTuning.COMPATIBILITY
        val stick = FakeUsbStick(blockCount = 4096, maxTransferSize = tuning.maxTransferBytes)
        val dev = ScsiBlockDevice.open(stick, maxCommandBytes = tuning.maxCommandBytes)
        val data = Random(3).nextBytes(512 * 1000)
        dev.write(5, data, 0, 1000)
        val back = ByteArray(data.size)
        dev.read(5, back, 0, 1000)
        assertThat(back).isEqualTo(data)
        // 64 KiB per command = 128 blocks: 1000 blocks need 8 WRITE(10) commands
        assertThat(dev.maxBlocksPerTransfer).isEqualTo(128)
        assertThat(stick.commandLog.count { it == Scsi.WRITE_10 }).isEqualTo(8)
    }
}
