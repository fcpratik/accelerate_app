package com.example.acceleratedcamera

import android.graphics.Color
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.system.measureTimeMillis

object VerificationUtils {
    private const val TAG = "VerificationUtils"

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun verifyFilterCorrectness(
        imageProxy: ImageProxy,
        filterType: FilterType,
        maxTolerance: Int = 2
    ): Boolean {
        val bitmap = imageProxy.toBitmap()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return verifyFilterCorrectness(pixels, width, height, filterType, maxTolerance)
    }

    fun verifyFilterCorrectness(
        pixels: IntArray, 
        width: Int, 
        height: Int, 
        filterType: FilterType, 
        maxTolerance: Int = 2
    ): Boolean {
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
            FilterType.FULL_CHAIN -> ImageFilters.applyFilterChain(copyA, width, height)
        }

        // (4) Apply SIMD filter on copy B
        when (filterType) {
            FilterType.SEPIA -> NativeLib.sepiaSimd(copyB, copyB.size)
            FilterType.GAUSSIAN_BLUR -> NativeLib.gaussianBlurSimd(copyB, width, height)
            FilterType.SOBEL_EDGE -> NativeLib.sobelEdgeSimd(copyB, width, height)
            FilterType.EMBOSS -> NativeLib.embossSimd(copyB, width, height)
            FilterType.VIGNETTE -> NativeLib.vignetteSimd(copyB, width, height)
            FilterType.FULL_CHAIN -> {
                NativeLib.sepiaSimd(copyB, copyB.size)
                NativeLib.gaussianBlurSimd(copyB, width, height)
                NativeLib.sobelEdgeSimd(copyB, width, height)
                NativeLib.vignetteSimd(copyB, width, height)
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

            if (diffR > maxTolerance || diffG > maxTolerance || diffB > maxTolerance) {
                // Log the first mismatched pixel index and its values
                Log.e(
                    TAG,
                    "Mismatch at pixel index $i (x=${i % width}, y=${i / width}): \n" +
                            "Baseline RGB: ($rA, $gA, $bA) \n" +
                            "SIMD RGB: ($rB, $gB, $bB) \n" +
                            "Diff RGB: ($diffR, $diffG, $diffB)"
                )
                return false
            }
        }

        Log.i(TAG, "Filter $filterType passes verification! All pixels within tolerance $maxTolerance.")
        return true
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun benchmarkFilter(
        imageProxy: ImageProxy,
        filterType: FilterType,
        iterations: Int = 100
    ) {
        val bitmap = imageProxy.toBitmap()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        benchmarkFilter(pixels, width, height, filterType, iterations)
    }

    fun benchmarkFilter(
        pixels: IntArray, 
        width: Int, 
        height: Int, 
        filterType: FilterType, 
        iterations: Int = 100
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

        val avgBaseline = totalBaselineTime / iterations.toDouble()
        val avgSimd = totalSimdTime / iterations.toDouble()
        val speedup = if (avgSimd > 0) avgBaseline / avgSimd else 0.0

        Log.i(
            TAG,
            "Benchmark Results for $filterType over $iterations frames:\n" +
                    "Avg Baseline Time: %.2f ms\n".format(avgBaseline) +
                    "Avg SIMD Time: %.2f ms\n".format(avgSimd) +
                    "Speedup: %.2fx".format(speedup)
        )
    }
}
