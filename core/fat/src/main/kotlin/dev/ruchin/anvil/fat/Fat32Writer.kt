package dev.ruchin.anvil.fat

import dev.ruchin.anvil.blockdev.BlockDevice
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DeviceTooSmallException
import dev.ruchin.anvil.common.Segment
import dev.ruchin.anvil.common.VirtualFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime

class FatFile(val name: String, val content: VirtualFile)

class FatDir(val name: String) {
    val dirs = mutableListOf<FatDir>()
    val files = mutableListOf<FatFile>()
    fun dir(name: String): FatDir = dirs.firstOrNull { it.name.equals(name, true) } ?: FatDir(name).also { dirs += it }
    fun add(file: FatFile) { files += file }

    /** Adds or replaces the file at [path] ("sources/x.dll"), creating folders as needed. */
    fun put(path: String, content: VirtualFile) {
        val parts = path.trim('/').split('/')
        var d = this
        for (p in parts.dropLast(1)) d = d.dir(p)
        d.files.removeAll { it.name.equals(parts.last(), true) }
        d.files += FatFile(parts.last(), content)
    }

    fun find(path: String): FatFile? {
        val parts = path.trim('/').split('/')
        var d: FatDir = this
        for (p in parts.dropLast(1)) d = d.dirs.firstOrNull { it.name.equals(p, true) } ?: return null
        return d.files.firstOrNull { it.name.equals(parts.last(), true) }
    }
}

/** Precomputed FAT32 layout: geometry, cluster allocation and directory contents. */
class Fat32Plan internal constructor(
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val fatSectors: Long,
    val totalSectors: Long,
    val clusterCount: Long,
    val partitionStartLba: Long,
    val label: String,
    internal val dirs: List<PlannedDir>,
    val files: List<PlannedFile>,
    internal val runs: List<LongArray>, // [startCluster, clusterCount], sorted
    val usedClusters: Long,
) {
    val clusterBytes get() = bytesPerSector * sectorsPerCluster
    val dataStartLba get() = partitionStartLba + reservedSectors + 2 * fatSectors
    val fileBytes get() = files.sumOf { it.file.content.size }
    fun clusterLba(cluster: Long) = dataStartLba + (cluster - 2) * sectorsPerCluster
}

internal class PlannedDir(val dir: FatDir, val path: String, val parent: PlannedDir?) {
    var firstCluster = 0L
    var clusters = 0L
    var bytes: ByteArray = ByteArray(0)
    val entries = mutableListOf<DirEntry>()
}

class PlannedFile(val file: FatFile, val path: String) {
    var firstCluster = 0L
        internal set
}

internal class DirEntry(val longName: String, val shortName: ByteArray, val needsLfn: Boolean, val isDir: Boolean, val target: Any)

/**
 * Builds a FAT32 file system on a [BlockDevice] partition in a single forward
 * pass: boot sectors → both FATs → directories → file data. Because the full
 * tree (with sizes) is known before writing, every file gets one contiguous
 * cluster run, the FAT is generated rather than stored, and USB writes are
 * strictly sequential — the fastest pattern for flash drives.
 */
class Fat32Writer(private val device: BlockDevice) {

