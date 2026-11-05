package dev.ruchin.anvil.fat

import dev.ruchin.anvil.blockdev.FileBlockDevice
import dev.ruchin.anvil.common.ByteArraySource
import dev.ruchin.anvil.common.Segment
import dev.ruchin.anvil.common.VirtualFile
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class Fat32WriterTest {
    @TempDir lateinit var tmp: File

    private fun tool(name: String) = listOf("/usr/sbin/$name", "/usr/bin/$name", "/sbin/$name").map(::File).firstOrNull { it.canExecute() }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private val rnd = Random(9)
    private val expected = LinkedHashMap<String, ByteArray>()

    private fun file(dir: FatDir, path: String, name: String, size: Int, split: Boolean = false) {
        val data = rnd.nextBytes(size)
        expected["$path/$name".trimStart('/')] = data
        val vf = if (split && size > 10) {
            // Exercise multi-segment content: bytes + range + zeros-free range
            val src = ByteArraySource(data)
            VirtualFile(listOf(Segment.Bytes(data.copyOfRange(0, 7)), Segment.Range(src, 7, (size - 7).toLong())))
        } else VirtualFile(listOf(Segment.Bytes(data)))
        dir.add(FatFile(name, vf))
    }

    private fun buildTree(): FatDir {
        val root = FatDir("")
        file(root, "", "bootmgr", 400_000)
        file(root, "", "setup.exe", 90_000, split = true)
        file(root, "", "autorun.inf", 128)
        file(root, "", "empty.txt", 0)
        val efi = root.dir("efi"); val boot = efi.dir("boot")
        file(boot, "/efi/boot", "bootx64.efi", 1_500_000)
        val sources = root.dir("sources")
        file(sources, "/sources", "install.swm", 3_000_000, split = true)
        file(sources, "/sources", "install2.swm", 2_000_000)
        file(sources, "/sources", "microsoft-windows-netfx3-ondemand-package~31bf3856ad364e35~amd64~~.cab", 50_000)
        file(sources, "/sources", "Lower.Case.Name.With.Dots.xml", 5_000)
        file(sources, "/sources", "exactly13char", 77) // LFN boundary
        val many = sources.dir("replacementmanifests")
        for (i in 0 until 300) file(many, "/sources/replacementmanifests", "microsoft-windows-component-$i-replacement.man", 300 + i)
        return root
    }

    @Test fun `gpt + fat32 image passes sgdisk, fsck_fat and 7z extraction`() {
        val img = File(tmp, "usb.img")
        val dev = FileBlockDevice(img, 300L * 1024 * 1024)
        val layout = Partitioner.plan(dev, PartitionScheme.GPT)
        Partitioner.write(dev, layout)
        val fat = Fat32Writer(dev)
        val plan = fat.plan(buildTree(), layout.startLba, layout.sectorCount, "CCCOMA_X64FRE_EN-US_DV9")
        assertThat(plan.label).isEqualTo("CCCOMA_X64F")
        assertThat((plan.dataStartLba * 512) % (1024 * 1024)).isEqualTo(0) // data region 1 MiB aligned
        fat.writeMetadata(plan, volumeId = 0x1234ABCD)
        fat.writeFiles(plan)
        dev.close()

        tool("sgdisk")?.let { sg ->
            val (code, out) = run(sg.path, "-v", img.path)
            assertThat(out).contains("No problems found")
            assertThat(code).isEqualTo(0)
        }
        tool("fsck.fat")?.let { fsck ->
            val part = File(tmp, "part.img")
            RandomAccessFile(img, "r").use { r ->
                RandomAccessFile(part, "rw").use { w ->
                    val buf = ByteArray(1 shl 20)
                    r.seek(layout.startLba * 512)
                    var left = layout.sectorCount * 512
                    while (left > 0) { val n = minOf(left, buf.size.toLong()).toInt(); r.readFully(buf, 0, n); w.write(buf, 0, n); left -= n }
                }
            }
            val (code, out) = run(fsck.path, "-n", "-v", part.path)
            assertThat(out).doesNotContainMatch("(?i)(differ|error|lost|invalid|bad )")
            assertThat(code).isEqualTo(0)
        }
        val sevenZ = tool("7z")
        assumeTrue(sevenZ != null, "7z not installed")
        val outDir = File(tmp, "x").apply { mkdirs() }
        val (code, out) = run(sevenZ!!.path, "x", "-y", "-o${outDir.path}", img.path)
        assertWithMessage(out).that(code).isEqualTo(0)
        // 7z extracts GPT partitions as "0.img"/"<name>.img" first; dig into the FAT image.
        val fatImg = outDir.walkTopDown().firstOrNull { it.isFile && it.length() > 100_000_000 }
        val contentDir = if (fatImg != null) {
            val d2 = File(tmp, "y").apply { mkdirs() }
            val (c2, o2) = run(sevenZ.path, "x", "-y", "-o${d2.path}", fatImg.path)
            assertWithMessage(o2).that(c2).isEqualTo(0)
            d2
        } else outDir
        for ((path, data) in expected) {
            val f = File(contentDir, path)
            assertWithMessage(path).that(f.exists()).isTrue()
            assertThat(f.readBytes()).isEqualTo(data)
        }
    }

    @Test fun `short names`() {
        val used = HashSet<String>()
        assertThat(String(ShortNames.generate("BOOTMGR", used).first)).isEqualTo("BOOTMGR    ")
        assertThat(ShortNames.generate("SETUP.EXE", used).second).isFalse()
        assertThat(String(ShortNames.generate("setup.exe", HashSet()).first)).isEqualTo("SETUP   EXE")
        val a = String(ShortNames.generate("microsoft-windows-a.man", used).first)
        val b = String(ShortNames.generate("microsoft-windows-b.man", used).first)
        assertThat(a).isEqualTo("MICROS~1MAN")
        assertThat(b).isEqualTo("MICROS~2MAN")
        assertThat(ShortNames.checksum("MICROS~1MAN".toByteArray())).isIn(0..255)
    }

    @Test fun `cluster sizes`() {
        assertThat(Fat32Writer.clusterSizeFor(16L shl 30)).isEqualTo(16384)
        assertThat(Fat32Writer.clusterSizeFor(64L shl 30)).isEqualTo(32768)
    }
}

class PartitionerTest {
    @Test fun `every sector is written once`() {
        val written = HashMap<Long, Int>()
        val mem = dev.ruchin.anvil.blockdev.MemoryBlockDevice(blockCount = 64L * 2048)
        val dev = object : dev.ruchin.anvil.blockdev.BlockDevice by mem {
            override fun write(lba: Long, src: ByteArray, offset: Int, blocks: Int) {
                for (i in 0 until blocks) written.merge(lba + i, 1, Int::plus)
                mem.write(lba, src, offset, blocks)
            }
        }
        for (scheme in PartitionScheme.entries) {
            written.clear()
            Partitioner.write(dev, Partitioner.plan(dev, scheme))
            assertThat(written.values.all { it == 1 }).isTrue()
        }
    }
}
