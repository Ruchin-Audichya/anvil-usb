package dev.ruchin.anvil.writer

import dev.ruchin.anvil.blockdev.BlockDevice
import dev.ruchin.anvil.blockdev.RecordingBlockDevice
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.RandomAccessSource
import dev.ruchin.anvil.common.Segment
import dev.ruchin.anvil.common.TransferMeter
import dev.ruchin.anvil.fat.Fat32Writer
import dev.ruchin.anvil.fat.FatDir
import dev.ruchin.anvil.fat.FatFile
import dev.ruchin.anvil.fat.PartitionScheme
import dev.ruchin.anvil.fat.Partitioner
import dev.ruchin.anvil.fat.PlannedFile
import dev.ruchin.anvil.udf.UdfNode
import dev.ruchin.anvil.udf.UdfReader
import dev.ruchin.anvil.wim.WimSplitter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.SecureRandom

enum class WindowsStage { READING_ISO, PARTITIONING, FORMATTING, COPYING, SPLITTING_WIM, VERIFYING, DONE }

data class WindowsProgress(
    val stage: WindowsStage,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val currentFile: String? = null,
)

data class WindowsWriteOptions(
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val verify: Boolean = true,
    /** FAT volume label; defaults to the ISO's label (e.g. "CCCOMA_X64F"). */
    val label: String? = null,
    /** Windows Setup automation (bypass, local account, …); null = plain, unmodified installer. */
    val unattend: dev.ruchin.anvil.unattend.UnattendOptions? = null,
    /** Optional driver folder copied to \Drivers\ (Intel RST/VMD storage drivers for "No drivers were found"). */
    val drivers: DriverFolder? = null,
)

data class WindowsWriteResult(
    val filesWritten: Int,
    val bytesWritten: Long,
    val swmParts: List<String>,
    val verified: Boolean,
    val durationMs: Long,
)

/**
 * Turns a Windows installation ISO into a UEFI-bootable USB drive:
 *
 *  1. read the ISO's UDF file system (no extraction, files stay as extent lists)
 *  2. if sources/install.wim is > 4 GiB, split it into install.swm, install2.swm… (streamed, no temp files)
 *  3. GPT (or MBR) partition table with one 1 MiB-aligned partition
 *  4. FAT32, planned up front so every file is one contiguous run
 *  5. copy all files in ISO order (sequential reads, sequential USB writes)
 *  6. read everything back and compare SHA-256
 *
 * UEFI firmware boots \efi\boot\bootx64.efi from FAT32 natively, with Secure Boot,
 * since the boot files are Microsoft-signed and copied unmodified.
 */
