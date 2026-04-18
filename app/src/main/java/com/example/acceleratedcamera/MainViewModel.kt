package com.example.acceleratedcamera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProcessingMode { BASELINE, SIMD, GPU, HYBRID }
enum class FilterType { SEPIA, GAUSSIAN_BLUR, SOBEL_EDGE, EMBOSS, VIGNETTE, FULL_CHAIN, COMIC }

data class CameraMetrics(
    val currentMode: ProcessingMode = ProcessingMode.BASELINE,
    val currentFilter: FilterType = FilterType.SEPIA,
    val fps: Int = 0,
    val frameLatencyMs: Long = 0L,
    val e2eLatencyMs: Long = 0L,
    val latencyHistory: List<Long> = emptyList(),
    val logs: List<String> = emptyList(),
    val batteryPercent: Int = -1,
    val batteryTempC: Float = -1f,
    val thermalState: Int = -1,
    val simdActive: Boolean = false,
    val gpuActive: Boolean = false,
    val baselineLatencyMs: Long = 0L,
    val gpuPortionMs: Long = 0L,        // GPU processing time in HYBRID mode
    val simdPortionMs: Long = 0L,       // SIMD processing time in HYBRID mode

    // --- Pipeline metrics ---
    val captureStageMs: Long = 0L,
    val computeStageMs: Long = 0L,
    val displayStageMs: Long = 0L,
    val droppedFrames: Long = 0L,
    val simdUtilization: Float = 0f,    // 0..1 fraction of wall-clock SIMD stage is busy
    val gpuUtilization: Float = 0f,     // 0..1 fraction of wall-clock GPU stage is busy
    val displayUtilization: Float = 0f  // 0..1 fraction of wall-clock display is busy
)

// 1. Pass the repository into the constructor
class MainViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {
    private val _metricsState = MutableStateFlow(CameraMetrics())
    val metricsState = _metricsState.asStateFlow()

    private val frameTimestamps = ArrayDeque<Long>()

    init {
        // Always start fresh with BASELINE + SEPIA (the defaults in CameraMetrics).
        // DataStore still persists user changes during the session, but we don't
        // restore them on startup — this ensures the Speedup metric and baseline
        // are always valid from the first frame.
    }

    fun setProcessingMode(mode: ProcessingMode) {
        _metricsState.update { it.copy(currentMode = mode) }
        logEvent("Switched to ${mode.name} mode")

        // 3. Save the new mode to DataStore in the background
        viewModelScope.launch {
            settingsRepository.saveProcessingMode(mode)
        }
    }

    fun setFilterType(filter: FilterType) {
        val benchmarking = isBenchmarkingState.value
        _metricsState.update { state ->
            if (benchmarking) {
                // During benchmark: change filter but do NOT reset mode or clear history.
                // The benchmark controls the mode; resetting it would break the test.
                state.copy(currentFilter = filter)
            } else {
                // Normal user interaction: reset to baseline for fair comparison.
                state.copy(
                    currentFilter = filter,
                    currentMode = ProcessingMode.BASELINE,
                    baselineLatencyMs = 0L,
                    latencyHistory = emptyList()
                )
            }
        }
        if (benchmarking) {
            logEvent("Bench: ${filter.name}")
        } else {
            logEvent("Switched to ${filter.name} (Reset to Baseline)")
        }

        viewModelScope.launch {
            settingsRepository.saveFilterType(filter)
            if (!benchmarking) {
                settingsRepository.saveProcessingMode(ProcessingMode.BASELINE)
            }
        }
    }

    private fun logEvent(message: String) {
        _metricsState.update { state ->
            val newLogs = (state.logs + message).takeLast(5)
            state.copy(logs = newLogs)
        }
    }

    fun recordFrame(processStartTime: Long, processEndTime: Long, frameRenderedTime: Long) {
        val frameLatency = processEndTime - processStartTime
        val e2eLatency = frameRenderedTime - processStartTime

        frameTimestamps.addLast(frameRenderedTime)

        val oneSecondAgo = frameRenderedTime - 1000
        while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < oneSecondAgo) {
            frameTimestamps.removeFirst()
        }

        val calculatedFps = frameTimestamps.size