    fun plan(root: FatDir, startLba: Long, sectorCount: Long, label: String, fileOrder: Comparator<PlannedFile>? = null): Fat32Plan {
        val bps = device.blockSize
        val partBytes = sectorCount * bps
        val clusterBytes = maxOf(bps, clusterSizeFor(partBytes))
        val spc = clusterBytes / bps
        val alignSectors = (1024L * 1024 / bps).coerceAtLeast(spc.toLong())

        var reserved = 32
        var fat = 0L
        var clusters = 0L
        repeat(16) {
            fat = computeFatSectors(sectorCount, reserved, spc, bps)
            val dataStart = startLba + reserved + 2 * fat
            val pad = ((alignSectors - dataStart % alignSectors) % alignSectors).toInt()
            if (pad == 0) {
                clusters = (sectorCount - reserved - 2 * fat) / spc
                return@repeat
            }
            reserved += pad
        }
        if (clusters == 0L) {
            fat = computeFatSectors(sectorCount, reserved, spc, bps)
            clusters = (sectorCount - reserved - 2 * fat) / spc
        }
        if (clusters < MIN_CLUSTERS) throw AnvilException("This USB drive is too small for FAT32.", "clusters=$clusters")
        if (clusters > MAX_CLUSTERS) throw AnvilException("This USB drive is too large for FAT32.", "clusters=$clusters")

        // --- Directory tree, entries and short names ---
        val dirs = ArrayList<PlannedDir>()
        val files = ArrayList<PlannedFile>()
        val queue = ArrayDeque<PlannedDir>()
        val rootPlanned = PlannedDir(root, "", null)
        queue += rootPlanned
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            dirs += d
            val used = HashSet<String>()
            for (sub in d.dir.dirs.sortedBy { it.name.lowercase() }) {
                val (sfn, lfn) = ShortNames.generate(sub.name, used)
                val child = PlannedDir(sub, "${d.path}/${sub.name}", d)
                d.entries += DirEntry(sub.name, sfn, lfn, true, child)
                queue += child
            }
            for (f in d.dir.files.sortedBy { it.name.lowercase() }) {
                if (f.content.size > MAX_FILE) throw AnvilException("${f.name} is larger than 4 GB and can't be stored on FAT32.", "file ${f.content.size}")
                val (sfn, lfn) = ShortNames.generate(f.name, used)
                val pf = PlannedFile(f, "${d.path}/${f.name}")
                d.entries += DirEntry(f.name, sfn, lfn, false, pf)
                files += pf
            }
        }

        // --- Cluster allocation: root, other dirs, then files (in read order) ---
        var next = 2L
        val runs = ArrayList<LongArray>()
        fun alloc(bytes: Long): Long {
            val n = (bytes + clusterBytes - 1) / clusterBytes
            if (n == 0L) return 0
            val first = next
            next += n
            runs += longArrayOf(first, n)
            return first
        }
        for (d in dirs) {
            val entryCount = (if (d.parent == null) 1 else 2) + d.entries.sumOf { (if (it.needsLfn) lfnCount(it.longName) else 0) + 1 }
            if (entryCount > 65536) throw AnvilException("A folder in this ISO has too many files for FAT32.", "${d.path}: $entryCount entries")
            d.clusters = maxOf(1L, (entryCount * 32L + clusterBytes - 1) / clusterBytes)
            d.firstCluster = alloc(d.clusters * clusterBytes)
        }
        val ordered = if (fileOrder != null) files.sortedWith(fileOrder) else files
        for (f in ordered) f.firstCluster = alloc(f.file.content.size)
        val used = next - 2
        if (used > clusters) {
            throw DeviceTooSmallException(required = (used + (2 * fat + reserved) / spc) * clusterBytes + startLba * bps + 2L * 1024 * 1024, available = device.sizeBytes)
        }

        val fatLabel = sanitizeLabel(label)
        val now = LocalDateTime.now()
        for (d in dirs) d.bytes = buildDirectory(d, clusterBytes, fatLabel, now)

