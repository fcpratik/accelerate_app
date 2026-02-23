package com.accelerate.videoproc

/**
 * Tracks real-time performance metrics using a rolling window.
 * Includes per-pipeline-stage timing for hardware mapping visibility.
 */
class PerformanceTracker(private val windowSize: Int = 30) {

    private val frameTimes = LongArray(windowSize)
    private val processLatencies = DoubleArray(windowSize)
    private val endToEndLatencies = DoubleArray(windowSize)

    // Per-stage breakdown for hardware mapping
    private val yuvLatencies = DoubleArray(windowSize)
    private val filterLatencies = DoubleArray(windowSize)
    private val displayLatencies = DoubleArray(windowSize)

    private var index = 0
    private var count = 0L

    fun recordFrame(
        processLatencyMs: Double,
        endToEndLatencyMs: Double,
        yuvLatencyMs: Double = 0.0,
        filterLatencyMs: Double = 0.0,
        displayLatencyMs: Double = 0.0
    ) {
        val now = System.nanoTime()
        val pos = (count % windowSize).toInt()

        frameTimes[pos] = now
        processLatencies[pos] = processLatencyMs
        endToEndLatencies[pos] = endToEndLatencyMs
        yuvLatencies[pos] = yuvLatencyMs
        filterLatencies[pos] = filterLatencyMs
        displayLatencies[pos] = displayLatencyMs

        count++
        index = pos
    }

    fun getFps(): Double {
        val filled = count.coerceAtMost(windowSize.toLong()).toInt()
        if (filled < 2) return 0.0
        val newest = frameTimes[index]
        val oldestIdx = if (count >= windowSize) (index + 1) % windowSize else 0
        val oldest = frameTimes[oldestIdx]
        val durationSec = (newest - oldest) / 1_000_000_000.0
        return if (durationSec > 0) (filled - 1) / durationSec else 0.0
    }

    fun getAvgProcessLatency(): Double = getAvg(processLatencies)
    fun getAvgEndToEndLatency(): Double = getAvg(endToEndLatencies)
    fun getAvgYuvLatency(): Double = getAvg(yuvLatencies)
    fun getAvgFilterLatency(): Double = getAvg(filterLatencies)
    fun getAvgDisplayLatency(): Double = getAvg(displayLatencies)

    private fun getAvg(arr: DoubleArray): Double {
        val filled = count.coerceAtMost(windowSize.toLong()).toInt()
        if (filled == 0) return 0.0
        var sum = 0.0
        for (i in 0 until filled) sum += arr[i]
        return sum / filled
    }

    val frameCount: Long get() = count

    fun reset() {
        count = 0
        index = 0
    }
}
