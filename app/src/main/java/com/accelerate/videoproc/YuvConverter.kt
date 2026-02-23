package com.accelerate.videoproc

import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

/**
 * Converts YUV_420_888 image data (from CameraX) to ARGB pixel array.
 * Day 20: Scalar CPU implementation with downscaling.
 */
object YuvConverter {

    fun yuvToArgbScaled(image: ImageProxy, scaleFactor: Int = 2): Triple<IntArray, Int, Int> {
        val srcWidth = image.width
        val srcHeight = image.height
        val dstWidth = srcWidth / scaleFactor
        val dstHeight = srcHeight / scaleFactor
        val pixels = IntArray(dstWidth * dstHeight)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (dy in 0 until dstHeight) {
            val sy = dy * scaleFactor
            for (dx in 0 until dstWidth) {
                val sx = dx * scaleFactor

                val yIndex = sy * yRowStride + sx
                val uvIndex = (sy / 2) * uvRowStride + (sx / 2) * uvPixelStride

                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF)
                val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                var r = yVal + (1.370705f * vVal).toInt()
                var g = yVal - (0.337633f * uVal).toInt() - (0.698001f * vVal).toInt()
                var b = yVal + (1.732446f * uVal).toInt()

                r = clamp(r); g = clamp(g); b = clamp(b)

                pixels[dy * dstWidth + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Triple(pixels, dstWidth, dstHeight)
    }

    private fun clamp(v: Int): Int = max(0, min(255, v))
}
