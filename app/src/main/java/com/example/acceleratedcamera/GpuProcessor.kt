package com.example.acceleratedcamera

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Manages a headless OpenGL ES 3.1 context and compiles one compute shader
 * per filter type. All GL calls must happen on the same thread that called
 * [initialize] — callers are responsible for dispatching to a dedicated
 * single-thread executor.
 */
class GpuProcessor(private val context: Context) {

    // EGL handles
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Compiled program IDs per filter
    private val programs = mutableMapOf<FilterType, Int>()

    // Two SSBOs: one for input, one for output (resized lazily)
    private var ssboIn: Int  = 0
    private var ssboOut: Int = 0
    private var ssboCapacityBytes: Int = 0

    // Uniform location caches per program
    private data class Uniforms(
        val pixelCount: Int = -1,
        val width: Int = -1,
        val height: Int = -1
    )
    private val uniforms = mutableMapOf<FilterType, Uniforms>()

    private var initialized = false

    private val twoBufferFilters = setOf(
        FilterType.GAUSSIAN_BLUR,
        FilterType.SOBEL_EDGE,
        FilterType.EMBOSS,
        FilterType.COMIC
    )

    /**
     * Compile all 5 compute shaders and set up the EGL context.
     * Must be called exactly once, on the thread that will own the GL context.
     */
    fun initialize() {
        if (initialized) return

        // ── EGL setup ──────────────────────────────────────────────────────────
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        check(numConfigs[0] > 0) { "No EGL config found for OpenGL ES 3.1" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // ── Compile shaders ────────────────────────────────────────────────────
        val shaderFiles = mapOf(
            FilterType.SEPIA         to "sepia.comp",
            FilterType.GAUSSIAN_BLUR to "gaussian_blur.comp",
            FilterType.SOBEL_EDGE    to "sobel_edge.comp",
            FilterType.EMBOSS        to "emboss.comp",
            FilterType.VIGNETTE      to "vignette.comp",
            FilterType.COMIC         to "comic.comp"
        )

        for ((filter, fileName) in shaderFiles) {
            val src = context.assets.open(fileName).bufferedReader().readText()
            val programId = compileAndLink(src, fileName)
            programs[filter] = programId

            uniforms[filter] = Uniforms(
                pixelCount = GLES31.glGetUniformLocation(programId, "uPixelCount"),
                width      = GLES31.glGetUniformLocation(programId, "uWidth"),
                height     = GLES31.glGetUniformLocation(programId, "uHeight")
            )
        }

        // ── Allocate SSBOs ─────────────────────────────────────────────────────
        val bufs = IntArray(2)
        GLES31.glGenBuffers(2, bufs, 0)
        ssboIn  = bufs[0]
        ssboOut = bufs[1]

        initialized = true
    }

    /**
     * Apply [filter] to [pixels] in-place using the GPU.
     * Must be called on the same thread that called [initialize].
     *
     * @param pixels  Flat ARGB_8888 pixel array (row-major).
     * @param filter  Which filter to run.
     * @param width   Image width in pixels.
     * @param height  Image height in pixels.
     */
    fun processPixels(pixels: IntArray, filter: FilterType, width: Int, height: Int) {
        if (filter == FilterType.FULL_CHAIN) {
            processPixels(pixels, FilterType.SEPIA, width, height)
            processPixels(pixels, FilterType.GAUSSIAN_BLUR, width, height)
            processPixels(pixels, FilterType.SOBEL_EDGE, width, height)
            processPixels(pixels, FilterType.VIGNETTE, width, height)
            return
        }
        check(initialized) { "GpuProcessor.initialize() must be called first" }

        val programId = programs[filter] ?: return
        val u = uniforms[filter] ?: return
        val pixelCount = pixels.size
        val byteCount = pixelCount * 4

        // ── Upload input to ssboIn ─────────────────────────────────────────────
        ensureSsboCapacity(byteCount)
        val cpuBuf: IntBuffer = ByteBuffer.allocateDirect(byteCount)
            .order(ByteOrder.nativeOrder()).asIntBuffer().put(pixels)
        cpuBuf.position(0)

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboIn)
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, byteCount, cpuBuf)

        // ── Bind SSBOs ─────────────────────────────────────────────────────────
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssboIn)
        if (twoBufferFilters.contains(filter)) {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboOut)
        }

        // ── Dispatch ───────────────────────────────────────────────────────────
        GLES31.glUseProgram(programId)

        // Set uniforms based on which ones this shader uses
        if (u.pixelCount >= 0) GLES31.glUniform1i(u.pixelCount, pixelCount)
        if (u.width >= 0)      GLES31.glUniform1i(u.width, width)
        if (u.height >= 0)     GLES31.glUniform1i(u.height, height)

        val is2D = twoBufferFilters.contains(filter) || filter == FilterType.VIGNETTE
        if (is2D) {
            val groupsX = (width  + 15) / 16
            val groupsY = (height + 15) / 16
            GLES31.glDispatchCompute(groupsX, groupsY, 1)
        } else {
            // 1D dispatch for sepia (in-place, linear)
            val groups = (pixelCount + 255) / 256
            GLES31.glDispatchCompute(groups, 1, 1)
        }

        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        // ── Read back ──────────────────────────────────────────────────────────
        val readSsbo = if (twoBufferFilters.contains(filter)) ssboOut else ssboIn
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, readSsbo)
        val mapped = GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, byteCount, GLES31.GL_MAP_READ_BIT
        ) as ByteBuffer
        mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels)
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
    }

    /**
     * Release all OpenGL and EGL resources.
     * Must be called on the same thread that called [initialize].
     */
    fun release() {
        if (!initialized) return
        GLES31.glDeleteBuffers(2, intArrayOf(ssboIn, ssboOut), 0)
        for (pid in programs.values) GLES31.glDeleteProgram(pid)
        programs.clear()
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        initialized = false
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun ensureSsboCapacity(requiredBytes: Int) {
        if (requiredBytes <= ssboCapacityBytes) return

        // Grow ssboIn to the new size
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboIn)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, requiredBytes, null, GLES31.GL_DYNAMIC_COPY)

        // Grow ssboOut to the new size at the exact same time
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboOut)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, requiredBytes, null, GLES31.GL_DYNAMIC_COPY)

        ssboCapacityBytes = requiredBytes
    }

    private fun compileAndLink(src: String, name: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, src)
        GLES31.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            throw RuntimeException("Shader '$name' compile failed:\n$log")
        }

        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)

        val linked = IntArray(1)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(program)
            GLES31.glDeleteProgram(program)
            throw RuntimeException("Program '$name' link failed:\n$log")
        }

        GLES31.glDeleteShader(shader)
        return program
    }
}
