package dev.ruchin.anvil.image

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ImageInspectorTest {

    private fun iso(label: String, udf: Boolean = false, mbr: Boolean = false, gpt: Boolean = false): ByteArray {
        val b = ByteArray(ImageInspector.HEADER_BYTES)
        val pvd = 16 * 2048
        b[pvd] = 1; "CD001".toByteArray().copyInto(b, pvd + 1)
        label.padEnd(32).toByteArray().copyInto(b, pvd + 40)
        val br = 17 * 2048
        b[br] = 0; "CD001".toByteArray().copyInto(b, br + 1); "EL TORITO SPECIFICATION".toByteArray().copyInto(b, br + 7)
        val term = 18 * 2048
        b[term] = 0xFF.toByte(); "CD001".toByteArray().copyInto(b, term + 1)
        if (udf) {
            "BEA01".toByteArray().copyInto(b, 19 * 2048 + 1)
            "NSR02".toByteArray().copyInto(b, 20 * 2048 + 1)
            "TEA01".toByteArray().copyInto(b, 21 * 2048 + 1)
        }
        if (mbr) {
            b[446] = 0x80.toByte(); b[446 + 4] = 0x17; b[446 + 12] = 0x10
            b[510] = 0x55; b[511] = 0xAA.toByte()
        }
        if (gpt) "EFI PART".toByteArray().copyInto(b, 512)
        return b
    }

    @Test fun `ubuntu hybrid iso is raw writable`() {
        val info = ImageInspector.inspect(iso("Ubuntu 24.04.3 LTS amd64", mbr = true, gpt = true))
        assertThat(info.kind).isEqualTo(ImageKind.HYBRID_ISO)
        assertThat(info.rawWritable).isTrue()
        assertThat(info.volumeLabel).isEqualTo("Ubuntu 24.04.3 LTS amd64")
        assertThat(info.hasElTorito).isTrue()
    }

    @Test fun `windows iso is detected and not raw writable`() {
        val info = ImageInspector.inspect(iso("CCCOMA_X64FRE_EN-US_DV9", udf = true))
        assertThat(info.kind).isEqualTo(ImageKind.WINDOWS_ISO)
        assertThat(info.hasUdf).isTrue()
        assertThat(info.rawWritable).isFalse()
    }

    @Test fun `plain iso without mbr is flagged`() {
        assertThat(ImageInspector.inspect(iso("SOME_CD")).kind).isEqualTo(ImageKind.NON_HYBRID_ISO)
    }

    @Test fun `disk image with mbr`() {
        val b = ByteArray(4096)
        b[446 + 4] = 0x0C; b[446 + 12] = 0x01; b[510] = 0x55; b[511] = 0xAA.toByte()
        assertThat(ImageInspector.inspect(b).kind).isEqualTo(ImageKind.DISK_IMAGE)
    }

    @Test fun `random bytes are unknown`() {
        assertThat(ImageInspector.inspect(ByteArray(4096) { 0x41 }).kind).isEqualTo(ImageKind.UNKNOWN)
    }

    @Test fun `empty mbr signature without partitions is not a partition table`() {
        val b = ByteArray(4096); b[510] = 0x55; b[511] = 0xAA.toByte()
        assertThat(ImageInspector.inspect(b).kind).isEqualTo(ImageKind.UNKNOWN)
    }
}
