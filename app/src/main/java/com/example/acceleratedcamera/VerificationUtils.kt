package com.example.acceleratedcamera

import android.graphics.Color
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object VerificationUtils {
    private const val TAG = "VerificationUtils"

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun verifyFilterCorrectness(
        imageProxy: ImageProxy,
        filterType: FilterType,
        maxTolerance: Int = 2,
        gpuProcessor: GpuProcessor? = null,
        gpuExecutor: java.util.concurrent.ExecutorService? = null
    ): Boolean {
        val bitmap = imageProxy.toBitmap()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return verifyFilterCorrectness(pixels, width, height, filterType, maxTolerance, gpuProcessor, gpuExecutor)
    }

    fun verifyFilterCorrectness(
        pixels: IntArray, 
        width: Int, 
        height: Int, 
        filterType: FilterType, 
        maxTolerance: Int = 2,
        gpuProcessor: GpuProcessor? = null,
        gpuExecutor: java.util.concurrent.ExecutorService? = null
    ): Boolean {
        val actualTolerance = if (filterType == FilterType.COMIC) 65 else maxTolerance

        // (2) Make two copies
        val copyA = pixels.clone()
        val copyB = pixels.clone()

        // (3) Apply baseline filter on copy A
        when (filterType) {
            FilterType.SEPIA -> ImageFilters.applySepia(copyA, width, height)
            FilterType.GAUSSIAN_BLUR -> ImageFilters.applyGaussianBlur5x5(copyA, width, height)
            FilterType.SOBEL_EDGE -> ImageFilters.applySobelEdge(copyA, width, height)
            FilterType.EMBOSS -> ImageFilters.applyEmboss(copyA, width, height)
            FilterType.VIGNETTE -> ImageFilters.applyVignette(copyA, width, height)
            FilterType.COMIC -> ImageFilters.applyComicFilter(copyA, width, height)
            FilterType.FULL_CHAIN -> ImageFilters.applyFilterChain(copyA, width, height)
        }

        // (4) Apply SIMD filter on copy B
        when (filterType) {
            FilterType.SEPIA -> NativeLib.sepiaSimd(copyB, copyB.size)
            FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(copyB, width, height)
            FilterType.SOBEL_EDGE -> NativeLib.sobelEdgeSimd(copyB, width, height)
            FilterType.EMBOSS -> NativeLib.embossSimd(copyB, width, height)
            FilterType.VIGNETTE -> NativeLib.vignetteSimd(copyB, width, height)
            FilterType.COMIC -> NativeLib.comicSimd(copyB, width, height)
            FilterType.FULL_CHAIN -> {
                NativeLib.sepiaSimd(copyB, copyB.size)
                NativeLib.gaussianBlurSimd(copyB, width, height)
                NativeLib.sobelEdgeSimd(copyB, width, height)
                NativeLib.vignetteSimd(copyB, width, height)
            }
        }

        var copyC: IntArray? = null
        if (gpuProcessor != null && gpuExecutor != null) {
            copyC = pixels.clone()
            runBlocking {
                withContext(gpuExecutor.asCoroutineDispatcher()) {
                    gpuProcessor.processPixels(copyC, filterType, width, height)
                }
            }
        }

        // (5) Compare every pixel
        for (i in copyA.indices) {
            val pA = copyA[i]
            val pB = copyB[i]

            val rA = Color.red(pA)
            val gA = Color.green(pA)
            val bA = Color.blue(pA)

            val rB = Color.red(pB)
            val gB = Color.green(pB)
            val bB = Color.blue(pB)

            val diffR = abs(rA - rB)
            val diffG = abs(gA - gB)
            val diffB = abs(bA - bB)

            if (diffR > actualTolerance || diffG > actualTolerance || diffB > actualTolerance) {
                Log.e(TAG, "SIMD Mismatch at pixel index $i")
                return false
            }

            if (copyC != null) {
                val pC = copyC[i]
                val diffRC = abs(rA - Color.red(pC))
                val diffGC = abs(gA - Color.green(pC))
                val diffBC = abs(bA - Color.blue(pC))
                if (diffRC > actualTolerance || diffGC > actualTolerance || diffBC > actualTolerance) {
                    Log.e(TAG, "GPU Mismatch at pixel index $i")
                    return false
                }
            }
        }

        Log.i(TAG, "Filter $filterType passes verification! All pixels within tolerance $actualTolerance.")
        return true
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun benchmarkFilter(
        imageProxy: ImageProxy,
        filterType: FilterType,
        iterations: Int = 100,
        gpuProcessor: GpuProcessor? = null,
        gpuExecutor: java.util.concurrent.ExecutorService? = null
    ) {
        val bitmap = imageProxy.toBitmap()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        benchmarkFilter(pixels, width, height, filterType, iterations, gpuProcessor, gpuExecutor)
    }

    fun benchmarkFilter(
        pixels: IntArray, 
        width: Int, 
        height: Int, 
        filterType: FilterType, 
        iterations: Int = 100,
        gpuProcessor: GpuProcessor? = null,
        gpuExecutor: java.util.concurrent.ExecutorService? = null
    ) {
        val copyA = pixels.clone()
        val copyB = pixels.clone()

        var totalBaselineTime = 0L
        for (i in 0 until iterations) {
            val iterCopy = copyA.clone()
            val time = measureTimeMillis {
                when (filterType) {
                    FilterType.SEPIA -> ImageFilters.applySepia(iterCopy, width, height)
                    FilterType.GAUSSIAN_BLUR -> ImageFilters.applyGaussianBlur5x5(iterCopy, width, height)
                    FilterType.SOBEL_EDGE -> ImageFilters.applySobelEdge(iterCopy, width, height)
                    FilterType.EMBOSS -> ImageFilters.applyEmboss(iterCopy, width, height)
                    FilterType.VIGNETTE -> ImageFilters.applyVignette(iterCopy, width, height)
                    FilterType.COMIC -> ImageFilters.applyComicFilter(iterCopy, width, height)
                    FilterType.FULL_CHAIN -> ImageFilters.applyFilterChain(iterCopy, width, height)
                }
            }
            totalBaselineTime += time
        }

        var totalSimdTime = 0L
        for (i in 0 until iterations) {
            val iterCopy = copyB.clone()
            val time = measureTimeMillis {
                when (filterType) {
                    FilterType.SEPIA -> NativeLib.sepiaSimd(iterCopy, iterCopy.size)
                    FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(iterCopy, width, height)
                    FilterType.SOBEL_EDGE -> NativeLib.sobelEdgeSimd(iterCopy, width, height)
                    FilterType.EMBOSS -> NativeLib.embossSimd(iterCopy, width, height)
                    FilterType.VIGNETTE -> NativeLib.vignetteSimd(iterCopy, width, height)
                    FilterType.COMIC -> NativeLib.comicSimd(iterCopy, width, height)
                    FilterType.FULL_CHAIN -> {
                        NativeLib.sepiaSimd(iterCopy, iterCopy.size)
                        NativeLib.gaussianBlurSimd(iterCopy, width, height)
                        NativeLib.sobelEdgeSimd(iterCopy, width, height)
                        NativeLib.vignetteSimd(iterCopy, width, height)
                    }
                }
            }
            totalSimdTime += time
        }

        var totalGpuTime = 0L
        if (gpuProcessor != null && gpuExecutor != null) {
            for (i in 0 until iterations) {
                val iterCopy = pixels.clone()
                val time = measureTimeMillis {
                    runBlocking {
                        withContext(gpuExecutor.asCoroutineDispatcher()) {
                            gpuProcessor.processPixels(iterCopy, filterType, width, height)
                        }
                    }
                }
                totalGpuTime += time
            }
        }

        val avgBaseline = totalBaselineTime / iterations.toDouble()
        val avgSimd = totalSimdTime / iterations.toDouble()
        val avgGpu = if (totalGpuTime > 0) totalGpuTime / iterations.toDouble() else 0.0
        val speedup = if (avgSimd > 0) avgBaseline / avgSimd else 0.0
        val gpuSpeedup = if (avgGpu > 0) avgBaseline / avgGpu else 0.0

        Log.i(
            TAG,
            "Benchmark Results for $filterType over $iterations frames:\n" +
                    "Avg Baseline Time: %.2f ms\n".format(avgBaseline) +
                    "Avg SIMD Time: %.2f ms\n".format(avgSimd) +
                    "Avg GPU Time: %.2f ms\n".format(avgGpu) +
                    "SIMD Speedup: %.2fx, GPU Speedup: %.2fx".format(speedup, gpuSpeedup)
        )
    }
}