        _metricsState.update { state ->
            val newHistory = (state.latencyHistory + frameLatency).takeLast(50)

            // Store baseline latency if we're in baseline mode
            val newBaselineLatency = if(state.currentMode == ProcessingMode.BASELINE) {
                frameLatency }
            else { state.baselineLatencyMs }

            state.copy(
                frameLatencyMs = frameLatency,
                e2eLatencyMs = e2eLatency,
                latencyHistory = newHistory,
                fps = calculatedFps,
                baselineLatencyMs = newBaselineLatency,
                simdActive = state.currentMode == ProcessingMode.SIMD || state.currentMode == ProcessingMode.HYBRID,
                gpuActive = state.currentMode == ProcessingMode.GPU || state.currentMode == ProcessingMode.HYBRID
            )
        }
    }

    fun recordHybridTiming(gpuMs: Long, simdMs: Long) {
        _metricsState.update { it.copy(gpuPortionMs = gpuMs, simdPortionMs = simdMs) }
    }

    /**
     * Called by FramePipeline after each frame exits the display stage.
     * Rolls FPS and latency using the display-end timestamp as the "rendered" time.
     */
    fun recordPipelineFrame(
        captureMs: Long, computeMs: Long, displayMs: Long, e2eMs: Long
    ) {
        val nowMs = System.currentTimeMillis()
        frameTimestamps.addLast(nowMs)
        val oneSecondAgo = nowMs - 1000
        while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < oneSecondAgo) {
            frameTimestamps.removeFirst()
        }
        val calculatedFps = frameTimestamps.size

        _metricsState.update { state ->
            val newHistory = (state.latencyHistory + computeMs).takeLast(50)
            val newBaseline = if (state.currentMode == ProcessingMode.BASELINE) computeMs
            else state.baselineLatencyMs
            state.copy(
                fps = calculatedFps,
                frameLatencyMs = computeMs,
                e2eLatencyMs = e2eMs,
                captureStageMs = captureMs,
                computeStageMs = computeMs,
                displayStageMs = displayMs,
                latencyHistory = newHistory,
                baselineLatencyMs = newBaseline,
                simdActive = state.currentMode == ProcessingMode.SIMD ||
                        state.currentMode == ProcessingMode.HYBRID ||
                        state.simdUtilization > 0.05f,
                gpuActive  = state.currentMode == ProcessingMode.GPU ||
                        state.currentMode == ProcessingMode.HYBRID
            )
        }
    }

    fun recordDroppedFrame() {
        _metricsState.update { it.copy(droppedFrames = it.droppedFrames + 1) }
    }

    fun recordUtilization(simdFrac: Float, gpuFrac: Float, displayFrac: Float) {
        _metricsState.update {
            it.copy(
                simdUtilization = simdFrac,
                gpuUtilization = gpuFrac,
                displayUtilization = displayFrac
            )
        }
    }

    fun updateSystemMetrics(percent: Int, tempC: Float, thermal: Int) {
        _metricsState.update {
            it.copy(batteryPercent = percent, batteryTempC = tempC, thermalState = thermal)
        }
    }

    val isBenchmarkingState = MutableStateFlow(false)

    // Lets the benchmark tell the UI to enable/disable processing.
    val benchmarkProcessingEnabled = MutableStateFlow(false)

    private var benchmarkJob: kotlinx.coroutines.Job? = null

    fun startBenchmark(durationPerModeMs: Long) {
        if (isBenchmarkingState.value) return
        benchmarkJob = viewModelScope.launch {
            runBenchmark(durationPerModeMs)
        }
    }

    fun stopBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        isBenchmarkingState.value = false
        benchmarkProcessingEnabled.value = false
        logEvent("Benchmark Stopped.")
    }

    private suspend fun runBenchmark(durationPerModeMs: Long) {
        isBenchmarkingState.value = true
        benchmarkProcessingEnabled.value = true
        logEvent("Bench: ${durationPerModeMs/1000}s per combo")

        kotlinx.coroutines.delay(500)

        val modesToTest = ProcessingMode.values()
        val filtersToTest = FilterType.values()

        try {
            // For each filter, cycle baseline → simd → gpu → hybrid
            for (filter in filtersToTest) {
                for (mode in modesToTest) {
                    setProcessingMode(mode)
                    setFilterType(filter)
                    logEvent("${mode.name} + ${filter.name}...")

                    kotlinx.coroutines.delay(500)
                    _metricsState.update { it.copy(latencyHistory = emptyList()) }
                    kotlinx.coroutines.delay(durationPerModeMs)

                    val currentFPS = _metricsState.value.fps
                    val avgLatency = if (_metricsState.value.latencyHistory.isNotEmpty()) {
                        _metricsState.value.latencyHistory.average()
                    } else { -1.0 }

                    logEvent("[${mode.name}][${filter.name}] FPS:$currentFPS Lat:${"%.1f".format(avgLatency)}ms")
                }
            }
            logEvent("Benchmark Complete!")
        } finally {
            isBenchmarkingState.value = false
            benchmarkProcessingEnabled.value = false
        }
    }
}