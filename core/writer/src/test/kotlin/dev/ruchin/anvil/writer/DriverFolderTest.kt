package dev.ruchin.anvil.writer

import com.google.common.truth.Truth.assertThat
import dev.ruchin.anvil.blockdev.MemoryBlockDevice
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.Segment
import dev.ruchin.anvil.common.VirtualFile
import dev.ruchin.anvil.fat.Fat32Writer
import dev.ruchin.anvil.fat.FatDir
import dev.ruchin.anvil.fat.FatFile
import dev.ruchin.anvil.fat.PartitionScheme
import dev.ruchin.anvil.fat.Partitioner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class DriverFolderTest {
    private fun vf(bytes: ByteArray) = VirtualFile(listOf(Segment.Bytes(bytes)))

    @Test fun `files land under Drivers keeping their folders`() {
        val tree = FatDir("")
        tree.dir("sources").add(FatFile("boot.wim", vf(ByteArray(10))))
        DriverFolder("IRST", listOf(
            DriverFile("iaStorVD.inf", vf("inf".toByteArray())),
            DriverFile("x64/iaStorVD.sys", vf("sys".toByteArray())),
        )).addTo(tree)
        assertThat(tree.find("Drivers/iaStorVD.inf")).isNotNull()
        assertThat(tree.find("Drivers/x64/iaStorVD.sys")).isNotNull()
        assertThat(tree.find("sources/boot.wim")).isNotNull()
    }

    @Test fun `paths are made safe for FAT`() {
        assertThat(DriverFolder.sanitize("../../etc/passwd")).containsExactly("etc", "passwd").inOrder()
        assertThat(DriverFolder.sanitize("a\\b/c:d?.inf")).containsExactly("a", "b", "c_d_.inf").inOrder()
        assertThat(DriverFolder.sanitize("dir./file.")).containsExactly("dir", "file").inOrder()
        assertThrows<AnvilException> { DriverFolder.sanitize("../..") }
    }

    @Test fun `empty folder is refused with a clear message`() {
        val e = assertThrows<AnvilException> { DriverFolder("Empty", emptyList()).addTo(FatDir("")) }
        assertThat(e.userMessage).contains(".inf")
    }

    @Test fun `driver bytes are written to the FAT32 partition`() {
        val dev = MemoryBlockDevice(blockCount = 262_144) // 128 MiB
        val sys = Random(5).nextBytes(300_000)
        val tree = FatDir("")
        tree.dir("efi").dir("boot").add(FatFile("bootx64.efi", vf(ByteArray(4096) { 1 })))
        DriverFolder("IRST", listOf(DriverFile("x64/iaStorVD.sys", vf(sys)))).addTo(tree)

        val layout = Partitioner.plan(dev, PartitionScheme.GPT)
        val fat = Fat32Writer(dev)
        val plan = fat.plan(tree, layout.startLba, layout.sectorCount, "TEST")
        Partitioner.write(dev, layout)
        fat.writeMetadata(plan, 42)
        fat.writeFiles(plan)

        val pf = plan.files.single { it.path.equals("/Drivers/x64/iaStorVD.sys", true) || it.path.equals("Drivers/x64/iaStorVD.sys", true) }
        val off = (plan.clusterLba(pf.firstCluster) * 512).toInt()
        assertThat(dev.data.copyOfRange(off, off + sys.size)).isEqualTo(sys)
    }
}
