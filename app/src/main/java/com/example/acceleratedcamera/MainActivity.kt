package com.example.acceleratedcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.ui.tooling.preview.Preview
import com.example.acceleratedcamera.ui.theme.AcceleratedCameraTheme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import android.Manifest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.example.acceleratedcamera.ui.theme.AcceleratedCameraTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState


//add android view
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner


//camera preview
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Matrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.rememberUpdatedState

// sparkline
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

// mode selector
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState


import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

// snapshot

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

// metrics
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue

// GPU / coroutines
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Create the repository
        val settingsRepository = DataStoreSettingsRepository(applicationContext)

        enableEdgeToEdge()
        setContent {
            AcceleratedCameraTheme {
                val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (cameraPermissionState.status.isGranted) {
                        // 2. Pass the repository to our skeleton
                        AppScreenSkeleton(
                            repository = settingsRepository,
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                                Text("Grant Camera Permission")
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun CameraPreview(
    isProcessingEnabled: Boolean,
    viewModel: MainViewModel,
    takeSnapshot: Boolean, // <-- Accept the trigger
    onSnapshotTaken: () -> Unit, // <-- Accept the callback
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1. Get the current state from the ViewModel
    val metrics by viewModel.metricsState.collectAsState()

    // 2. Create our live pointers!
    val liveProcessingEnabled by rememberUpdatedState(isProcessingEnabled)
    val liveMode by rememberUpdatedState(metrics.currentMode) // <-- New live pointer!
    val liveFilter by rememberUpdatedState(metrics.currentFilter) // <-- Add Filter pointer
    val liveTakeSnapshot by rememberUpdatedState(takeSnapshot) // <-- Live pointer
    val liveOnSnapshotTaken by rememberUpdatedState(onSnapshotTaken) // <-- Live pointer

    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    val processingScope = remember { CoroutineScope(Dispatchers.Default) }
    val bufferPool = remember { FrameBufferPool() }

    // GPU processor and its dedicated single-thread executor (EGL is thread-bound)
    val gpuExecutor = remember { Executors.newSingleThreadExecutor() }
    val gpuProcessor = remember { GpuProcessor(context) }
    LaunchedEffect(Unit) {
        withContext(gpuExecutor.asCoroutineDispatcher()) {
            gpuProcessor.initialize()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            gpuExecutor.execute { gpuProcessor.release() }
            gpuExecutor.shutdown()
        }
    }
    // --- Pipeline wiring ---------------------------------------------------
    // One FramePipeline drives all 4 modes. The compute stage runs the filter
    // selected for the frame (captured at submit time, so mid-pipeline mode
    // switches don't produce half-processed frames). Capture runs on the
    // CameraX callback thread via submitFrame(); compute + display run on
    // their own pipeline threads.

    val pipeline = remember {
        FramePipeline(
            onFrameReady = { bmp ->
                // Post latest finished bitmap to the UI. Compose state writes
                // must hit the main thread — MutableState handles that.
                currentFrame = bmp
            },
            onStageTiming = { capMs, cmpMs, dspMs, e2eMs ->
                viewModel.recordPipelineFrame(capMs, cmpMs, dspMs, e2eMs)
            },
            onFrameDropped = { viewModel.recordDroppedFrame() },
            onUtilization = { simdFrac, gpuFrac, dspFrac ->
                // Capture stage does SIMD-natured work (YUV->ARGB, prep).
                // Compute stage is where GPU (or SIMD) actually runs the filter.
                viewModel.recordUtilization(simdFrac, gpuFrac, dspFrac)
            },
            computeStageRunner = FramePipeline.ComputeStageRunner { slot ->
                runFilterInPlace(
                    slot = slot,
                    gpuProcessor = gpuProcessor,
                    gpuExecutor = gpuExecutor,
                    viewModel = viewModel
                )
            }
        )
    }

    // Keep pipeline's view of selected mode/filter in sync with the UI.
    LaunchedEffect(metrics.currentMode)   { pipeline.currentMode = metrics.currentMode }
    LaunchedEffect(metrics.currentFilter) { pipeline.currentFilter = metrics.currentFilter }

    DisposableEffect(Unit) {
        pipeline.start()
        onDispose { pipeline.stop() }
    }




    val cameraController = remember {
        // 1. Add a cooldown tracker here!
        var lastSnapshotTime = 0L

        LifecycleCameraController(context).apply {
            bindToLifecycle(lifecycleOwner)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(context)
            ) { imageProxy: ImageProxy ->

                val processStartTime = System.currentTimeMillis()

                if (liveProcessingEnabled) {
                    // Hand the frame to the pipeline. submitFrame() does the
                    // YUV->ARGB copy, closes the proxy, and enqueues the slot.
                    // Returns immediately; all filter work happens off-thread.
                    pipeline.submitFrame(imageProxy)
                    // 2. Check the trigger AND the cooldown timer!
                    // Snapshot handling
                    val currentTime = System.currentTimeMillis()
                    if (liveTakeSnapshot && (currentTime - lastSnapshotTime > 1000)) {
                        currentFrame?.let { bmp ->
                            lastSnapshotTime = currentTime
                            saveSnapshot(context, bmp)
                            liveOnSnapshotTaken()

                        }
                    }
                } else {
                    currentFrame = null
                    imageProxy.close()
                }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(if (liveProcessingEnabled) 0f else 1f),
            factory = { ctx -> PreviewView(ctx).apply { this.controller = cameraController } }
        )

        currentFrame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Processed Camera Feed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private fun finalizeFrame(
    pool: FrameBufferPool,
    pixels: IntArray,
    width: Int,
    height: Int,
    imageProxy: ImageProxy
): Bitmap {
    val processedBitmap = pool.getProcessedBitmap(width, height)
    processedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    val rotation = imageProxy.imageInfo.rotationDegrees
    val rotatedBitmap = pool.getRotatedBitmap(width, height, rotation)
    val canvas = android.graphics.Canvas(rotatedBitmap)
    val matrix = pool.matrix
    matrix.reset()
    matrix.postTranslate(-width / 2f, -height / 2f)
    matrix.postRotate(rotation.toFloat())
    matrix.postTranslate(rotatedBitmap.width / 2f, rotatedBitmap.height / 2f)
    canvas.drawBitmap(processedBitmap, matrix, null)

    imageProxy.close()
    return rotatedBitmap
}

private fun processFrame(imageProxy: ImageProxy, filterType: FilterType, pool: FrameBufferPool): Bitmap {
    val originalBitmap = imageProxy.toBitmap()
    val width = originalBitmap.width
    val height = originalBitmap.height

    val pixels = pool.getPixels(width * height)
    originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    when (filterType) {
        FilterType.SEPIA -> ImageFilters.applySepia(pixels, width, height)
        FilterType.GAUSSIAN_BLUR -> ImageFilters.applyGaussianBlur5x5(pixels, width, height)
        FilterType.SOBEL_EDGE -> ImageFilters.applySobelEdge(pixels, width, height)
        FilterType.EMBOSS -> ImageFilters.applyEmboss(pixels, width, height)
        FilterType.VIGNETTE -> ImageFilters.applyVignette(pixels, width, height)
        FilterType.COMIC -> ImageFilters.applyComicFilter(pixels, width, height)
        FilterType.FULL_CHAIN -> ImageFilters.applyFilterChain(pixels, width, height)
    }

    return finalizeFrame(pool, pixels, width, height, imageProxy)
}




// 1. Create the DataStore instance (this needs to be at the top level of the file)
val Context.dataStore by preferencesDataStore(name = "camera_settings")

interface SettingsRepository {
    val processingModeFlow: Flow<ProcessingMode>
    val filterTypeFlow: Flow<FilterType>
    suspend fun saveProcessingMode(mode: ProcessingMode)
    suspend fun saveFilterType(filter: FilterType)
}

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    // 2. Define the exact key we use to save our string
    private val MODE_KEY = stringPreferencesKey("processing_mode")
    private val FILTER_KEY = stringPreferencesKey("filter_type")

    // 3. Create a Flow that reads the string and converts it back to our Enum
    override val processingModeFlow: Flow<ProcessingMode> = context.dataStore.data.map { preferences ->
        val modeString = preferences[MODE_KEY] ?: ProcessingMode.BASELINE.name
        try {
            ProcessingMode.valueOf(modeString)
        } catch (e: IllegalArgumentException) {
            ProcessingMode.BASELINE // Fallback just in case
        }
    }

    override val filterTypeFlow: Flow<FilterType> = context.dataStore.data.map { preferences ->
        val filterString = preferences[FILTER_KEY] ?: FilterType.SEPIA.name
        try {
            FilterType.valueOf(filterString)
        } catch (e: IllegalArgumentException) {
            FilterType.SEPIA // Fallback
        }
    }

    // 4. Create a function to save the new mode
    override suspend fun saveProcessingMode(mode: ProcessingMode) {
        context.dataStore.edit { preferences ->
            preferences[MODE_KEY] = mode.name
        }
    }

    override suspend fun saveFilterType(filter: FilterType) {
        context.dataStore.edit { preferences ->
            preferences[FILTER_KEY] = filter.name
        }
    }
}

private fun processFrameSIMD(imageProxy: ImageProxy, filterType: FilterType, pool: FrameBufferPool): Bitmap {
    val originalBitmap = imageProxy.toBitmap()
    val width = originalBitmap.width
    val height = originalBitmap.height
    val pixels = pool.getPixels(width * height)
    originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    when (filterType) {
        FilterType.SEPIA -> NativeLib.sepiaSimd(pixels, pixels.size)
        FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(pixels, width, height)
        FilterType.SOBEL_EDGE -> NativeLib.sobelEdgeSimd(pixels, width, height)
        FilterType.EMBOSS -> NativeLib.embossSimd(pixels, width, height)
        FilterType.VIGNETTE -> NativeLib.vignetteSimd(pixels, width, height)
        FilterType.COMIC -> NativeLib.comicSimd(pixels, width, height)
        FilterType.FULL_CHAIN -> {
            NativeLib.sepiaSimd(pixels, pixels.size)
            NativeLib.gaussianBlurSimd(pixels, width, height)
            NativeLib.sobelEdgeSimd(pixels, width, height)
            NativeLib.vignetteSimd(pixels, width, height)
        }
    }

    return finalizeFrame(pool, pixels, width, height, imageProxy)
}

private suspend fun processFrameGPU(
    imageProxy: ImageProxy,
    filterType: FilterType,
    gpuProcessor: GpuProcessor,
    executor: java.util.concurrent.ExecutorService,
    pool: FrameBufferPool
): Bitmap {
    val bmp = imageProxy.toBitmap()
    val width = bmp.width
    val height = bmp.height
    val pixels = pool.getPixels(width * height)
    bmp.getPixels(pixels, 0, width, 0, 0, width, height)

    // All GL calls must run on the executor thread that owns the EGL context
    withContext(executor.asCoroutineDispatcher()) {
        gpuProcessor.processPixels(pixels, filterType, width, height)
    }

    return finalizeFrame(pool, pixels, width, height, imageProxy)
}

private suspend fun processFrameHybrid(
    imageProxy: ImageProxy,
    filterType: FilterType,
    gpuProcessor: GpuProcessor,
    executor: java.util.concurrent.ExecutorService,
    viewModel: MainViewModel,
    pool: FrameBufferPool
): Bitmap = coroutineScope {
    val bmp = imageProxy.toBitmap()
    val width = bmp.width
    val height = bmp.height
    val pixels = pool.getPixels(width * height)
    bmp.getPixels(pixels, 0, width, 0, 0, width, height)

    val mid = pixels.size / 2
    val topHalf    = pixels.copyOfRange(0, mid)
    val bottomHalf = pixels.copyOfRange(mid, pixels.size)

    // Top half → GPU, bottom half → SIMD, run concurrently
    val gpuStart = System.nanoTime()
    val gpuJob = async(executor.asCoroutineDispatcher()) {
        gpuProcessor.processPixels(topHalf, filterType, width, mid / width)
    }

    val simdStart = System.nanoTime()
    val simdJob = async(Dispatchers.Default) {
        when (filterType) {
            FilterType.SEPIA        -> NativeLib.sepiaSimd(bottomHalf, bottomHalf.size)
            FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(bottomHalf, width, bottomHalf.size / width)
            FilterType.SOBEL_EDGE   -> NativeLib.sobelEdgeSimd(bottomHalf, width, bottomHalf.size / width)
            FilterType.EMBOSS       -> NativeLib.embossSimd(bottomHalf, width, bottomHalf.size / width)
            FilterType.VIGNETTE     -> NativeLib.vignetteSimd(bottomHalf, width, bottomHalf.size / width)
            FilterType.COMIC        -> NativeLib.comicSimd(bottomHalf, width, bottomHalf.size / width)
            FilterType.FULL_CHAIN   -> {
                NativeLib.sepiaSimd(bottomHalf, bottomHalf.size)
                NativeLib.gaussianBlurSimd(bottomHalf, width, bottomHalf.size / width)
                NativeLib.sobelEdgeSimd(bottomHalf, width, bottomHalf.size / width)
                NativeLib.vignetteSimd(bottomHalf, width, bottomHalf.size / width)
            }
        }
    }

    gpuJob.await()
    simdJob.await()

    val gpuMs  = (System.nanoTime() - gpuStart)  / 1_000_000L
    val simdMs = (System.nanoTime() - simdStart) / 1_000_000L

    viewModel.recordHybridTiming(gpuMs, simdMs)

    // Stitch halves back together
    System.arraycopy(topHalf,    0, pixels, 0,   mid)
    System.arraycopy(bottomHalf, 0, pixels, mid, pixels.size - mid)

    finalizeFrame(pool, pixels, width, height, imageProxy)
}

/**
 * Runs the selected filter on slot.pixels in place. Called on the pipeline's
 * compute thread.
 *
 * For HYBRID mode: the pipeline itself already has SIMD and GPU active on
 * different frames concurrently (capture/display do SIMD-style CPU work while
 * compute runs on GPU). The compute stage in hybrid mode dispatches to GPU,
 * and the "coordinated SIMD + GPU within the same frame pipeline" behaviour
 * emerges from the pipeline structure.
 */
private fun runFilterInPlace(
    slot: FrameSlot,
    gpuProcessor: GpuProcessor,
    gpuExecutor: java.util.concurrent.ExecutorService,
    viewModel: MainViewModel
) {
    val w = slot.width
    val h = slot.height
    val pixels = slot.pixels
    val filter = slot.filter

    // CRITICAL: pixels.size may be LARGER than w*h because FramePipeline
    // reuses the slot's IntArray and only grows it. Always pass the actual
    // pixel count, never pixels.size, to native code that walks the array.
    val pixelCount = w * h

    when (slot.mode) {
        ProcessingMode.BASELINE -> when (filter) {
            FilterType.SEPIA         -> ImageFilters.applySepia(pixels, w, h)
            FilterType.GAUSSIAN_BLUR -> ImageFilters.applyGaussianBlur5x5(pixels, w, h)
            FilterType.SOBEL_EDGE    -> ImageFilters.applySobelEdge(pixels, w, h)
            FilterType.EMBOSS        -> ImageFilters.applyEmboss(pixels, w, h)
            FilterType.VIGNETTE      -> ImageFilters.applyVignette(pixels, w, h)
            FilterType.COMIC         -> ImageFilters.applyComicFilter(pixels, w, h)
            FilterType.FULL_CHAIN    -> ImageFilters.applyFilterChain(pixels, w, h)
        }

        ProcessingMode.SIMD -> when (filter) {
            FilterType.SEPIA         -> NativeLib.sepiaSimd(pixels, pixelCount)
            FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(pixels, w, h)
            FilterType.SOBEL_EDGE    -> NativeLib.sobelEdgeSimd(pixels, w, h)
            FilterType.EMBOSS        -> NativeLib.embossSimd(pixels, w, h)
            FilterType.VIGNETTE      -> NativeLib.vignetteSimd(pixels, w, h)
            FilterType.COMIC         -> NativeLib.comicSimd(pixels, w, h)
            FilterType.FULL_CHAIN    -> {
                NativeLib.sepiaSimd(pixels, pixelCount)
                NativeLib.gaussianBlurSimd(pixels, w, h)
                NativeLib.sobelEdgeSimd(pixels, w, h)
                NativeLib.vignetteSimd(pixels, w, h)
            }
        }

        ProcessingMode.GPU -> {
            // Pure GPU: marshal onto the EGL thread and wait.
            val latch = java.util.concurrent.CountDownLatch(1)
            gpuExecutor.execute {
                try {
                    gpuProcessor.processPixels(pixels, filter, w, h)
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
        }

        ProcessingMode.HYBRID -> {
            // 1. Read real-time system metrics gathered by your ViewModel
            val metrics = viewModel.metricsState.value

            // 2. POWER-AWARE ADAPTIVE SCHEDULING STRATEGY
            // Android Thermal API: 2 represents THERMAL_STATUS_MODERATE.
            // If the device crosses this thermal threshold or battery drops to critical,
            // we offload to the SIMD (CPU) pipeline to allow the GPU to cool down.
            val isDeviceHot = metrics.batteryTempC > 37.0f || metrics.thermalState >= 2
            val isBatteryLow = metrics.batteryPercent in 0..15

            // 3. Decide the optimal hardware target for THIS specific frame
            val useSimd = isDeviceHot || isBatteryLow

            val tStart = System.nanoTime()

            if (useSimd) {
                // ROUTE TO SIMD PIPELINE
                when (filter) {
                    FilterType.SEPIA         -> NativeLib.sepiaSimd(pixels, pixelCount)
                    FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(pixels, w, h)
                    FilterType.SOBEL_EDGE    -> NativeLib.sobelEdgeSimd(pixels, w, h)
                    FilterType.EMBOSS        -> NativeLib.embossSimd(pixels, w, h)
                    FilterType.VIGNETTE      -> NativeLib.vignetteSimd(pixels, w, h)
                    FilterType.COMIC         -> NativeLib.comicSimd(pixels, w, h)
                    FilterType.FULL_CHAIN    -> {
                        NativeLib.sepiaSimd(pixels, pixelCount)
                        NativeLib.gaussianBlurSimd(pixels, w, h)
                        NativeLib.sobelEdgeSimd(pixels, w, h)
                        NativeLib.vignetteSimd(pixels, w, h)
                    }
                }
                val simdMs = (System.nanoTime() - tStart) / 1_000_000L
                // Record 0ms for GPU so the dashboard gauge swings 100% to SIMD
                viewModel.recordHybridTiming(gpuMs = 0L, simdMs = simdMs)

            } else {
                // ROUTE TO GPU PIPELINE
                val latch = java.util.concurrent.CountDownLatch(1)
                gpuExecutor.execute {
                    try {
                        gpuProcessor.processPixels(pixels, filter, w, h)
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await()

                val gpuMs = (System.nanoTime() - tStart) / 1_000_000L
                // Record 0ms for SIMD so the dashboard gauge swings 100% to GPU
                viewModel.recordHybridTiming(gpuMs = gpuMs, simdMs = 0L)
            }
        }
    }
}




@Composable
fun AppScreenSkeleton(
    repository: SettingsRepository,
    modifier: Modifier = Modifier
){

    // Create a factory that knows how to build MainViewModel with the repository
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository) as T
            }
        }
    }
    // Inject the ViewModel using our factory
    val viewModel: MainViewModel = viewModel(factory = factory)

    val context = LocalContext.current

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // 1. Calculate battery percentage
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (scale > 0) (level * 100) / scale else -1

                // 2. Battery temp is returned in tenths of a degree Celsius
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val tempC = temp / 10.0f

                // 3. Get Thermal Status (Requires Android Q / API 29+)
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val thermalState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    powerManager.currentThermalStatus
                } else {
                    -1
                }

                viewModel.updateSystemMetrics(percent, tempC, thermalState)
            }
        }

        // Start listening
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Stop listening when the UI is disposed
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    // 2. Listen to the live data stream
    val metrics by viewModel.metricsState.collectAsState()
    var isProcessingEnabled by remember { mutableStateOf(false) }
    var takeSnapshot by remember { mutableStateOf(false) }
    val isBenchmarking by viewModel.isBenchmarkingState.collectAsState()
    val benchmarkProcessing by viewModel.benchmarkProcessingEnabled.collectAsState()

    // When benchmark is running, it controls processing. Otherwise the user does.
    val effectiveProcessingEnabled = if (isBenchmarking) benchmarkProcessing else isProcessingEnabled

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(0.55f).fillMaxWidth()) {
            CameraPreview(
                isProcessingEnabled = effectiveProcessingEnabled,
                viewModel = viewModel,
                takeSnapshot = takeSnapshot,
                onSnapshotTaken = { takeSnapshot = false },
                modifier = Modifier.fillMaxSize()
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Snapshot Button
                FloatingActionButton(
                    onClick = { if (!isBenchmarking) takeSnapshot = true },
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = Color(0xFF00B0FF)
                ) {
                    Icon(imageVector = Icons.Filled.CameraAlt, contentDescription = "Capture")
                }
                // Play/Stop Button
                FloatingActionButton(
                    onClick = { if (!isBenchmarking) isProcessingEnabled = !isProcessingEnabled },
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = if (isProcessingEnabled) Color(0xFFFF5252) else Color(0xFF00B0FF)
                ) {
                    Icon(
                        imageVector = if (isProcessingEnabled) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Stop"
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.weight(0.45f).fillMaxWidth(),
            color = Color(0xFF121212), // Deep, dark background to pop the #1E1E1E cards
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SegmentedControl(
                    items = ProcessingMode.values().map { it.name },
                    selectedIndex = ProcessingMode.values().indexOf(metrics.currentMode),
                    onItemSelected = { index -> viewModel.setProcessingMode(ProcessingMode.values()[index]) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SegmentedControl(
                    items = FilterType.values().map { it.name },
                    selectedIndex = FilterType.values().indexOf(metrics.currentFilter),
                    onItemSelected = { index -> viewModel.setFilterType(FilterType.values()[index]) },
                    useWeight = false
                )

                Spacer(modifier = Modifier.height(12.dp))
                MetricsGrid(metrics = metrics)

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Sparkline(
                        data = metrics.latencyHistory,
                        modifier = Modifier.weight(1f).height(80.dp)
                    )

                    if (metrics.currentMode == ProcessingMode.HYBRID) {
                        HybridGauge(
                            gpuMs = metrics.gpuPortionMs,
                            simdMs = metrics.simdPortionMs,
                            modifier = Modifier.size(80.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LogView(logs = metrics.logs)

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    if (isBenchmarking) {
                        // Single STOP button when any benchmark is running
                        Button(
                            onClick = { viewModel.stopBenchmark() },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5C2A2A),
                                contentColor = Color(0xFFFF5252)
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "STOP BENCH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                isProcessingEnabled = true
                                viewModel.startBenchmark(3000)
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A2A2A),
                                contentColor = Color(0xFF00B0FF)
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "FAST BENCH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Button(
                            onClick = {
                                isProcessingEnabled = true
                                viewModel.startBenchmark(10000)
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A2A2A),
                                contentColor = Color(0xFF00B0FF)
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "FULL BENCH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String = "",
    valueColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = Color.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
fun MetricsGrid(metrics: CameraMetrics, modifier: Modifier = Modifier) {
    // Mature, softened color palette
    val cyanGpu = Color(0xFF00B0FF)
    val warningRed = Color(0xFFFF5252)
    val cautionYellow = Color(0xFFFFEA00)

    val tempColor = when {
        metrics.batteryTempC > 40.0f -> warningRed
        metrics.batteryTempC > 35.0f -> cautionYellow
        else -> Color.White
    }

    val batteryColor = if (metrics.batteryPercent in 0..15) warningRed else Color.White

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Row 1: Pipeline Performance
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                label = "FPS",
                value = metrics.fps.toString(),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "Latency",
                value = metrics.frameLatencyMs.toString(),
                unit = "ms",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "E2E",
                value = metrics.e2eLatencyMs.toString(),
                unit = "ms",
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: System Health
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                label = "Battery",
                value = if (metrics.batteryPercent >= 0) metrics.batteryPercent.toString() else "--",
                unit = "%",
                valueColor = batteryColor,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "Temp",
                value = if (metrics.batteryTempC >= 0f) metrics.batteryTempC.toString() else "--",
                unit = "°C",
                valueColor = tempColor,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "Thermal",
                value = if (metrics.thermalState >= 0) metrics.thermalState.toString() else "--",
                modifier = Modifier.weight(1f)
            )
        }

        // Row 3: Contextual Info (Speedup & Hybrid Split)
        val showSpeedup = metrics.baselineLatencyMs > 0 && metrics.frameLatencyMs > 0
        val showHybrid = metrics.currentMode == ProcessingMode.HYBRID

        if (showSpeedup || showHybrid) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showSpeedup) {
                    val speedup = metrics.baselineLatencyMs.toFloat() / metrics.frameLatencyMs
                    MetricCard(
                        label = "Speedup",
                        value = "%.1f".format(speedup),
                        unit = "x",
                        valueColor = cyanGpu,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (showHybrid) {
                    MetricCard(
                        label = "GPU / SIMD",
                        value = "${metrics.gpuPortionMs}/${metrics.simdPortionMs}",
                        unit = "ms",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}


@Composable
fun Sparkline(data: List<Long>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas

        val maxData = data.maxOrNull() ?: 1L
        val minData = data.minOrNull() ?: 0L
        val range = (maxData - minData).coerceAtLeast(1L)
        val stepX = size.width / (data.size - 1)

        val strokePath = Path().apply {
            data.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - ((value - minData).toFloat() / range * size.height)

                if (index == 0) {
                    moveTo(x, y)
                } else {
                    lineTo(x, y)
                }
            }
        }

        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color.Cyan.copy(alpha = 0.5f), Color.Transparent),
                startY = 0f,
                endY = size.height
            )
        )

        drawPath(
            path = strokePath,
            color = Color.Cyan,
            style = Stroke(width = 3f)
        )
    }
}

fun calculateSweepAngles(gpuMs: Long, simdMs: Long): Pair<Float, Float> {
    val total = kotlin.math.max(gpuMs + simdMs, 1L).toFloat()
    val gpuSweep = (gpuMs.toFloat() / total) * 360f
    val simdSweep = (simdMs.toFloat() / total) * 360f
    return Pair(gpuSweep, simdSweep)
}

@Composable
fun HybridGauge(gpuMs: Long, simdMs: Long, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val (gpuSweep, simdSweep) = calculateSweepAngles(gpuMs, simdMs)

            drawArc(
                color = Color(0xFF00B0FF),
                startAngle = -90f,
                sweepAngle = gpuSweep,
                useCenter = false,
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )

            drawArc(
                color = Color(0xFF00E676),
                startAngle = -90f + gpuSweep,
                sweepAngle = simdSweep,
                useCenter = false,
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )
        }
        Text("GPU/SIMD", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
    }
}

@Composable
fun SegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    useWeight: Boolean = true
) {
    val rowModifier = if (useWeight) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    }

    Row(
        modifier = rowModifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selectedIndex
            val itemModifier = if (useWeight) Modifier.weight(1f) else Modifier

            Box(
                modifier = itemModifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color(0xFF00B0FF).copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onItemSelected(index) }
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item,
                    color = if (isSelected) Color(0xFF00B0FF) else Color.Gray,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun LogView(logs: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "SYSTEM LOGS",
            color = Color.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "Awaiting events...",
                    color = Color.DarkGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                logs.forEach { log ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = "> ",
                            color = Color(0xFF00B0FF), // Matches the GPU Cyan
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = log,
                            color = Color(0xFFE0E0E0), // Soft off-white
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}



fun saveSnapshot(context: android.content.Context, bitmap: Bitmap) {
    val filename = "snapshot_${System.currentTimeMillis()}.jpg"

    // 1. Tell Android what kind of file we are making and where we want it
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        // Put it in a specific sub-folder in the public Pictures directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AcceleratedCamera")
        }
    }

    // 2. Ask the MediaStore to create a blank file for us
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    // 3. Open that blank file and compress our bitmap into it!
    if (uri != null) {
        resolver.openOutputStream(uri)?.use { outStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
        }
    }
}

class FrameBufferPool {
    var pixels: IntArray? = null
    var processedBitmap: Bitmap? = null
    var rotatedBitmap: Bitmap? = null
    val matrix = android.graphics.Matrix()

    fun getPixels(size: Int): IntArray {
        if (pixels == null || pixels!!.size != size) {
            pixels = IntArray(size)
        }
        return pixels!!
    }

    fun getProcessedBitmap(width: Int, height: Int): Bitmap {
        if (processedBitmap == null || processedBitmap!!.width != width || processedBitmap!!.height != height) {
            processedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return processedBitmap!!
    }

    fun getRotatedBitmap(width: Int, height: Int, rotationDegrees: Int): Bitmap {
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val tw = if (swap) height else width
        val th = if (swap) width else height
        if (rotatedBitmap == null || rotatedBitmap!!.width != tw || rotatedBitmap!!.height != th) {
            rotatedBitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        }
        return rotatedBitmap!!
    }
}

//@Preview(showBackground = true)
//@Composable
//fun AppScreenPreview() {
//    // Make sure this theme name matches the one in your setContent block!
//    AcceleratedCameraTheme {
//        AppScreenSkeleton()
//    }
//}