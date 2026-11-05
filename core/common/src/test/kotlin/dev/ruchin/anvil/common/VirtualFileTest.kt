package dev.ruchin.anvil.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VirtualFileTest {
    @Test fun `slice and random access across mixed segments`() {
        val src = ByteArraySource(ByteArray(100) { it.toByte() })
        val vf = VirtualFile(listOf(
            Segment.Bytes(byteArrayOf(-1, -2, -3)),
            Segment.Range(src, 10, 5),       // 10..14
            Segment.Zeros(4),
            Segment.Range(src, 50, 3),       // 50..52
        ))
        assertThat(vf.size).isEqualTo(15)
        val all = vf.asSource().readBytes(0, 15)
        assertThat(all.map { it.toInt() }).containsExactly(-1, -2, -3, 10, 11, 12, 13, 14, 0, 0, 0, 0, 50, 51, 52).inOrder()
        assertThat(vf.asSource().readBytes(2, 4).map { it.toInt() }).containsExactly(-3, 10, 11, 12).inOrder()
        val sl = vf.slice(6, 7)
        assertThat(sl.map { it.length }).containsExactly(2L, 4L, 1L).inOrder()
        assertThat((sl[0] as Segment.Range).offset).isEqualTo(13)
    }
}
