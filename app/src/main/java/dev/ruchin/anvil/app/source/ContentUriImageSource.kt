package dev.ruchin.anvil.app.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.ChannelSource
import dev.ruchin.anvil.common.RandomAccessSource
import java.io.FileInputStream
import dev.ruchin.anvil.writer.ImageSource
import java.io.InputStream

/** An ISO/IMG the user picked through the Storage Access Framework (no storage permission needed). */
class ContentUriImageSource(private val context: Context, val uri: Uri) : ImageSource {
    override val displayName: String
    override val sizeBytes: Long

    init {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size <= 0) {
            size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }
        displayName = name ?: uri.lastPathSegment ?: "image.iso"
        sizeBytes = size
    }

    override fun open(): InputStream = context.contentResolver.openInputStream(uri)
        ?: throw AnvilException("Couldn't open the selected file. Pick it again.")

    /**
     * Seekable access (needed to read a Windows ISO's UDF file system). Works for
     * local files and documents from local storage providers; cloud-only documents
     * that can't be opened as a seekable descriptor fail with a clear message.
     */
    fun openRandomAccess(): RandomAccessSource {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw AnvilException("Couldn't open the selected file. Pick it again.")
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return try {
            channel.position(0)
            ChannelSource(channel) { pfd.close() }
        } catch (e: Exception) {
            pfd.close()
            throw AnvilException("This file can't be read directly. Copy the ISO to your phone's storage (e.g. Downloads) and pick it from there.", cause = e)
        }
    }

    /** First [n] bytes, for [dev.ruchin.anvil.image.ImageInspector]. */
    fun readHeader(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        open().use { input ->
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
        }
        return if (read == n) buf else buf.copyOf(read)
    }
}
