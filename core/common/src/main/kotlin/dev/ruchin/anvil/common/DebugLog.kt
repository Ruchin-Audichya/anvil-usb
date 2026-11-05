package dev.ruchin.anvil.common

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Tiny in-memory ring buffer log. Backs the "Copy debug log" button: users can
 * paste it into a bug report without us shipping any tracking SDK.
 */
object DebugLog {
    private const val MAX_LINES = 2000
    private val lines = ConcurrentLinkedDeque<String>()

    @Volatile var sink: ((String) -> Unit)? = null

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(Instant.now()).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.pollFirst()
        sink?.invoke(line)
    }

    fun dump(): String = lines.joinToString("\n")
    fun clear() = lines.clear()
}
