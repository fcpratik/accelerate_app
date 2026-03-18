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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Create the repository
        val settingsRepository = SettingsRepository(applicationContext)

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
    val liveTakeSnapshot by rememberUpdatedState(takeSnapshot) // <-- Live pointer
    val liveOnSnapshotTaken by rememberUpdatedState(onSnapshotTaken) // <-- Live pointer

    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    val processingScope = remember { CoroutineScope(Dispatchers.Default) }

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
                    processingScope.launch {
                        val newBitmap = when (liveMode) {
                            ProcessingMode.BASELINE -> processFrame(imageProxy)
                            ProcessingMode.SIMD -> processFrameSIMDPlaceholder(imageProxy)
                            ProcessingMode.GPU -> processFrameGPUPlaceholder(imageProxy)
                        }

                        val processEndTime = System.currentTimeMillis()
                        currentFrame = newBitmap
                        val frameRenderedTime = System.currentTimeMillis()

                        viewModel.recordFrame(processStartTime, processEndTime, frameRenderedTime)

                        // 2. Check the trigger AND the cooldown timer!
                        val currentTime = System.currentTimeMillis()
                        if (liveTakeSnapshot && (currentTime - lastSnapshotTime > 1000)) {
                            lastSnapshotTime = currentTime // Reset the cooldown
                            saveSnapshot(context, newBitmap)
                            liveOnSnapshotTaken() // Tell UI to reset
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
            modifier = Modifier.fillMaxSize(),
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
private fun processFrame(imageProxy: ImageProxy): Bitmap {
    val originalBitmap = imageProxy.toBitmap()
    val width = originalBitmap.width
    val height = originalBitmap.height

    val pixels = IntArray(width * height)
    originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    for (i in pixels.indices) {
        val pixel = pixels[i]

        val a = android.graphics.Color.alpha(pixel)
        val r = 255 - android.graphics.Color.red(pixel)
        val g = 255 - android.graphics.Color.green(pixel)
        val b = 255 - android.graphics.Color.blue(pixel)

        pixels[i] = android.graphics.Color.argb(a, r, g, b)
    }

    val processedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    processedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    // --- NEW: Fix the 90-degree rotation ---
    // 1. Create a Matrix
    val matrix = Matrix()

    // 2. Ask the imageProxy how much the sensor is rotated, and apply that to the Matrix
    matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

    // 3. Create a final, rotated copy of our processed bitmap
    val rotatedBitmap = Bitmap.createBitmap(
        processedBitmap, 0, 0, width, height, matrix, true
    )

    imageProxy.close()

    // Return the rotated frame!
    return rotatedBitmap
}




// 1. Create the DataStore instance (this needs to be at the top level of the file)
val Context.dataStore by preferencesDataStore(name = "camera_settings")

class SettingsRepository(private val context: Context) {

    // 2. Define the exact key we use to save our string
    private val MODE_KEY = stringPreferencesKey("processing_mode")

    // 3. Create a Flow that reads the string and converts it back to our Enum
    val processingModeFlow: Flow<ProcessingMode> = context.dataStore.data.map { preferences ->
        val modeString = preferences[MODE_KEY] ?: ProcessingMode.BASELINE.name
        try {
            ProcessingMode.valueOf(modeString)
        } catch (e: IllegalArgumentException) {
            ProcessingMode.BASELINE // Fallback just in case
        }
    }

    // 4. Create a function to save the new mode
    suspend fun saveProcessingMode(mode: ProcessingMode) {
        context.dataStore.edit { preferences ->
            preferences[MODE_KEY] = mode.name
        }
    }
}

private fun processFrameSIMDPlaceholder(imageProxy: ImageProxy): Bitmap {
    // TODO: Implement actual SIMD C++ processing later
    return processFrame(imageProxy)
}

private fun processFrameGPUPlaceholder(imageProxy: ImageProxy): Bitmap {
    // TODO: Implement actual GPU processing later
    return processFrame(imageProxy)
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
    // 2. Listen to the live data stream
    val metrics by viewModel.metricsState.collectAsState()
    var isProcessingEnabled by remember { mutableStateOf(false) }
    var takeSnapshot by remember { mutableStateOf(false) } // <-- New trigger state

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            isProcessingEnabled = isProcessingEnabled,
            viewModel = viewModel,
            takeSnapshot = takeSnapshot, // <-- Pass the trigger down
            onSnapshotTaken = { takeSnapshot = false }, // <-- Reset the trigger when done
            modifier = Modifier.fillMaxSize()
        )

        // Dashboard Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(16.dp)
        ) {
            ModeSelector(
                currentMode = metrics.currentMode,
                onModeSelected = { viewModel.setProcessingMode(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            MetricsTiles(metrics = metrics)

            Spacer(modifier = Modifier.height(12.dp))
            Sparkline(
                data = metrics.latencyHistory,
                modifier = Modifier.fillMaxWidth().height(40.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            LogView(logs = metrics.logs)

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = { isProcessingEnabled = !isProcessingEnabled }) {
                    Text(if (isProcessingEnabled) "Stop Processing" else "Start Processing")
                }
                Button(onClick = { takeSnapshot = true }) {
                    Text("Snapshot")
                }
            }
        }
    }
}

@Composable
fun MetricsTiles(metrics: CameraMetrics, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("FPS: ${metrics.fps}", color = Color.White)
        Text("Frame: ${metrics.frameLatencyMs}ms", color = Color.White)
        Text("E2E: ${metrics.e2eLatencyMs}ms", color = Color.White)
    }
}


@Composable
fun Sparkline(data: List<Long>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas

        // Find the highest and lowest values to scale our graph
        val maxData = data.maxOrNull() ?: 1L
        val minData = data.minOrNull() ?: 0L
        val range = (maxData - minData).coerceAtLeast(1L)

        // Calculate the horizontal spacing between each point
        val stepX = size.width / (data.size - 1)

        val path = Path().apply {
            data.forEachIndexed { index, value ->
                val x = index * stepX
                // Calculate Y, flipping it so higher numbers go up instead of down
                val y = size.height - ((value - minData).toFloat() / range * size.height)

                if (index == 0) {
                    moveTo(x, y)
                } else {
                    lineTo(x, y)
                }
            }
        }

        drawPath(
            path = path,
            color = Color.Cyan,
            style = Stroke(width = 3f)
        )
    }
}

@Composable
fun ModeSelector(
    currentMode: ProcessingMode,
    onModeSelected: (ProcessingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ProcessingMode.values().forEach { mode ->
            Button(
                onClick = { onModeSelected(mode) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == currentMode) Color.Cyan else Color.DarkGray
                )
            ) {
                Text(
                    text = mode.name,
                    color = if (mode == currentMode) Color.Black else Color.White
                )
            }
        }
    }
}

@Composable
fun LogView(logs: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text("Recent Events:", color = Color.Gray, fontSize = 12.sp)
        logs.forEach { log ->
            Text("> $log", color = Color.Green, fontSize = 10.sp)
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

//@Preview(showBackground = true)
//@Composable
//fun AppScreenPreview() {
//    // Make sure this theme name matches the one in your setContent block!
//    AcceleratedCameraTheme {
//        AppScreenSkeleton()
//    }
//}