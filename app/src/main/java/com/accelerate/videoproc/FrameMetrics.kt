package com.accelerate.videoproc

/**
 * Per-frame performance metrics for the dashboard.
 */
data class FrameMetrics(
    val fps: Double = 0.0,
    val processLatencyMs: Double = 0.0,
    val endToEndLatencyMs: Double = 0.0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameCount: Long = 0,
    val executionMode: ExecutionMode = ExecutionMode.BASELINE,
    val filterType: FilterType = FilterType.SOBEL_EDGE
)
