package dev.ruchin.anvil.writer

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Something we can stream an OS image from: a local file, a SAF content:// URI, or (milestone 5) an HTTP stream. */
interface ImageSource {
    val displayName: String
    /** Exact size in bytes. Must be known up-front so we can refuse too-small drives before erasing. */
    val sizeBytes: Long
    fun open(): InputStream
}

class FileImageSource(private val file: File) : ImageSource {
    override val displayName: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override fun open(): InputStream = FileInputStream(file)
}

class ByteArrayImageSource(private val bytes: ByteArray, override val displayName: String = "memory") : ImageSource {
    override val sizeBytes: Long get() = bytes.size.toLong()
    override fun open(): InputStream = bytes.inputStream()
}
