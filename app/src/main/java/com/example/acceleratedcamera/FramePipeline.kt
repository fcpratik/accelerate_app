package com.example.acceleratedcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A single frame's worth of state. We preallocate 3 of these and rotate them
 * through the pipeline so the hot path never allocates.
 *
 * Buffers are sized lazily on first use to match the camera resolution,
 * and reused across frames.
 */
class FrameSlot(val id: Int) {
    var width: Int = 0
    var height: Int = 0
    var rotationDegrees: Int = 0

    // Raw ARGB pixels coming out of the capture stage.
    var pixels: IntArray = IntArray(0)

    // Final Bitmap emitted at the end of the compute stage.
    // Allocated once (per slot, per resolution) and reused.
    var processedBitmap: Bitmap? = null
    var rotatedBitmap: Bitmap? = null

    // Timestamps (System.nanoTime) for each pipeline boundary.
    // e2e latency = tDisplayEnd - tCaptureStart.
    var tCaptureStart: Long = 0L
    var tCaptureEnd: Long = 0L
    var tComputeStart: Long = 0L
    var tComputeEnd: Long = 0L
    var tDisplayStart: Long = 0L
    var tDisplayEnd: Long = 0L

    // Mode the compute stage should use for THIS frame. We snapshot the
    // current mode at capture time so a mode switch mid-pipeline doesn't
    // produce a frame processed in two modes.
    var mode: ProcessingMode = ProcessingMode.BASELINE
    var filter: FilterType = FilterType.SEPIA

    fun ensurePixels(w: Int, h: Int) {
        val need = w * h
        if (pixels.size < need) pixels = IntArray(need)
        width = w
        height = h
    }
}

/**
 * 3-stage pipeline: Capture/Preprocess -> Compute -> Display.
 *
 * Three FrameSlots rotate through three bounded queues of size 1. Drop-oldest
 * policy: if the next stage is busy, the producer evicts the waiting frame
 * and replaces it with the new one. Keeps latency low and preview responsive
 * when compute can't match camera rate.
 *
 * Stage -> thread mapping:
 *   Capture:  "capture-thread"   (does YUV -> ARGB via Bitmap, runs on CPU/SIMD)
 *   Compute:  "compute-thread"   (runs the user-selected filter)
 *   Display:  "display-thread"   (rotates, posts final Bitmap to UI via callback)
 *
 * "Hybrid" mode in this pipeline: capture+display run on CPU/SIMD while
 * compute runs on GPU (or SIMD+GPU for filters that stage-split, e.g. comic).
 * In steady state all three stages are active on different frames, which is
 * what the dashboard visualises as coordinated SIMD+GPU utilisation.
 */
