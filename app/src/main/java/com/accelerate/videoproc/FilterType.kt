package com.accelerate.videoproc

/**
 * Available compute-intensive image filters.
 * Each operates on every pixel or neighborhood of pixels.
 */
enum class FilterType(val displayName: String) {
    NONE("No Filter"),
    GRAYSCALE("Grayscale"),
    SOBEL_EDGE("Sobel Edge"),
    GAUSSIAN_BLUR("Gaussian 5x5"),
    SHARPEN("Sharpen"),
    EMBOSS("Emboss");

    fun next(): FilterType {
        val filters = values()
        return filters[(ordinal + 1) % filters.size]
    }
}
