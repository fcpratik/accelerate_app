package com.accelerate.videoproc

/**
 * Execution modes for the video processing pipeline.
 * Day 20: Only BASELINE is active.
 * Day 40: SIMD mode will use ARM NEON.
 * Day 70: GPU and HYBRID modes will be implemented.
 */
enum class ExecutionMode(val displayName: String) {
    BASELINE("BASELINE"),
    SIMD("SIMD"),
    GPU("GPU"),
    HYBRID("HYBRID");

    fun next(): ExecutionMode {
        val modes = values()
        return modes[(ordinal + 1) % modes.size]
    }
}
