package dev.ruchin.anvil.common

/**
 * Computes a smoothed transfer speed and ETA from (bytesDone, timestamp) samples.
 * Uses an exponentially weighted moving average so the UI number doesn't jitter,
 * while still reacting to real slow-downs (thermal throttling, flaky Wi-Fi).
 */
class TransferMeter(
    private val clock: () -> Long = System::nanoTime,
    private val smoothing: Double = 0.25,
    private val minSampleNanos: Long = 250_000_000L,
) {
    private var lastBytes = -1L
    private var lastTime = 0L
    private var rate = 0.0

    /** Bytes per second, smoothed. 0 until enough samples arrived. */
    val bytesPerSecond: Double get() = rate

    fun reset(bytesDone: Long = 0) {
        lastBytes = bytesDone
        lastTime = clock()
        rate = 0.0
    }

    fun update(bytesDone: Long): Double {
        val now = clock()
        if (lastBytes < 0) {
            lastBytes = bytesDone; lastTime = now; return rate
        }
        val dt = now - lastTime
        if (dt < minSampleNanos) return rate
        val instant = (bytesDone - lastBytes).coerceAtLeast(0) * 1e9 / dt
        rate = if (rate == 0.0) instant else rate + smoothing * (instant - rate)
        lastBytes = bytesDone
        lastTime = now
        return rate
    }

    /** Remaining seconds, or null when unknown. */
    fun etaSeconds(bytesDone: Long, total: Long): Long? {
        if (total <= 0 || rate < 1.0) return null
        return ((total - bytesDone).coerceAtLeast(0) / rate).toLong()
    }
}

fun formatEta(seconds: Long?): String = when {
    seconds == null -> "—"
    seconds >= 3600 -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
    seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
    else -> "${seconds}s"
}
