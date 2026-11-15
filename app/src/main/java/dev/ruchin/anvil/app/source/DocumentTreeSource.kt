package dev.ruchin.anvil.app.source

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.RandomAccessSource
import dev.ruchin.anvil.common.VirtualFile
import dev.ruchin.anvil.writer.DriverFile
import dev.ruchin.anvil.writer.DriverFolder
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A folder the user picked with the SAF folder picker (ACTION_OPEN_DOCUMENT_TREE). */
class PickedFolder(val treeUri: Uri, val name: String, val files: List<Entry>) {
    class Entry(val relativePath: String, val uri: Uri, val size: Long)

    val totalBytes: Long get() = files.sumOf { it.size }

    /**
     * Turns the folder into a [DriverFolder] for the Windows writer. Files are opened lazily, one at
     * a time as the FAT32 writer reaches them; close the returned [Closeable] when the write ends.
     */
    fun toDriverFolder(context: Context): Pair<DriverFolder, Closeable> {
        val sources = files.map { LazyDocumentSource(context, it.uri, it.size) }
        val folder = DriverFolder(name, files.zip(sources) { e, s -> DriverFile(e.relativePath, VirtualFile.of(s)) })
        return folder to Closeable { sources.forEach { runCatching { it.close() } } }
    }

    companion object {
        private const val MAX_FILES = DriverFolder.MAX_FILES

        /** Lists every file under [treeUri] recursively. Run off the main thread. */
        fun read(context: Context, treeUri: Uri): PickedFolder {
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            var rootName = "Drivers"
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) rootName = it.getString(0) ?: rootName }

            val out = mutableListOf<Entry>()
            fun walk(docId: String, prefix: String) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (out.size > MAX_FILES) return
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, path)
                        } else {
                            val size = if (c.isNull(3)) -1L else c.getLong(3)
                            out += Entry(path, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size)
                        }
                    }
                }
            }
            walk(rootId, "")
            if (out.any { it.size < 0 }) {
                throw AnvilException("Some files in this folder have an unknown size. Copy the folder to your phone's storage and pick it from there.")
            }
            return PickedFolder(treeUri, rootName, out)
        }
    }
}

/** Opens a document only when it's first read, so a big driver folder doesn't hold hundreds of descriptors. */
private class LazyDocumentSource(private val context: Context, private val uri: Uri, override val size: Long) : RandomAccessSource {
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null

    @Synchronized
    private fun channel(): FileChannel = channel ?: run {
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw AnvilException("Couldn't open a file in the drivers folder. Pick the folder again.")
        pfd = fd
        FileInputStream(fd.fileDescriptor).channel.also { channel = it }
    }

    override fun readFully(position: Long, dst: ByteArray, offset: Int, len: Int) {
        val ch = channel()
        val bb = ByteBuffer.wrap(dst, offset, len)
        var pos = position
        while (bb.hasRemaining()) {
            val r = synchronized(this) { ch.read(bb, pos) }
            if (r < 0) throw EOFException("driver file changed while writing: $uri")
            pos += r
        }
    }

    @Synchronized
    override fun close() {
        runCatching { channel?.close() }
        runCatching { pfd?.close() }
        channel = null; pfd = null
    }
}
