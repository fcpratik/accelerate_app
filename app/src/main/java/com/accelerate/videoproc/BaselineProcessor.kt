package com.accelerate.videoproc

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Baseline (scalar CPU) frame processor.
 * All operations are performed pixel-by-pixel without SIMD or GPU acceleration.
 * This serves as the correctness reference and performance baseline.
 */
object BaselineProcessor {

    fun processFrame(pixels: IntArray, width: Int, height: Int, filter: FilterType): IntArray {
        return when (filter) {
            FilterType.NONE -> pixels.copyOf()  // Passthrough: show original color frame
            FilterType.GRAYSCALE -> applyGrayscale(pixels, width, height)
            FilterType.SOBEL_EDGE -> applySobelEdge(pixels, width, height)
            FilterType.GAUSSIAN_BLUR -> applyConvolution(pixels, width, height, GAUSSIAN_5x5, 256)
            FilterType.SHARPEN -> applyConvolution(pixels, width, height, SHARPEN_3x3, 1)
            FilterType.EMBOSS -> applyConvolution(pixels, width, height, EMBOSS_3x3, 1, 128)
        }
    }

    // ==================== GRAYSCALE ====================
    private fun applyGrayscale(pixels: IntArray, width: Int, height: Int): IntArray {
        val output = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (77 * r + 150 * g + 29 * b) shr 8
            output[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        return output
    }

    // ==================== SOBEL EDGE DETECTION ====================
    private fun applySobelEdge(pixels: IntArray, width: Int, height: Int): IntArray {
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            gray[i] = (77 * r + 150 * g + 29 * b) shr 8
        }

        val output = IntArray(pixels.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val gx = -gray[(y - 1) * width + (x - 1)] +
                        gray[(y - 1) * width + (x + 1)] +
                        -2 * gray[y * width + (x - 1)] +
                        2 * gray[y * width + (x + 1)] +
                        -gray[(y + 1) * width + (x - 1)] +
                        gray[(y + 1) * width + (x + 1)]

                val gy = -gray[(y - 1) * width + (x - 1)] +
                        -2 * gray[(y - 1) * width + x] +
                        -gray[(y - 1) * width + (x + 1)] +
                        gray[(y + 1) * width + (x - 1)] +
                        2 * gray[(y + 1) * width + x] +
                        gray[(y + 1) * width + (x + 1)]

                val mag = min(255, abs(gx) + abs(gy))
                output[y * width + x] = (0xFF shl 24) or (mag shl 16) or (mag shl 8) or mag
            }
        }

        for (x in 0 until width) {
            output[x] = 0xFF000000.toInt()
            output[(height - 1) * width + x] = 0xFF000000.toInt()
        }
        for (y in 0 until height) {
            output[y * width] = 0xFF000000.toInt()
            output[y * width + width - 1] = 0xFF000000.toInt()
        }
        return output
    }

    // ==================== GENERIC CONVOLUTION ====================
    private fun applyConvolution(
        pixels: IntArray, width: Int, height: Int,
        kernel: Array<IntArray>, divisor: Int, bias: Int = 0
    ): IntArray {
        val output = IntArray(pixels.size)
        val kSize = kernel.size
        val kHalf = kSize / 2

        for (y in kHalf until height - kHalf) {
            for (x in kHalf until width - kHalf) {
                var sumR = 0; var sumG = 0; var sumB = 0

                for (ky in 0 until kSize) {
                    for (kx in 0 until kSize) {
                        val pixel = pixels[(y + ky - kHalf) * width + (x + kx - kHalf)]
                        val weight = kernel[ky][kx]
                        sumR += ((pixel shr 16) and 0xFF) * weight
                        sumG += ((pixel shr 8) and 0xFF) * weight
                        sumB += (pixel and 0xFF) * weight
                    }
                }

                val r = clamp((sumR / divisor) + bias)
                val g = clamp((sumG / divisor) + bias)
                val b = clamp((sumB / divisor) + bias)
                output[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        for (x in 0 until width) {
            for (by in 0 until kHalf) {
                output[by * width + x] = pixels[by * width + x]
                output[(height - 1 - by) * width + x] = pixels[(height - 1 - by) * width + x]
            }
        }
        for (y in kHalf until height - kHalf) {
            for (bx in 0 until kHalf) {
                output[y * width + bx] = pixels[y * width + bx]
                output[y * width + width - 1 - bx] = pixels[y * width + width - 1 - bx]
            }
        }
        return output
    }

    private fun clamp(value: Int): Int = max(0, min(255, value))

    private val GAUSSIAN_5x5 = arrayOf(
        intArrayOf(1, 4, 6, 4, 1),
        intArrayOf(4, 16, 24, 16, 4),
        intArrayOf(6, 24, 36, 24, 6),
        intArrayOf(4, 16, 24, 16, 4),
        intArrayOf(1, 4, 6, 4, 1)
    )

    private val SHARPEN_3x3 = arrayOf(
        intArrayOf(0, -1, 0),
        intArrayOf(-1, 5, -1),
        intArrayOf(0, -1, 0)
    )

    private val EMBOSS_3x3 = arrayOf(
        intArrayOf(-2, -1, 0),
        intArrayOf(-1, 1, 1),
        intArrayOf(0, 1, 2)
    )
}
