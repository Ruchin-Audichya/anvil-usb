package dev.ruchin.anvil.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class CommonTest {
    @Test fun `byte formatting`() {
        assertThat(ByteSize.format(9_047_330_816L, Locale.US)).isEqualTo("8.43 GB")
        assertThat(ByteSize.format(1536, Locale.US)).isEqualTo("2 KB")
        assertThat(ByteSize.marketingCapacity(31_457_280_000L)).isEqualTo("31 GB")
    }

    @Test fun `meter smooths rate and computes eta`() {
        var now = 0L
        val m = TransferMeter(clock = { now }, smoothing = 0.5)
        m.reset(0)
        now = 1_000_000_000L; m.update(10_000_000)       // 10 MB/s
        assertThat(m.bytesPerSecond).isWithin(1.0).of(10_000_000.0)
        now = 2_000_000_000L; m.update(30_000_000)       // 20 MB/s instant -> 15 smoothed
        assertThat(m.bytesPerSecond).isWithin(1.0).of(15_000_000.0)
        assertThat(m.etaSeconds(30_000_000, 180_000_000)).isEqualTo(10)
    }

    @Test fun `eta formatting`() {
        assertThat(formatEta(null)).isEqualTo("—")
        assertThat(formatEta(42)).isEqualTo("42s")
        assertThat(formatEta(125)).isEqualTo("2m 05s")
        assertThat(formatEta(3725)).isEqualTo("1h 02m")
    }

    @Test fun `hex digest comparison is case-insensitive`() {
        assertThat(hexDigestEquals("ABcd", "abCD")).isTrue()
        assertThat(hexDigestEquals("ab", "abc")).isFalse()
        assertThat(byteArrayOf(0x00, 0x7f, -1).toHex()).isEqualTo("007fff")
    }
}
