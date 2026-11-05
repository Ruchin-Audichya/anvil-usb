package dev.ruchin.anvil.common

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Positional (random-access) byte source: an ISO on the phone's storage, a file
 * inside that ISO, a WIM inside that file… Reads are thread-safe.
 */
interface RandomAccessSource : Closeable {
    val size: Long

    /** Reads exactly [len] bytes at [position] or throws [EOFException]. */
    fun readFully(position: Long, dst: ByteArray, offset: Int = 0, len: Int = dst.size)

    fun readBytes(position: Long, len: Int): ByteArray = ByteArray(len).also { readFully(position, it) }

    override fun close() {}
}

/** Backed by a [FileChannel] — works for host files and Android `ParcelFileDescriptor`s alike. */
class ChannelSource(private val channel: FileChannel, private val onClose: () -> Unit = {}) : RandomAccessSource {
    override val size: Long = channel.size()

    override fun readFully(position: Long, dst: ByteArray, offset: Int, len: Int) {
        val bb = ByteBuffer.wrap(dst, offset, len)
        var pos = position
        while (bb.hasRemaining()) {
            val r = channel.read(bb, pos)
            if (r < 0) throw EOFException("EOF at $pos (size $size)")
            pos += r
        }
    }

    override fun close() { channel.close(); onClose() }

    companion object {
        fun open(file: File): ChannelSource {
            val raf = RandomAccessFile(file, "r")
            return ChannelSource(raf.channel) { raf.close() }
        }
    }
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()
    override fun readFully(position: Long, dst: ByteArray, offset: Int, len: Int) {
        if (position < 0 || position + len > bytes.size) throw EOFException("read $position+$len beyond ${bytes.size}")
        System.arraycopy(bytes, position.toInt(), dst, offset, len)
    }
}

/** A run of bytes that makes up part of a [VirtualFile]. */
sealed interface Segment {
    val length: Long

    /** Literal bytes we generate (WIM headers, blob tables…). */
    class Bytes(val data: ByteArray) : Segment {
        override val length: Long get() = data.size.toLong()
    }

    /** A range of an underlying source (e.g. an extent inside the ISO). */
    class Range(val source: RandomAccessSource, val offset: Long, override val length: Long) : Segment

    /** [length] zero bytes (sparse/unrecorded UDF extents). */
    class Zeros(override val length: Long) : Segment
}

/**
 * A file described as a list of segments instead of bytes on disk. This is how
 * Anvil copies a 6 GB install.wim out of an ISO and into split .swm parts
 * on a FAT32 USB drive without ever writing a temporary file to the phone.
 */
class VirtualFile(val segments: List<Segment>) {
    val size: Long = segments.sumOf { it.length }

    /** Segments covering [offset, offset+length) of this file. */
    fun slice(offset: Long, length: Long): List<Segment> {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "slice $offset+$length of $size" }
        val out = ArrayList<Segment>()
        var pos = 0L
        var remaining = length
        var start = offset
        for (s in segments) {
            if (remaining == 0L) break
            val segEnd = pos + s.length
            if (segEnd > start) {
                val within = start - pos
                val n = minOf(s.length - within, remaining)
                out += when (s) {
                    is Segment.Range -> Segment.Range(s.source, s.offset + within, n)
                    is Segment.Zeros -> Segment.Zeros(n)
                    is Segment.Bytes -> Segment.Bytes(s.data.copyOfRange(within.toInt(), (within + n).toInt()))
                }
                remaining -= n
                start += n
            }
            pos = segEnd
        }
        return out
    }

    /** Random access view (used to parse a WIM that lives inside the ISO). */
    fun asSource(): RandomAccessSource = object : RandomAccessSource {
        override val size: Long get() = this@VirtualFile.size
        override fun readFully(position: Long, dst: ByteArray, offset: Int, len: Int) {
            if (position < 0 || position + len > size) throw EOFException("read $position+$len beyond $size")
            var o = offset
            for (s in slice(position, len.toLong())) {
                val n = s.length.toInt()
                when (s) {
                    is Segment.Range -> s.source.readFully(s.offset, dst, o, n)
                    is Segment.Bytes -> System.arraycopy(s.data, 0, dst, o, n)
                    is Segment.Zeros -> dst.fill(0, o, o + n)
                }
                o += n
            }
        }
    }

    /** Merges adjacent ranges of the same source so writers issue a few large reads instead of many small ones. */
    fun coalesced(): VirtualFile {
        val out = ArrayList<Segment>(segments.size)
        for (s in segments) {
            if (s.length == 0L) continue
            val last = out.lastOrNull()
            if (last is Segment.Range && s is Segment.Range && last.source === s.source && last.offset + last.length == s.offset) {
                out[out.lastIndex] = Segment.Range(s.source, last.offset, last.length + s.length)
            } else if (last is Segment.Zeros && s is Segment.Zeros) {
                out[out.lastIndex] = Segment.Zeros(last.length + s.length)
            } else {
                out += s
            }
        }
        return VirtualFile(out)
    }

    companion object {
        fun of(source: RandomAccessSource) = VirtualFile(listOf(Segment.Range(source, 0, source.size)))
    }
}