        return Fat32Plan(bps, spc, reserved, fat, sectorCount, clusters, startLba, fatLabel, dirs, ordered, runs, used)
    }

    /** Boot sectors, FSInfo, both FATs and all directories. */
    fun writeMetadata(plan: Fat32Plan, volumeId: Int) {
        val bps = plan.bytesPerSector
        val reserved = ByteArray(plan.reservedSectors * bps)
        val boot = bootSector(plan, volumeId)
        boot.copyInto(reserved, 0)
        boot.copyInto(reserved, 6 * bps)
        val fsInfo = fsInfo(plan)
        fsInfo.copyInto(reserved, 1 * bps)
        fsInfo.copyInto(reserved, 7 * bps)
        device.write(plan.partitionStartLba, reserved, 0, plan.reservedSectors)

        // FATs, generated in chunks from the cluster runs.
        val chunkSectors = 2048
        val buf = ByteArray(chunkSectors * bps)
        for (copy in 0 until 2) {
            val fatStart = plan.partitionStartLba + plan.reservedSectors + copy * plan.fatSectors
            var runIdx = 0
            var s = 0L
            while (s < plan.fatSectors) {
                val n = minOf(chunkSectors.toLong(), plan.fatSectors - s).toInt()
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                java.util.Arrays.fill(buf, 0, n * bps, 0)
                val firstEntry = s * bps / 4
                val entries = n * bps / 4
                for (i in 0 until entries) {
                    val c = firstEntry + i
                    val v: Int = when {
                        c == 0L -> 0x0FFFFFF8
                        c == 1L -> 0x0FFFFFFF
                        else -> {
                            while (runIdx < plan.runs.size && c >= plan.runs[runIdx][0] + plan.runs[runIdx][1]) runIdx++
                            if (runIdx < plan.runs.size && c >= plan.runs[runIdx][0]) {
                                val end = plan.runs[runIdx][0] + plan.runs[runIdx][1]
                                if (c == end - 1) EOC else (c + 1).toInt()
                            } else 0
                        }
                    }
                    if (v != 0) bb.putInt(i * 4, v)
                }
                device.write(fatStart + s, buf, 0, n)
                s += n
            }
        }

        for (d in plan.dirs) {
            val lba = plan.clusterLba(d.firstCluster)
            device.write(lba, d.bytes, 0, d.bytes.size / bps)
        }
    }

    /**
     * Streams every file's segments into its cluster run.
     * [onFile] is called before each file; [onBytes] with the running total.
     */
    fun writeFiles(
        plan: Fat32Plan,
        bufferBytes: Int = 4 * 1024 * 1024,
        checkCancelled: () -> Unit = {},
        onFile: (PlannedFile) -> Unit = {},
        onBytes: (Long) -> Unit = {},
    ) {
        val bps = plan.bytesPerSector
        val bufSize = (bufferBytes / plan.clusterBytes).coerceAtLeast(1) * plan.clusterBytes
        val buf = ByteArray(bufSize)
        var total = 0L
        for (pf in plan.files) {
            val size = pf.file.content.size
            if (size == 0L) continue
            onFile(pf)
            var lba = plan.clusterLba(pf.firstCluster)
            var fill = 0
            fun flush(final: Boolean) {
                if (fill == 0) return
                val padded = if (final) ((fill + plan.clusterBytes - 1) / plan.clusterBytes) * plan.clusterBytes else fill
                if (padded > fill) java.util.Arrays.fill(buf, fill, padded, 0)
                val sectors = padded / bps
                var off = 0
                while (off < sectors) {
                    val n = minOf(device.maxBlocksPerTransfer, sectors - off)
                    device.write(lba + off, buf, off * bps, n)
                    off += n
                }
                lba += sectors
                fill = 0
            }
            for (seg in pf.file.content.segments) {
                var done = 0L
                while (done < seg.length) {
                    checkCancelled()
                    val n = minOf((bufSize - fill).toLong(), seg.length - done).toInt()
                    when (seg) {
                        is Segment.Range -> seg.source.readFully(seg.offset + done, buf, fill, n)
                        is Segment.Bytes -> System.arraycopy(seg.data, done.toInt(), buf, fill, n)
                        is Segment.Zeros -> java.util.Arrays.fill(buf, fill, fill + n, 0)
                    }
                    fill += n
                    done += n
                    total += n
                    if (fill == bufSize) flush(false)
                    onBytes(total)
                }
            }
            flush(true)
        }
    }

    private fun buildDirectory(d: PlannedDir, clusterBytes: Int, label: String, now: LocalDateTime): ByteArray {
        val out = ByteArray((d.clusters * clusterBytes).toInt())
        var p = 0
        val (date, time) = dosDateTime(now)
        fun sfnEntry(name: ByteArray, attr: Int, cluster: Long, size: Long) {
            val bb = ByteBuffer.wrap(out, p, 32).order(ByteOrder.LITTLE_ENDIAN)
            name.copyInto(out, p)
            out[p + 11] = attr.toByte()
            bb.putShort(p + 14, time); bb.putShort(p + 16, date); bb.putShort(p + 18, date)
            bb.putShort(p + 20, (cluster ushr 16).toShort())
            bb.putShort(p + 22, time); bb.putShort(p + 24, date)
            bb.putShort(p + 26, (cluster and 0xFFFF).toShort())
            bb.putInt(p + 28, size.toInt())
            p += 32
        }
        if (d.parent == null) {
            sfnEntry(label.padEnd(11).toByteArray(Charsets.US_ASCII), ATTR_VOLUME_ID, 0, 0)
        } else {
            sfnEntry(".          ".toByteArray(Charsets.US_ASCII), ATTR_DIRECTORY, d.firstCluster, 0)
            val parentCluster = if (d.parent.parent == null) 0 else d.parent.firstCluster
            sfnEntry("..         ".toByteArray(Charsets.US_ASCII), ATTR_DIRECTORY, parentCluster, 0)
        }
        for (e in d.entries) {
            if (e.needsLfn) p = writeLfn(out, p, e.longName, ShortNames.checksum(e.shortName))
            if (e.isDir) {
                val c = e.target as PlannedDir
                sfnEntry(e.shortName, ATTR_DIRECTORY, c.firstCluster, 0)
            } else {
                val f = e.target as PlannedFile
                sfnEntry(e.shortName, ATTR_ARCHIVE, f.firstCluster, f.file.content.size)
            }
        }
        return out
    }

    private fun writeLfn(out: ByteArray, start: Int, name: String, checksum: Int): Int {
        val units = name.toCharArray()
        val count = lfnCount(name)
        var p = start
        for (seq in count downTo 1) {
            val base = (seq - 1) * 13
            out[p] = (seq or if (seq == count) 0x40 else 0).toByte()
            out[p + 11] = ATTR_LFN.toByte()
            out[p + 12] = 0
            out[p + 13] = checksum.toByte()
            out[p + 26] = 0; out[p + 27] = 0
            for (i in 0 until 13) {
                val idx = base + i
                val ch = when {
                    idx < units.size -> units[idx].code
                    idx == units.size -> 0x0000
                    else -> 0xFFFF
                }
                val off = p + LFN_OFFSETS[i]
                out[off] = (ch and 0xFF).toByte()
                out[off + 1] = (ch ushr 8).toByte()
            }
            p += 32
        }
        return p
    }

    private fun bootSector(plan: Fat32Plan, volumeId: Int): ByteArray {
        val bps = plan.bytesPerSector
        val b = ByteArray(bps)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(b, 3)
        bb.putShort(11, bps.toShort())
        b[13] = plan.sectorsPerCluster.toByte()
        bb.putShort(14, plan.reservedSectors.toShort())
        b[16] = 2 // FATs
        b[21] = 0xF8.toByte() // media: fixed disk
        bb.putShort(24, 63) // sectors per track
        bb.putShort(26, 255) // heads
        bb.putInt(28, plan.partitionStartLba.toInt()) // hidden sectors
        bb.putInt(32, plan.totalSectors.toInt())
        bb.putInt(36, plan.fatSectors.toInt())
        bb.putInt(44, 2) // root cluster
        bb.putShort(48, 1) // FSInfo sector
        bb.putShort(50, 6) // backup boot sector
        b[64] = 0x80.toByte() // drive number
        b[66] = 0x29 // extended boot signature
        bb.putInt(67, volumeId)
        plan.label.padEnd(11).toByteArray(Charsets.US_ASCII).copyInto(b, 71)
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(b, 82)
        // Tiny real-mode stub (like mkfs.fat): print a message if someone BIOS-boots this partition.
        NON_BOOTABLE_STUB.copyInto(b, 90)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    private fun fsInfo(plan: Fat32Plan): ByteArray {
        val b = ByteArray(plan.bytesPerSector)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 0x41615252)
        bb.putInt(484, 0x61417272)
        bb.putInt(488, (plan.clusterCount - plan.usedClusters).toInt())
        bb.putInt(492, (plan.usedClusters + 2).toInt())
        bb.putInt(508, 0xAA550000.toInt())
        return b
    }

    companion object {
        const val MAX_FILE = 0xFFFF_FFFFL
        private const val MIN_CLUSTERS = 65525L
        private const val MAX_CLUSTERS = 0x0FFFFFF5L
        private const val EOC = 0x0FFFFFFF
        private const val ATTR_ARCHIVE = 0x20
        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_VOLUME_ID = 0x08
        private const val ATTR_LFN = 0x0F
        private val LFN_OFFSETS = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)

        /** mkfs.fat's boot stub: "This is not a bootable disk..." then wait for a key and reboot. */
        private val NON_BOOTABLE_STUB: ByteArray = run {
            val code = byteArrayOf(
                0x0E, 0x1F, 0xBE.toByte(), 0x77, 0x7C, 0xAC.toByte(), 0x22, 0xC0.toByte(), 0x74, 0x0B, 0x56, 0xB4.toByte(), 0x0E,
                0xBB.toByte(), 0x07, 0x00, 0xCD.toByte(), 0x10, 0x5E, 0xEB.toByte(), 0xF0.toByte(), 0x32, 0xE4.toByte(),
                0xCD.toByte(), 0x16, 0xCD.toByte(), 0x19, 0xEB.toByte(), 0xFE.toByte(),
            )
            code + "This drive boots in UEFI mode only. Select the USB entry marked UEFI in your PC's boot menu.\r\n\u0000".toByteArray(Charsets.US_ASCII)
        }

        fun clusterSizeFor(partBytes: Long): Int = when {
            partBytes < 260L * 1024 * 1024 -> 512
            partBytes < 8L * 1024 * 1024 * 1024 -> 4096
            partBytes < 16L * 1024 * 1024 * 1024 -> 8192
            partBytes < 32L * 1024 * 1024 * 1024 -> 16384
            else -> 32768
        }

        internal fun computeFatSectors(total: Long, reserved: Int, spc: Int, bps: Int): Long {
            var fat = 1L
            repeat(32) {
                val clusters = (total - reserved - 2 * fat) / spc
                val needed = ((clusters + 2) * 4 + bps - 1) / bps
                if (needed == fat) return fat
                fat = needed
            }
            return fat
        }

        internal fun lfnCount(name: String) = (name.length + 12) / 13

        fun sanitizeLabel(s: String): String {
            val cleaned = s.uppercase().map { c -> if (c in 'A'..'Z' || c in '0'..'9' || c in "_-") c else '_' }.joinToString("")
            return cleaned.take(11).ifBlank { "ANVIL" }
        }

        private fun dosDateTime(t: LocalDateTime): Pair<Short, Short> {
            val date = (((t.year - 1980) shl 9) or (t.monthValue shl 5) or t.dayOfMonth).toShort()
            val time = ((t.hour shl 11) or (t.minute shl 5) or (t.second / 2)).toShort()
            return date to time
        }
    }
}

