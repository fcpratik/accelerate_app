package com.example.acceleratedcamera

object NativeLib {
    init
    { // Load the .so file when this object is first accessed
        System.loadLibrary("accelerated-camera")
    }
    /** * Process the pixel array using ARM NEON SIMD intrinsics. *
    @param
    pixels IntArray of ARGB_8888 pixels (modified in-place) *
    @param
    length Number of pixels (width * height) */
    external fun processPixelsSIMD(pixels: IntArray, length: Int)
    external fun sepiaSimd(pixels: IntArray, length: Int)
    external fun gaussianBlurSimd(pixels: IntArray, width: Int, height: Int)
    external fun sobelEdgeSimd(pixels: IntArray, width: Int, height: Int)
    external fun embossSimd(pixels: IntArray, width: Int, height: Int)
    external fun vignetteSimd(pixels: IntArray, width: Int, height: Int)
    external fun comicSimd(pixels: IntArray, width: Int, height: Int)
}