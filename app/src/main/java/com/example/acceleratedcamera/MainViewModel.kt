package com.example.acceleratedcamera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProcessingMode { BASELINE, SIMD, GPU }

data class CameraMetrics(
    val currentMode: ProcessingMode = ProcessingMode.BASELINE,
    val fps: Int = 0,
    val frameLatencyMs: Long = 0L,
    val e2eLatencyMs: Long = 0L,
    val latencyHistory: List<Long> = emptyList(),
    val logs: List<String> = emptyList()
)

// 1. Pass the repository into the constructor
class MainViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {
    private val _metricsState = MutableStateFlow(CameraMetrics())
    val metricsState = _metricsState.asStateFlow()

    private val frameTimestamps = ArrayDeque<Long>()

    init {
        // 2. Load the saved mode as soon as the ViewModel is created
        viewModelScope.launch {
            settingsRepository.processingModeFlow.collect { savedMode ->
                _metricsState.update { it.copy(currentMode = savedMode) }
            }
        }
    }

    fun setProcessingMode(mode: ProcessingMode) {
        _metricsState.update { it.copy(currentMode = mode) }
        logEvent("Switched to ${mode.name} mode")

        // 3. Save the new mode to DataStore in the background
        viewModelScope.launch {
            settingsRepository.saveProcessingMode(mode)
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

            state.copy(
                frameLatencyMs = frameLatency,
                e2eLatencyMs = e2eLatency,
                latencyHistory = newHistory,
                fps = calculatedFps
            )
        }
    }
}