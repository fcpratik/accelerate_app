package com.example.acceleratedcamera

import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.sqrt

object ImageFilters {

    fun applySepia(pixels: IntArray, width: Int, height: Int) {
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = Color.alpha(pixel)
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            val newR = (gray * 1.2f + 40).toInt().coerceIn(0, 255)
            val newG = (gray * 1.0f + 20).toInt().coerceIn(0, 255)
            val newB = (gray * 0.8f).toInt().coerceIn(0, 255)

            pixels[i] = Color.argb(a, newR, newG, newB)
        }
    }

    fun applyGaussianBlur5x5(pixels: IntArray, width: Int, height: Int) {
        val kernel = arrayOf(
            intArrayOf(1, 4, 7, 4, 1),
            intArrayOf(4, 16, 26, 16, 4),
            intArrayOf(7, 26, 41, 26, 7),
            intArrayOf(4, 16, 26, 16, 4),
            intArrayOf(1, 4, 7, 4, 1)
        )
        val kernelSum = 273
        val tempPixels = pixels.clone()

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                val a = Color.alpha(tempPixels[y * width + x])

                for (ky in -2..2) {
                    for (kx in -2..2) {
                        val nx = (x + kx).coerceIn(0, width - 1)
                        val ny = (y + ky).coerceIn(0, height - 1)
                        val neighbor = tempPixels[ny * width + nx]
                        val w = kernel[ky + 2][kx + 2]

                        sumR += Color.red(neighbor) * w
                        sumG += Color.green(neighbor) * w
                        sumB += Color.blue(neighbor) * w
                    }
                }
                pixels[y * width + x] = Color.argb(
                    a,
                    sumR / kernelSum,
                    sumG / kernelSum,
                    sumB / kernelSum
                )
            }
        }
    }

    fun applySobelEdge(pixels: IntArray, width: Int, height: Int) {
        val gxKernel = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )
        val gyKernel = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )
        val tempPixels = pixels.clone()

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumX = 0
                var sumY = 0
                val a = Color.alpha(tempPixels[y * width + x])

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val nx = (x + kx).coerceIn(0, width - 1)
                        val ny = (y + ky).coerceIn(0, height - 1)
                        val neighbor = tempPixels[ny * width + nx]
                        val r = Color.red(neighbor)
                        val g = Color.green(neighbor)
                        val b = Color.blue(neighbor)
                        val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()

                        val wX = gxKernel[ky + 1][kx + 1]
                        val wY = gyKernel[ky + 1][kx + 1]

                        sumX += gray * wX
                        sumY += gray * wY
                    }
                }
                val magnitude = sqrt((sumX * sumX + sumY * sumY).toDouble()).toInt().coerceIn(0, 255)
                pixels[y * width + x] = Color.argb(a, magnitude, magnitude, magnitude)
            }
        }
    }

    fun applyEmboss(pixels: IntArray, width: Int, height: Int) {
        val kernel = arrayOf(
            intArrayOf(-2, -1, 0),
            intArrayOf(-1, 1, 1),
            intArrayOf(0, 1, 2)
        )
        val tempPixels = pixels.clone()

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                val a = Color.alpha(tempPixels[y * width + x])

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val nx = (x + kx).coerceIn(0, width - 1)
                        val ny = (y + ky).coerceIn(0, height - 1)
                        val neighbor = tempPixels[ny * width + nx]
                        val w = kernel[ky + 1][kx + 1]

                        sumR += Color.red(neighbor) * w
                        sumG += Color.green(neighbor) * w
                        sumB += Color.blue(neighbor) * w
                    }
                }
                val newR = (sumR + 128).coerceIn(0, 255)
                val newG = (sumG + 128).coerceIn(0, 255)
                val newB = (sumB + 128).coerceIn(0, 255)
                pixels[y * width + x] = Color.argb(a, newR, newG, newB)
            }
        }
    }

    fun applyVignette(pixels: IntArray, width: Int, height: Int) {
        val centerX = width / 2
        val centerY = height / 2
        val maxDist = sqrt((centerX * centerX + centerY * centerY).toDouble())
        val strength = 1.0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dist = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble())
                val factor = (1.0 - (dist / maxDist) * strength).coerceIn(0.0, 1.0)

                val idx = y * width + x
                val pixel = pixels[idx]
                val a = Color.alpha(pixel)
                val r = (Color.red(pixel) * factor).toInt()
                val g = (Color.green(pixel) * factor).toInt()
                val b = (Color.blue(pixel) * factor).toInt()

                pixels[idx] = Color.argb(a, r, g, b)
            }
        }
    }

    fun applyFilterChain(pixels: IntArray, width: Int, height: Int) {
        applySepia(pixels, width, height)
        applyGaussianBlur5x5(pixels, width, height)
        applySobelEdge(pixels, width, height)
        applyVignette(pixels, width, height)
    }
}