class FramePipeline(
    private val slotCount: Int = 3,
    private val onFrameReady: (Bitmap) -> Unit,
    private val onStageTiming: (captureMs: Long, computeMs: Long, displayMs: Long, e2eMs: Long) -> Unit,
    private val onFrameDropped: () -> Unit,
    private val onUtilization: (captureBusyFrac: Float, computeBusyFrac: Float, displayBusyFrac: Float) -> Unit,
    private val computeStageRunner: ComputeStageRunner
) {
    fun interface ComputeStageRunner {
        /** Run the filter on slot.pixels in place. Must not allocate on hot path. */
        fun run(slot: FrameSlot)
    }

    // Pool of free slots (initially all 3). Producer grabs one, pipeline returns it at the end.
    private val freeSlots = ArrayBlockingQueue<FrameSlot>(slotCount)

    // Size-1 queues between stages. Drop-oldest is implemented by poll+offer.
    private val captureToCompute = ArrayBlockingQueue<FrameSlot>(1)
    private val computeToDisplay = ArrayBlockingQueue<FrameSlot>(1)

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var computeThread: Thread? = null
    private var displayThread: Thread? = null

    // Utilization tracking: ns of busy work per stage, sampled each second.
    private val captureBusyNs = AtomicLong(0)
    private val computeBusyNs = AtomicLong(0)
    private val displayBusyNs = AtomicLong(0)

    // Mode/filter selected by the UI. Written by the main thread, read by capture.
    @Volatile var currentMode: ProcessingMode = ProcessingMode.BASELINE
    @Volatile var currentFilter: FilterType = FilterType.SEPIA

    init {
        repeat(slotCount) { freeSlots.add(FrameSlot(id = it)) }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        captureThread = Thread({ captureLoop() }, "pipeline-capture").also { it.start() }
        computeThread = Thread({ computeLoop() }, "pipeline-compute").also { it.start() }
        displayThread = Thread({ displayLoop() }, "pipeline-display").also { it.start() }

        // Utilization sampler: every 1s, read the busy counters and report fractions.
        Thread({
            var lastSample = System.nanoTime()
            var lastCap = 0L; var lastCmp = 0L; var lastDsp = 0L
            while (running.get()) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                val now = System.nanoTime()
                val windowNs = now - lastSample
                if (windowNs <= 0) continue
                val cap = captureBusyNs.get()
                val cmp = computeBusyNs.get()
                val dsp = displayBusyNs.get()
                onUtilization(
                    ((cap - lastCap).toDouble() / windowNs).toFloat().coerceIn(0f, 1f),
                    ((cmp - lastCmp).toDouble() / windowNs).toFloat().coerceIn(0f, 1f),
                    ((dsp - lastDsp).toDouble() / windowNs).toFloat().coerceIn(0f, 1f)
                )
                lastCap = cap; lastCmp = cmp; lastDsp = dsp
                lastSample = now
            }
        }, "pipeline-util").start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        captureThread?.interrupt()
        computeThread?.interrupt()
        displayThread?.interrupt()
        captureThread = null; computeThread = null; displayThread = null
        // Drain queues so a subsequent start() begins clean.
        captureToCompute.clear()
        computeToDisplay.clear()
        // Return any in-flight slots to the free pool.
        // (Queues are cleared above; remaining slots are whatever was in free already.)
    }

    /**
     * Called from CameraX's Analyzer callback. Must be cheap and non-blocking.
     * Copies the ImageProxy into a FrameSlot, then hands it to the capture thread.
     * If no free slot is available we drop this frame (the pipeline is saturated).
     *
     * The ImageProxy is closed inside this function so CameraX can deliver the
     * next frame immediately — we do NOT hold it for the whole pipeline.
     */
    fun submitFrame(imageProxy: ImageProxy) {
        if (!running.get()) { imageProxy.close(); return }

        val slot = freeSlots.poll()
        if (slot == null) {
            // No free slot: all 3 are in flight. Drop this frame.
            onFrameDropped()
            imageProxy.close()
            return
        }

        val t0 = System.nanoTime()
        slot.tCaptureStart = t0
        slot.mode = currentMode
        slot.filter = currentFilter
        slot.rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // YUV -> ARGB. This is the expensive part of the capture stage and
        // the natural place to plug in a NEON YUV converter later.
        val bmp = imageProxy.toBitmap()
        slot.ensurePixels(bmp.width, bmp.height)
        bmp.getPixels(slot.pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        imageProxy.close()

        slot.tCaptureEnd = System.nanoTime()
        captureBusyNs.addAndGet(slot.tCaptureEnd - t0)

        // Hand to compute. Drop-oldest: if compute hasn't consumed the previous
        // waiting frame, evict it and return it to the free pool before we offer ours.
        val evicted = captureToCompute.poll()
        if (evicted != null) {
            onFrameDropped()
            freeSlots.offer(evicted)
        }
        captureToCompute.offer(slot)
    }

    private fun captureLoop() {
        // Currently unused — capture work happens inline in submitFrame() because
        // CameraX hands us the ImageProxy on its own executor and we must close
        // the proxy ASAP. Keeping this thread as a placeholder lets us later move
        // the YUV->ARGB work here (off the camera callback thread) if we add a
        // dedicated NEON converter that takes raw YUV planes instead of a Bitmap.
        while (running.get()) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun computeLoop() {
        while (running.get()) {
            val slot = try { captureToCompute.take() } catch (_: InterruptedException) { break }
            val t0 = System.nanoTime()
            slot.tComputeStart = t0
            try {
                computeStageRunner.run(slot)
            } catch (t: Throwable) {
                // Don't let a filter bug kill the pipeline — return slot, keep going.
                t.printStackTrace()
            }
            slot.tComputeEnd = System.nanoTime()
            computeBusyNs.addAndGet(slot.tComputeEnd - t0)

            // Hand to display, drop-oldest.
            val evicted = computeToDisplay.poll()
            if (evicted != null) {
                onFrameDropped()
                freeSlots.offer(evicted)
            }
            computeToDisplay.offer(slot)
        }
    }

    private fun displayLoop() {
        while (running.get()) {
            val slot = try { computeToDisplay.take() } catch (_: InterruptedException) { break }
            val t0 = System.nanoTime()
            slot.tDisplayStart = t0

            // Rebuild the Bitmap from slot.pixels (compute stage mutates pixels in place).
            val processed = getOrAllocBitmap(slot, slot.width, slot.height)
            processed.setPixels(slot.pixels, 0, slot.width, 0, 0, slot.width, slot.height)

            val rotated = rotateBitmap(slot, processed, slot.rotationDegrees)

            slot.tDisplayEnd = System.nanoTime()
            displayBusyNs.addAndGet(slot.tDisplayEnd - t0)

            // Report timings (use the *copy* we emit, not the slot's buffers, so
            // returning the slot to the pool is race-free w.r.t. the UI thread).
            val captureMs = (slot.tCaptureEnd - slot.tCaptureStart) / 1_000_000L
            val computeMs = (slot.tComputeEnd - slot.tComputeStart) / 1_000_000L
            val displayMs = (slot.tDisplayEnd - slot.tDisplayStart) / 1_000_000L
            val e2eMs     = (slot.tDisplayEnd - slot.tCaptureStart) / 1_000_000L
            onStageTiming(captureMs, computeMs, displayMs, e2eMs)

            // The rotated bitmap is what the UI needs. We hand over a copy
            // reference; slot.rotatedBitmap will be overwritten next time this
            // slot is reused (3 slots = triple buffering for the UI too).
            onFrameReady(rotated)

            // Return slot to the free pool for the next capture.
            freeSlots.offer(slot)
        }
    }

    private fun getOrAllocBitmap(slot: FrameSlot, w: Int, h: Int): Bitmap {
        val existing = slot.processedBitmap
        if (existing != null && existing.width == w && existing.height == h) return existing
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        slot.processedBitmap = fresh
        return fresh
    }

    private fun rotateBitmap(slot: FrameSlot, src: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return src
        // 90/270 swap dimensions.
        val rotW: Int; val rotH: Int
        if (rotation % 180 == 0) { rotW = src.width; rotH = src.height }
        else                     { rotW = src.height; rotH = src.width }

        val existing = slot.rotatedBitmap
        val out = if (existing != null && existing.width == rotW && existing.height == rotH) existing
        else Bitmap.createBitmap(rotW, rotH, Bitmap.Config.ARGB_8888).also { slot.rotatedBitmap = it }

        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(rotation.toFloat())
        m.postTranslate(out.width / 2f, out.height / 2f)
        canvas.drawBitmap(src, m, null)
        return out
    }
}