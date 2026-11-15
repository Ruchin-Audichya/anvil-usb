package dev.ruchin.anvil.writer

import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.VirtualFile
import dev.ruchin.anvil.fat.Fat32Writer
import dev.ruchin.anvil.fat.FatDir

/** One file of a user-picked driver folder; [relativePath] uses '/' ("x64/iaStorVD.inf"). */
class DriverFile(val relativePath: String, val content: VirtualFile)

/**
 * A folder of drivers (typically the laptop maker's Intel RST/VMD storage driver) copied to
 * `\Drivers\` on the Windows USB drive, so "Load driver → Browse" in Setup finds it immediately.
 */
class DriverFolder(val displayName: String, val files: List<DriverFile>) {
    val totalBytes: Long get() = files.sumOf { it.content.size }

    /** Adds every file under [TARGET] in [tree], keeping the folder structure. */
    fun addTo(tree: FatDir) {
        if (files.isEmpty()) throw AnvilException("The drivers folder is empty. Pick the folder that contains the driver's .inf files.")
        if (files.size > MAX_FILES) {
            throw AnvilException("The drivers folder has too many files (${files.size}). Pick just the storage driver's folder.", "files ${files.size}")
        }
        for (f in files) {
            val parts = sanitize(f.relativePath)
            if (f.content.size > Fat32Writer.MAX_FILE) {
                throw AnvilException("${parts.last()} is larger than 4 GB and can't be copied to the USB drive.", "oversized driver ${f.relativePath}")
            }
            tree.put((listOf(TARGET) + parts).joinToString("/"), f.content)
        }
    }

    companion object {
        const val TARGET = "Drivers"
        const val MAX_FILES = 5_000
        private val ILLEGAL = Regex("""[\\:*?"<>|\u0000-\u001F]""")

        /** Splits a relative path into FAT-safe components: no "..", no empty parts, no characters FAT rejects. */
        fun sanitize(relativePath: String): List<String> {
            val parts = relativePath.replace('\\', '/').split('/')
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { ILLEGAL.replace(it, "_").take(255) }
            if (parts.isEmpty()) throw AnvilException("A driver file has no usable name.", "bad driver path '$relativePath'")
            return parts
        }
    }
}
