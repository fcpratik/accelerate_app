package com.example.acceleratedcamera

import org.junit.Test
import org.junit.Assert.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Before
import org.junit.After

class MetricsTests {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testViewModelModeSwitchingToHybrid() = runTest {
        // Create a stub SettingsRepository
        val stubRepository = object : SettingsRepository {
            override val processingModeFlow: StateFlow<ProcessingMode> = MutableStateFlow(ProcessingMode.BASELINE)
            override val filterTypeFlow: StateFlow<FilterType> = MutableStateFlow(FilterType.SEPIA)
            override suspend fun saveProcessingMode(mode: ProcessingMode) {}
            override suspend fun saveFilterType(filter: FilterType) {}
        }
        
        val viewModel = MainViewModel(stubRepository)
        
        // Ensure initial state is not HYBRID to make test authentic
        assertNotEquals(ProcessingMode.HYBRID, viewModel.metricsState.value.currentMode)
        
        viewModel.setProcessingMode(ProcessingMode.HYBRID)
        
        val stateAfterModeSet = viewModel.metricsState.value
        assertEquals(ProcessingMode.HYBRID, stateAfterModeSet.currentMode)
        
        // Push a frame record to trigger the gpuActive/simdActive computation
        viewModel.recordFrame(0L, 10L, 20L)
        val metrics = viewModel.metricsState.value
        
        assertTrue("GPU should be active in HYBRID mode", metrics.gpuActive)
        assertTrue("SIMD should be active in HYBRID mode", metrics.simdActive)
    }

    @Test
    fun testHybridGaugeMath() {
        // If gpuMs = 10 and simdMs = 10, function calculates a 180-degree sweep for both.
        val (gpuSweep, simdSweep) = calculateSweepAngles(10L, 10L)
        assertEquals(180f, gpuSweep, 0.01f)
        assertEquals(180f, simdSweep, 0.01f)
        
        // Edge case: if both are 0, it should safely return 0 without triggering divide-by-zero bounds
        val (gpuZeros, simdZeros) = calculateSweepAngles(0L, 0L)
        assertEquals(0f, gpuZeros, 0.01f)
        assertEquals(0f, simdZeros, 0.01f)

        // Asymmetry test
        val (gpuMajor, simdMinor) = calculateSweepAngles(30L, 10L)
        assertEquals(270f, gpuMajor, 0.01f)
        assertEquals(90f, simdMinor, 0.01f)
    }
}
