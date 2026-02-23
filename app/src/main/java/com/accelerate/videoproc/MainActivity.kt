package com.accelerate.videoproc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main activity implementing the real-time camera video processing pipeline.
 *
 * Pipeline (3 measured stages):
 *   Stage 1: Camera YUV_420_888 → ARGB conversion     [CPU Scalar / NEON]
 *   Stage 2: ARGB → Filter Processing                  [CPU Scalar / NEON / GPU]
 *   Stage 3: Bitmap creation + rotation + display       [CPU + Android GPU compositor]
 *
 * The dashboard shows per-stage latency and which hardware resource executes each stage,
 * allowing an observer to reason about how computation is mapped onto hardware in real time.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AccelerateVP"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val SCALE_FACTOR = 2
        private const val THERMAL_UPDATE_INTERVAL_MS = 2000L
    }

    // --- UI: Core metrics ---
    private lateinit var processedFrameView: ImageView
    private lateinit var cameraPreview: PreviewView
    private lateinit var tvFps: TextView
    private lateinit var tvProcessLatency: TextView
    private lateinit var tvEndToEndLatency: TextView
    private lateinit var tvResolution: TextView
    private lateinit var tvSimdStatus: TextView
    private lateinit var tvGpuStatus: TextView
    private lateinit var tvFrameCount: TextView
    private lateinit var tvArchInfo: TextView
    private lateinit var modeLabel: TextView
    private lateinit var filterLabel: TextView
    private lateinit var btnSwitchMode: Button
    private lateinit var btnSwitchFilter: Button

    // --- UI: Pipeline stage breakdown (hardware mapping) ---
    private lateinit var tvYuvLatency: TextView
    private lateinit var tvYuvHardware: TextView
    private lateinit var tvFilterLatency: TextView
    private lateinit var tvFilterHardware: TextView
    private lateinit var tvDisplayLatency: TextView
    private lateinit var tvDisplayHardware: TextView
    private lateinit var tvThroughput: TextView
    private lateinit var tvCpuLoad: TextView

    // --- UI: Thermal/Power ---
    private lateinit var tvThermal: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvThermalStatus: TextView

    // --- Processing State ---
    private var currentMode = ExecutionMode.BASELINE
    private var currentFilter = FilterType.NONE
    private val performanceTracker = PerformanceTracker(windowSize = 30)
    private lateinit var processingExecutor: ExecutorService
    private val isProcessing = AtomicBoolean(false)

    // --- Thermal + CPU Monitoring ---
    private lateinit var thermalMonitor: ThermalMonitor
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCpuIdle = 0L
    private var lastCpuTotal = 0L
    private val thermalUpdateRunnable = object : Runnable {
        override fun run() {
            updateThermalDisplay()
            updateCpuLoad()
            mainHandler.postDelayed(this, THERMAL_UPDATE_INTERVAL_MS)
        }
    }

    // --- Frame dimensions for throughput calc ---
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupButtons()

        processingExecutor = Executors.newSingleThreadExecutor()
        thermalMonitor = ThermalMonitor(this)

        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(", ")
        val cores = Runtime.getRuntime().availableProcessors()
        tvArchInfo.text = "Arch: $abis | Cores: $cores | Day 20 Baseline"

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        updateModeDisplay()
        updateFilterDisplay()
        mainHandler.post(thermalUpdateRunnable)
    }

    private fun bindViews() {
        processedFrameView = findViewById(R.id.processedFrameView)
        cameraPreview = findViewById(R.id.cameraPreview)
        tvFps = findViewById(R.id.tvFps)
        tvProcessLatency = findViewById(R.id.tvProcessLatency)
        tvEndToEndLatency = findViewById(R.id.tvEndToEndLatency)
        tvResolution = findViewById(R.id.tvResolution)
        tvSimdStatus = findViewById(R.id.tvSimdStatus)
        tvGpuStatus = findViewById(R.id.tvGpuStatus)
        tvFrameCount = findViewById(R.id.tvFrameCount)
        tvArchInfo = findViewById(R.id.tvArchInfo)
        modeLabel = findViewById(R.id.modeLabel)
        filterLabel = findViewById(R.id.filterLabel)
        btnSwitchMode = findViewById(R.id.btnSwitchMode)
        btnSwitchFilter = findViewById(R.id.btnSwitchFilter)

        // Pipeline breakdown
        tvYuvLatency = findViewById(R.id.tvYuvLatency)
        tvYuvHardware = findViewById(R.id.tvYuvHardware)
        tvFilterLatency = findViewById(R.id.tvFilterLatency)
        tvFilterHardware = findViewById(R.id.tvFilterHardware)
        tvDisplayLatency = findViewById(R.id.tvDisplayLatency)
        tvDisplayHardware = findViewById(R.id.tvDisplayHardware)
        tvThroughput = findViewById(R.id.tvThroughput)
        tvCpuLoad = findViewById(R.id.tvCpuLoad)

        // Thermal/Power
        tvThermal = findViewById(R.id.tvThermal)
        tvBattery = findViewById(R.id.tvBattery)
        tvThermalStatus = findViewById(R.id.tvThermalStatus)
    }

    private fun setupButtons() {
        btnSwitchMode.setOnClickListener {
            currentMode = currentMode.next()
            performanceTracker.reset()
            updateModeDisplay()
        }
        btnSwitchFilter.setOnClickListener {
            currentFilter = currentFilter.next()
            performanceTracker.reset()
            updateFilterDisplay()
        }
    }

    private fun updateModeDisplay() {
        modeLabel.text = "MODE: ${currentMode.displayName}"
        when (currentMode) {
            ExecutionMode.BASELINE -> {
                tvSimdStatus.text = "⬤ INACTIVE"; tvSimdStatus.setTextColor(0xFF616161.toInt())
                tvGpuStatus.text = "⬤ INACTIVE"; tvGpuStatus.setTextColor(0xFF616161.toInt())
                tvYuvHardware.text = "[CPU Scalar]"
                tvFilterHardware.text = "[CPU Scalar]"
                tvDisplayHardware.text = "[CPU+GPU]"
            }
            ExecutionMode.SIMD -> {
                tvSimdStatus.text = "⬤ ACTIVE"; tvSimdStatus.setTextColor(0xFF76FF03.toInt())
                tvGpuStatus.text = "⬤ INACTIVE"; tvGpuStatus.setTextColor(0xFF616161.toInt())
                tvYuvHardware.text = "[CPU NEON]"
                tvFilterHardware.text = "[CPU NEON]"
                tvDisplayHardware.text = "[CPU+GPU]"
            }
            ExecutionMode.GPU -> {
                tvSimdStatus.text = "⬤ INACTIVE"; tvSimdStatus.setTextColor(0xFF616161.toInt())
                tvGpuStatus.text = "⬤ ACTIVE"; tvGpuStatus.setTextColor(0xFF76FF03.toInt())
                tvYuvHardware.text = "[CPU Scalar]"
                tvFilterHardware.text = "[GPU Shader]"
                tvDisplayHardware.text = "[GPU]"
            }
            ExecutionMode.HYBRID -> {
                tvSimdStatus.text = "⬤ ACTIVE"; tvSimdStatus.setTextColor(0xFF76FF03.toInt())
                tvGpuStatus.text = "⬤ ACTIVE"; tvGpuStatus.setTextColor(0xFF76FF03.toInt())
                tvYuvHardware.text = "[CPU NEON]"
                tvFilterHardware.text = "[NEON+GPU]"
                tvDisplayHardware.text = "[GPU]"
            }
        }
    }

    private fun updateFilterDisplay() {
        filterLabel.text = currentFilter.displayName
    }

    // ==================== CAMERA SETUP ====================

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            imageAnalysis.setAnalyzer(processingExecutor) { processFrame(it) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                Log.i(TAG, "Camera pipeline started")
            } catch (e: Exception) { Log.e(TAG, "Camera binding failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    // ==================== FRAME PROCESSING PIPELINE ====================

    private fun processFrame(imageProxy: ImageProxy) {
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return
        }
        val captureTimestampMs = System.currentTimeMillis()

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // ── STAGE 1: YUV → ARGB ──
            val yuvStartNs = System.nanoTime()
            val (pixels, width, height) = YuvConverter.yuvToArgbScaled(imageProxy, SCALE_FACTOR)
            val yuvEndNs = System.nanoTime()
            val yuvLatencyMs = (yuvEndNs - yuvStartNs) / 1_000_000.0

            // ── STAGE 2: Filter Processing ──
            val filterStartNs = System.nanoTime()
            val processedPixels = when (currentMode) {
                ExecutionMode.BASELINE -> BaselineProcessor.processFrame(pixels, width, height, currentFilter)
                ExecutionMode.SIMD -> BaselineProcessor.processFrame(pixels, width, height, currentFilter)  // Day 40
                ExecutionMode.GPU -> BaselineProcessor.processFrame(pixels, width, height, currentFilter)    // Day 70
                ExecutionMode.HYBRID -> BaselineProcessor.processFrame(pixels, width, height, currentFilter) // Day 70
            }
            val filterEndNs = System.nanoTime()
            val filterLatencyMs = (filterEndNs - filterStartNs) / 1_000_000.0

            // ── STAGE 3: Bitmap + Rotation + Display ──
            val displayStartNs = System.nanoTime()
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(processedPixels, 0, width, 0, 0, width, height)
            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                if (rotated != bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val displayEndNs = System.nanoTime()
            val displayLatencyMs = (displayEndNs - displayStartNs) / 1_000_000.0

            // ── Total processing time ──
            val totalProcessMs = yuvLatencyMs + filterLatencyMs + displayLatencyMs

            // ── End-to-end latency ──
            val endToEndLatencyMs = (System.currentTimeMillis() - captureTimestampMs).toDouble()

            // ── Record all metrics ──
            performanceTracker.recordFrame(totalProcessMs, endToEndLatencyMs, yuvLatencyMs, filterLatencyMs, displayLatencyMs)
            lastFrameWidth = width
            lastFrameHeight = height

            // ── Update UI ──
            val finalBitmap = bitmap
            val displayW = finalBitmap.width
            val displayH = finalBitmap.height
            runOnUiThread {
                processedFrameView.setImageBitmap(finalBitmap)
                updateDashboard(displayW, displayH)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
            isProcessing.set(false)
        }
    }

    // ==================== DASHBOARD UPDATE ====================

    private fun updateDashboard(frameWidth: Int, frameHeight: Int) {
        val fps = performanceTracker.getFps()
        val processLatency = performanceTracker.getAvgProcessLatency()
        val endToEndLatency = performanceTracker.getAvgEndToEndLatency()
        val frameCount = performanceTracker.frameCount

        // Core metrics
        tvFps.text = String.format("%.1f FPS", fps)
        tvFps.setTextColor(when {
            fps >= 20 -> 0xFF76FF03.toInt()
            fps >= 10 -> 0xFFFFD740.toInt()
            else -> 0xFFFF5252.toInt()
        })
        tvProcessLatency.text = String.format("%.1f ms", processLatency)
        tvEndToEndLatency.text = String.format("%.0f ms", endToEndLatency)
        tvResolution.text = "${frameWidth}×${frameHeight}"
        tvFrameCount.text = frameCount.toString()

        // Pipeline stage breakdown — this is the key hardware mapping info
        tvYuvLatency.text = String.format("%.1f ms", performanceTracker.getAvgYuvLatency())
        tvFilterLatency.text = String.format("%.1f ms", performanceTracker.getAvgFilterLatency())
        tvDisplayLatency.text = String.format("%.1f ms", performanceTracker.getAvgDisplayLatency())

        // Throughput: pixels processed per second → MB/s (4 bytes per ARGB pixel)
        val pixelsPerFrame = lastFrameWidth * lastFrameHeight
        val bytesPerSec = pixelsPerFrame * 4.0 * fps
        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
        tvThroughput.text = String.format("%.1f MB/s", mbPerSec)
    }

    // ==================== CPU LOAD READING ====================

    private fun updateCpuLoad() {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()

            val parts = line.split("\\s+".toRegex())
            // user + nice + system + idle + iowait + irq + softirq
            val idle = parts[4].toLong()
            val total = parts.drop(1).take(7).sumOf { it.toLong() }

            if (lastCpuTotal > 0) {
                val diffIdle = idle - lastCpuIdle
                val diffTotal = total - lastCpuTotal
                val cpuPercent = if (diffTotal > 0) 100.0 * (1.0 - diffIdle.toDouble() / diffTotal) else 0.0
                tvCpuLoad.text = String.format("%.0f%%", cpuPercent)
                tvCpuLoad.setTextColor(when {
                    cpuPercent < 50 -> 0xFF69F0AE.toInt()
                    cpuPercent < 80 -> 0xFFFFD740.toInt()
                    else -> 0xFFFF5252.toInt()
                })
            }
            lastCpuIdle = idle
            lastCpuTotal = total
        } catch (e: Exception) {
            tvCpuLoad.text = "N/A"
        }
    }

    // ==================== THERMAL / POWER ====================

    private fun updateThermalDisplay() {
        try {
            val state = thermalMonitor.readState()
            tvThermal.text = String.format("%.1f°C", state.batteryTempCelsius)
            tvThermal.setTextColor(when {
                state.batteryTempCelsius < 35 -> 0xFF69F0AE.toInt()
                state.batteryTempCelsius < 40 -> 0xFFFFD740.toInt()
                state.batteryTempCelsius < 45 -> 0xFFFF9100.toInt()
                else -> 0xFFFF5252.toInt()
            })
            val chargingSymbol = if (state.isCharging) "⚡" else ""
            tvBattery.text = "${state.batteryPercent}%$chargingSymbol"
            tvBattery.setTextColor(when {
                state.batteryPercent > 50 -> 0xFF69F0AE.toInt()
                state.batteryPercent > 20 -> 0xFFFFD740.toInt()
                else -> 0xFFFF5252.toInt()
            })
            tvThermalStatus.text = state.thermalStatus
            tvThermalStatus.setTextColor(when (state.thermalStatusLevel) {
                0 -> 0xFF69F0AE.toInt(); 1 -> 0xFFFFD740.toInt()
                2 -> 0xFFFF9100.toInt(); else -> 0xFFFF5252.toInt()
            })
        } catch (e: Exception) { Log.w(TAG, "Thermal read error: ${e.message}") }
    }

    // ==================== PERMISSIONS ====================

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show(); finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(thermalUpdateRunnable)
        processingExecutor.shutdown()
    }
}
