package dev.ruchin.anvil.common

import java.util.Locale
import kotlin.math.abs

/** Human-readable byte formatting. Uses binary units (GiB) but labels them "GB" like most phones do. */
object ByteSize {
    const val KiB = 1024L
    const val MiB = 1024L * KiB
    const val GiB = 1024L * MiB

    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val b = abs(bytes)
        return when {
            b >= GiB -> String.format(locale, "%.2f GB", bytes.toDouble() / GiB)
            b >= MiB -> String.format(locale, "%.1f MB", bytes.toDouble() / MiB)
            b >= KiB -> String.format(locale, "%.0f KB", bytes.toDouble() / KiB)
            else -> "$bytes B"
        }
    }

    fun formatRate(bytesPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        format(bytesPerSecond.toLong(), locale) + "/s"

    /** Marketing capacity of a USB drive ("16 GB"), i.e. decimal gigabytes rounded. */
    fun marketingCapacity(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) "${Math.round(gb)} GB" else "${Math.round(bytes / 1_000_000.0)} MB"
    }
}