class WindowsUsbWriter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun write(
        iso: RandomAccessSource,
        isoLabel: String?,
        device: BlockDevice,
        options: WindowsWriteOptions = WindowsWriteOptions(),
        onProgress: (WindowsProgress) -> Unit = {},
    ): WindowsWriteResult = withContext(ioDispatcher) {
        val started = System.nanoTime()
        val ctx = coroutineContext
        onProgress(WindowsProgress(WindowsStage.READING_ISO))

        // 1-2. Build the FAT tree from the ISO.
        val root = UdfReader(iso).readRoot()
        val tree = FatDir("")
        val swmNames = mutableListOf<String>()
        var hasEfiBoot = false
        fun add(dir: UdfNode.Directory, fat: FatDir) {
            for (c in dir.children) when (c) {
                is UdfNode.Directory -> add(c, fat.dir(c.name))
                is UdfNode.File -> {
                    if (c.path.equals("/efi/boot/bootx64.efi", true) || c.path.equals("/efi/boot/bootaa64.efi", true)) hasEfiBoot = true
                    val isInstallImage = dir.path.equals("/sources", true) && c.name.equals("install.wim", true)
                    if (isInstallImage && WimSplitter.needsSplit(c.size)) {
                        for (p in WimSplitter.split(c.content)) {
                            fat.add(FatFile(p.fileName, p.content))
                            swmNames += p.fileName
                        }
                        DebugLog.i(TAG, "install.wim ${c.size} B split into ${swmNames.size} parts")
                    } else if (c.size > Fat32Writer.MAX_FILE) {
                        throw AnvilException(
                            "${c.name} is larger than 4 GB and can't be split. This ISO needs the NTFS layout (Advanced options).",
                            "oversized ${c.path} ${c.size}",
                        )
                    } else {
                        fat.add(FatFile(c.name, c.content))
                    }
                }
            }
        }
        add(root, tree)
        options.unattend?.let { customize(tree, root, it, isoLabel) }
        options.drivers?.let {
            it.addTo(tree)
            DebugLog.i(TAG, "drivers: ${it.files.size} files, ${it.totalBytes} B from '${it.displayName}' → \\${DriverFolder.TARGET}")
        }
        if (!hasEfiBoot) {
            throw AnvilException(
                "This doesn't look like a Windows installer ISO (no UEFI boot files found).",
                "missing efi/boot/boot*.efi",
            )
        }
        ctx.ensureActive()

        // 3. Partition table.
        val rec = if (options.verify) RecordingBlockDevice(device) else null
        val target = rec ?: device
        val layout = Partitioner.plan(target, options.scheme)
        val fat = Fat32Writer(target)
        // Read ISO files in on-disc order; .swm parts (no direct ISO offset) go last.
        val order = compareBy<PlannedFile> { (it.file.content.segments.firstOrNull() as? Segment.Range)?.offset ?: Long.MAX_VALUE }
        // Plan before touching the drive, so "too small" fails without erasing anything.
        val plan = fat.plan(tree, layout.startLba, layout.sectorCount, options.label ?: isoLabel ?: "WINDOWS", order)
        DebugLog.i(TAG, "FAT32 plan: ${plan.files.size} files, ${plan.fileBytes} B, cluster ${plan.clusterBytes}, ${plan.usedClusters}/${plan.clusterCount} clusters")

        onProgress(WindowsProgress(WindowsStage.PARTITIONING))
        Partitioner.write(target, layout, random = random)

        // 4. File system metadata.
        onProgress(WindowsProgress(WindowsStage.FORMATTING))
        fat.writeMetadata(plan, random.nextInt())

        // 5. Files.
        val total = plan.fileBytes
        val meter = TransferMeter().also { it.reset() }
        var lastEmit = 0L
        var stage = WindowsStage.COPYING
        var current: String? = null
        fat.writeFiles(
            plan,
            checkCancelled = { ctx.ensureActive() },
            onFile = { pf ->
                current = pf.path
                stage = if (pf.file.name.endsWith(".swm", true)) WindowsStage.SPLITTING_WIM else WindowsStage.COPYING
            },
            onBytes = { done ->
                val now = System.nanoTime()
                if (now - lastEmit > 200_000_000L || done == total) {
                    lastEmit = now
                    val rate = meter.update(done)
                    onProgress(WindowsProgress(stage, done, total, rate, meter.etaSeconds(done, total), current))
                }
            },
        )
        device.flush()

        // 6. Verify.
        if (rec != null) {
            val vMeter = TransferMeter().also { it.reset() }
            var vLast = 0L
            rec.verify(checkCancelled = { ctx.ensureActive() }) { done, all ->
                val now = System.nanoTime()
                if (now - vLast > 200_000_000L || done == all) {
                    vLast = now
                    val rate = vMeter.update(done)
                    onProgress(WindowsProgress(WindowsStage.VERIFYING, done, all, rate, vMeter.etaSeconds(done, all)))
                }
            }
        }
        onProgress(WindowsProgress(WindowsStage.DONE, total, total))
        WindowsWriteResult(plan.files.size, total, swmNames, rec != null, (System.nanoTime() - started) / 1_000_000)
    }

    /** Adds autounattend.xml (+ helper files) and, for the hardware bypass, Rufus's appraiserres.dll swap. */
    private fun customize(tree: FatDir, root: UdfNode.Directory, opts: dev.ruchin.anvil.unattend.UnattendOptions, isoLabel: String?) {
        val arch = if (root.find("efi/boot/bootaa64.efi") != null) "arm64" else "amd64"
        val bundle = dev.ruchin.anvil.unattend.AutounattendBuilder.build(
            opts, arch, dev.ruchin.anvil.unattend.AutounattendBuilder.languageFromIsoLabel(isoLabel),
        )
        fun text(s: String) = dev.ruchin.anvil.common.VirtualFile(listOf(Segment.Bytes(s.toByteArray(Charsets.UTF_8))))
        tree.put("autounattend.xml", text(bundle.autounattendXml))
        bundle.extraFiles.forEach { (path, content) -> tree.put(path, text(content)) }
        if (opts.bypassHardwareChecks) {
            // setup.exe's compatibility appraiser: keep the original as .bak, leave an empty stub (Rufus does the same).
            tree.find("sources/appraiserres.dll")?.let { orig ->
                tree.put("sources/appraiserres.bak", orig.content)
                tree.put("sources/appraiserres.dll", dev.ruchin.anvil.common.VirtualFile(emptyList()))
            }
        }
        DebugLog.i(TAG, "customized: arch=$arch autounattend ${bundle.autounattendXml.length} B, extra=${bundle.extraFiles.keys}")
    }

    companion object { private const val TAG = "WindowsWriter" }
}
