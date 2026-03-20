# Accelerated Video Processing System

An advanced Android application demonstrating high-performance, real-time computational photography and video processing. This project explores architectural speedups by comparing naive scalar processing against highly optimized SIMD (Single Instruction, Multiple Data) and GPU-accelerated pipelines.

## 🚀 Features

- **Real-Time Camera Pipeline**: Leverages Android CameraX for low-latency frame extraction.
- **Multiple Processing Modes**:
  - **Baseline (Kotlin)**: Standard scalar (SISD) implementations for correct ground-truth outputs.
  - **SIMD (ARM NEON)**: Vectorized C++ implementations utilizing native ARM intrinsics to process multiple pixels per CPU clock cycle.
  - *(WIP)* **GPU (OpenGL ES Compute Shaders)**: Massively parallelized image processing using `glDispatchCompute`.
- **Live Performance Dashboard**: Monitors real-time FPS output, CPU latency per frame, and relative speedup between Baseline and SIMD architectures.
- **Five Specialized Filters**:
  - **Sepia** (Color channel mapping)
  - **Gaussian Blur 5x5** (Spatial convolution)
  - **Sobel Edge Detection** (Matrix convolution + Gradient magnitude)
  - **Emboss** (Directional edge highlighting)
  - **Vignette** (Radial darkening via Euclidean distance)

## 🧠 Technical Highlights & Optimizations

The SIMD pipeline was extensively optimized to break past the "Memory Wall" limitations typical of mobile CPUs:

1. **Algorithm Re-architecture**: Converted algorithms like 2D Gaussian Blur into **Separable 1D operations**, reducing complexity from $O(N^2)$ to $O(2N)$.
2. **Multi-Pass Decoupling**: Sobel edge detection was restructured into two distinct passes (Grayscale conversion -> Vectorized Convolution) to prevent CPU cache thrashing.
3. **Structural Loads**: Used `vld4q_u8` to instantly load, de-interleave, and vector-pack 16 separate ARGB pixels (64 bytes) into 4 physical hardware registers in a single cycle. 
4. **Fixed-Point Arithmetic**: Removed slow floating-point math across all SIMD filters, shifting to scaled integer multiplication and pure bitwise shifting (`>> 8` for division by 256).
5. **Overlapping Cache Loads**: Sliding window convolutions leverage immediate memory overlaps mapped closely into the ultra-fast L1 cache (`vld1_u8(ptr-1)`, `ptr`, `ptr+1`).
6. **Pure Bitwise Arithmetic**: Replaced mathematical additions and multiplications in Gaussian Blur kernels with native bit-shifts (`x*6` becomes `(x<<2)+(x<<1)`).

These optimizations yielded a **> 2.4x performance gain** over the baseline Kotlin routines.

## 🛠️ Technology Stack

- **Android SDK & Kotlin**: Primary application logic, Jetpack Compose UI, and coroutine pipelines.
- **CameraX API**: Image Analysis framework capable of yielding concurrent RGBA/YUV `ImageProxy` objects.
- **Android NDK (C/C++)**: Native JNI bridge architecture mapping memory spaces.
- **ARM NEON Intrinsics**: Direct interface to 128-bit mobile processor SIMD registers.
- **CMake**: Build configuration pipeline for packaging the `.cpp` source into `.so` shared libraries.

## 🖥️ Project Structure

```text
├── app/src/main/
│   ├── java/com/example/acceleratedcamera/
│   │   ├── MainActivity.kt         # Compose UI & Application Dashboard
│   │   ├── CameraPipeline.kt       # CameraX analysis logic
│   │   ├── NativeLib.kt            # JNI mapping to native logic
│   │   └── filters/                # Baseline Kotlin filters
│   └── cpp/
│       ├── native-lib.cpp          # JNI boilerplate and memory allocation
│       └── native-filters.cpp      # Pure ARM NEON SIMD implementations
├── CMakeLists.txt                  # NDK config
└── report.md                       # Complete internal documentation & journey
```

## ⚙️ Running Locally

1. **Prerequisites**:
   - Install **Android Studio**.
   - Ensure the **Android NDK** and **CMake** are installed via the SDK Manager (`Tools > SDK Manager > SDK Tools`).

2. **Clone & Open**:
   Open the project directly in Android Studio.

3. **Build**:
   Allow Gradle to sync. C++ source files will automatically be pulled and compiled by CMake.

4. **Deploy**:
   **Crucially**, run this on a **Physical Android Device**. Emulators often lack true architectural support for ARM NEON SIMD hardware operations and do not support low-level camera performance properly.

---

> _For an in-depth reading on how this project circumvents Gather/Scatter anti-patterns, Flynn's Taxonomy, and cache thrashing, see the internal `report.md`._
