package dev.ruchin.anvil.image

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What kind of image the user picked, and therefore how it must be written. */
enum class ImageKind {
    /** isohybrid ISO (Ubuntu, Fedora, Mint, Debian…): has an MBR/GPT, boots when copied byte-for-byte. */
    HYBRID_ISO,
    /** Raw disk image (.img) with a partition table. Raw write. */
    DISK_IMAGE,
    /** Windows installer ISO (UDF, no partition table). Must be extracted to a FAT32/NTFS stick (milestone 2). */
    WINDOWS_ISO,
    /** Plain ISO9660 without hybrid MBR. Raw write will likely NOT boot from USB. */
    NON_HYBRID_ISO,
    UNKNOWN,
}

data class ImageInfo(
    val kind: ImageKind,
    val volumeLabel: String?,
    val hasMbr: Boolean,
    val hasGpt: Boolean,
    val hasElTorito: Boolean,
    val hasUdf: Boolean,
) {
    /** True when a byte-for-byte write produces a bootable USB stick. */
    val rawWritable get() = kind == ImageKind.HYBRID_ISO || kind == ImageKind.DISK_IMAGE
}

/**
 * Classifies a disk/ISO image from its first 64 KiB. No filesystem library needed:
 *  - MBR boot signature 0x55AA @510 with at least one partition entry
 *  - GPT header "EFI PART" @512 (or @4096 for 4Kn images)
 *  - ISO9660 Primary Volume Descriptor "CD001" @ sector 16 (32 KiB)
 *  - El Torito boot record @ sector 17
 *  - UDF Volume Recognition Sequence ("BEA01"/"NSR02"/"NSR03") after the ISO descriptors
 */
object ImageInspector {
    const val HEADER_BYTES = 64 * 1024
    private const val ISO_SECTOR = 2048

    fun inspect(header: ByteArray, length: Int = header.size): ImageInfo {
        val hasMbr = hasMbrPartitionTable(header, length)
        val hasGpt = hasSignature(header, length, 512, "EFI PART") || hasSignature(header, length, 4096, "EFI PART")
        val pvdOffset = 16 * ISO_SECTOR
        val isIso = hasSignature(header, length, pvdOffset + 1, "CD001")
        val volumeLabel = if (isIso && length >= pvdOffset + 72) {
            String(header, pvdOffset + 40, 32, Charsets.US_ASCII).trim().ifEmpty { null }
        } else null
        val hasElTorito = isIso && hasSignature(header, length, 17 * ISO_SECTOR + 7, "EL TORITO SPECIFICATION")
        val hasUdf = isIso && scanVrsForUdf(header, length)

        val kind = when {
            isIso && (hasMbr || hasGpt) -> ImageKind.HYBRID_ISO
            isIso && looksLikeWindows(volumeLabel, hasUdf) -> ImageKind.WINDOWS_ISO
            isIso -> ImageKind.NON_HYBRID_ISO
            hasMbr || hasGpt -> ImageKind.DISK_IMAGE
            else -> ImageKind.UNKNOWN
        }
        return ImageInfo(kind, volumeLabel, hasMbr, hasGpt, hasElTorito, hasUdf)
    }

    /** Windows ISOs are UDF-bridge discs whose ISO9660 tree is a stub; labels follow Microsoft's naming. */
    internal fun looksLikeWindows(label: String?, hasUdf: Boolean): Boolean {
        val l = label?.uppercase() ?: return false
        val msPrefixes = listOf("CCCOMA_", "CCSA_", "CPBA_", "CENA_", "CCCOMA", "J_CCSA", "ESD-ISO", "WIN10", "WIN11", "WINDOWS")
        return hasUdf && msPrefixes.any { l.startsWith(it) } ||
            Regex("^[A-Z0-9_]*(X64|X86|A64|ARM64)FRE[A-Z0-9_]*").containsMatchIn(l)
    }

    private fun scanVrsForUdf(b: ByteArray, len: Int): Boolean {
        // Volume descriptors are one per 2 KiB sector starting at 16; VRS follows the ISO terminator.
        for (sector in 16 until 32) {
            val off = sector * ISO_SECTOR + 1
            if (off + 5 > len) break
            val id = String(b, off, 5, Charsets.US_ASCII)
            if (id == "NSR02" || id == "NSR03") return true
        }
        return false
    }

    private fun hasMbrPartitionTable(b: ByteArray, len: Int): Boolean {
        if (len < 512) return false
        if ((b[510].toInt() and 0xFF) != 0x55 || (b[511].toInt() and 0xFF) != 0xAA) return false
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            val e = 446 + i * 16
            val type = b[e + 4].toInt() and 0xFF
            val sectors = bb.getInt(e + 12).toLong() and 0xFFFFFFFFL
            val status = b[e].toInt() and 0xFF
            if (type != 0 && sectors > 0 && (status == 0 || status == 0x80)) return true
        }
        return false
    }

    private fun hasSignature(b: ByteArray, len: Int, offset: Int, sig: String): Boolean {
        if (offset + sig.length > len) return false
        for (i in sig.indices) if (b[offset + i] != sig[i].code.toByte()) return false
        return true
    }
}
