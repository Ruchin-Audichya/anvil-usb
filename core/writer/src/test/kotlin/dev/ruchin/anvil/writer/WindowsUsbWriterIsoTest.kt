package dev.ruchin.anvil.writer

import dev.ruchin.anvil.blockdev.FileBlockDevice
import dev.ruchin.anvil.common.ChannelSource
import dev.ruchin.anvil.image.ImageInspector
import dev.ruchin.anvil.udf.UdfNode
import dev.ruchin.anvil.udf.UdfReader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end on a real Microsoft ISO (not shipped with the repo):
 *   ANVIL_WIN_ISO=/path/Win11.iso ./gradlew :core:writer:test
 * Writes it to a sparse 16 GB image, with read-back verification, and checks the result.
 */
class WindowsUsbWriterIsoTest {
    @TempDir lateinit var tmp: File

    @Test fun `real windows iso to fat32 image`(): Unit = runBlocking {
        val iso = System.getenv("ANVIL_WIN_ISO")?.let(::File)
        assumeTrue(iso != null && iso.isFile, "set ANVIL_WIN_ISO to run")
        val label = ImageInspector.inspect(iso!!.inputStream().use { it.readNBytes(ImageInspector.HEADER_BYTES) }).volumeLabel
        ChannelSource.open(iso).use { src ->
            val udfFiles = UdfReader(src).readRoot().files().toList()
            val wim = udfFiles.first { it.path.equals("/sources/install.wim", true) }
            val stages = mutableSetOf<WindowsStage>()
            val img = File(iso.parentFile, "anvil-isotest-usb.img").apply { deleteOnExit() }
            FileBlockDevice(img, 16_000_000_000L).use { dev ->
                val r = WindowsUsbWriter(Dispatchers.IO).write(src, label, dev) { stages += it.stage }
                assertThat(r.verified).isTrue()
                val expectedFiles = udfFiles.size - (if (r.swmParts.isNotEmpty()) 1 else 0) + r.swmParts.size
                assertThat(r.filesWritten).isEqualTo(expectedFiles)
                if (wim.size > 0xFFFF_FFFFL) assertThat(r.swmParts).isNotEmpty()
                assertThat(stages).containsAtLeast(WindowsStage.PARTITIONING, WindowsStage.FORMATTING, WindowsStage.VERIFYING, WindowsStage.DONE)
            }
        }
    }
}