/** 8.3 short-name generation (Windows-style "~N" tails) and the LFN checksum. */
object ShortNames {
    private const val VALID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#\$%&'()-@^_`{}~"

    /** Returns (11-byte padded short name, whether an LFN is needed). */
    fun generate(name: String, used: MutableSet<String>): Pair<ByteArray, Boolean> {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else ""
        val exact = base.isNotEmpty() && base.length <= 8 && ext.length <= 3 &&
            (base + ext).all { it in VALID } && name.count { it == '.' } <= 1
        if (exact) {
            val key = base.padEnd(8) + ext.padEnd(3)
            if (used.add(key)) return key.toByteArray(Charsets.US_ASCII) to false
        }
        fun clean(s: String) = s.uppercase().filter { it != ' ' && it != '.' }.map { if (it in VALID) it else '_' }.joinToString("")
        val b = clean(base).ifEmpty { "_" }
        val e = clean(ext).take(3)
        // Mixed/lower-case names that are otherwise valid 8.3 keep their exact form as the short name.
        if (base.length <= 8 && ext.length <= 3 && name.count { it == '.' } <= 1 && b.length == base.length && e.length == ext.length) {
            val key = b.padEnd(8) + e.padEnd(3)
            if (used.add(key)) return key.toByteArray(Charsets.US_ASCII) to true
        }
        var n = 1
        while (true) {
            val tail = "~$n"
            val key = (b.take(8 - tail.length) + tail).padEnd(8) + e.padEnd(3)
            if (used.add(key)) return key.toByteArray(Charsets.US_ASCII) to true
            n++
        }
    }

    fun checksum(sfn: ByteArray): Int {
        var sum = 0
        for (i in 0 until 11) sum = (((sum and 1) shl 7) + (sum ushr 1) + (sfn[i].toInt() and 0xFF)) and 0xFF
        return sum
    }
}
