package com.inksdk.ink

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Onyx Boox raw-drawing controller. Uses [TouchHelper] to paint strokes
 * directly at EPD refresh rate, with [com.onyx.android.sdk.api.device.epd.EpdController]
 * configuring the full-view ink region to avoid hardware dead zones.
 *
 * [attach] is the detection gate: on non-Onyx devices the SDK classes load
 * but the vendor runtime is missing, so the call throws. We catch and return
 * false, leaving [isActive] clear so the host falls back to the Canvas path.
 *
 * ## Metrics coverage
 *
 * Onyx's `TouchHelper` paints strokes inside the vendor SDK's native code —
 * we never see the "draw issued" or "invalidate returned" moments from
 * the JVM. So the four `paint.*` and `pen.*_to_paint` metrics that hinge on
 * a JVM-side paint boundary cannot be filled in here. The five we *can*
 * record are:
 *
 *  - [PerfMetric.PEN_KERNEL_TO_JVM]    — DOWN-only kernel→JVM dispatch
 *  - [PerfMetric.PEN_JVM_TO_FIRST_MOVE]— DOWN entry → first MOVE entry
 *  - [PerfMetric.EVENT_KERNEL_TO_JVM]  — per-event dispatch (every callback)
 *  - [PerfMetric.EVENT_HANDLER]        — wall time of each callback body
 *  - SLOW_STROKE log line, derived from the DOWN-side dispatch metric
 *
 * `TouchPoint.timestamp` is `SystemClock.uptimeMillis()` set when the SDK
 * dispatches the event; pairing it with [SystemClock.uptimeMillis] at
 * callback entry gives the same kernel→JVM-style delta the Bigme controller
 * computes from the daemon's CLOCK_REALTIME timestamp.
 */
class OnyxInkController : InkController {

    override var isActive: Boolean = false
        private set

    override val consumesMotionEvents: Boolean get() = isActive

    private var touchHelper: TouchHelper? = null
    private var pendingWidth: Float = InkDefaults.DEFAULT_STROKE_WIDTH_PX
    private var pendingColor: Int = InkDefaults.DEFAULT_STROKE_COLOR

    // Per-session diagnostic counter — incremented on each stroke begin so
    // resetDiagnostics() can clear it on demand (e.g. Clear button) and let
    // the FIRST_STROKE log fire again without detach/attach.
    private val strokeIndex = AtomicInteger(0)

    override fun resetDiagnostics() {
        strokeIndex.set(0)
    }

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        if (isActive) return true
        return try {
            touchHelper = TouchHelper.create(view, makeRawInputCallback(callback)).apply {
                setStrokeWidth(pendingWidth)
                setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                setStrokeColor(pendingColor)
                setLimitRect(limit, emptyList())
                openRawDrawing()
                setRawDrawingEnabled(true)
            }
            try {
                com.onyx.android.sdk.api.device.epd.EpdController
                    .setScreenHandWritingRegionLimit(view)
            } catch (e: Exception) {
                Log.w(TAG, "EpdController.setScreenHandWritingRegionLimit failed: ${e.message}")
            }
            isActive = true
            Log.i(TAG, "Onyx ink controller attached: limitRect=$limit")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Onyx ink attach failed, falling back: ${e.message}")
            touchHelper = null
            isActive = false
            false
        }
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) {
        pendingWidth = widthPx
        pendingColor = color
        if (!isActive) return
        try {
            touchHelper?.setStrokeWidth(widthPx)
            touchHelper?.setStrokeColor(color)
        } catch (e: Exception) {
            Log.w(TAG, "setStrokeStyle failed: ${e.message}")
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (!isActive) return
        try { touchHelper?.setRawDrawingEnabled(enabled) }
        catch (e: Exception) { Log.w(TAG, "setEnabled($enabled) failed: ${e.message}") }
    }

    override fun syncOverlay(bitmap: android.graphics.Bitmap, region: Rect?, force: Boolean) {
        // Onyx owns its raw-drawing buffer via TouchHelper — no need to blit
        // the host bitmap. When [force] is set, cycle the raw-drawing layer
        // so the EPD picks up the freshly-composed SurfaceView and the
        // overlay's cached ink (pre-mutation) is flushed.
        if (!force || !isActive) return
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
        } catch (e: Exception) { Log.w(TAG, "syncOverlay failed: ${e.message}") }
    }

    override fun detach() {
        if (!isActive) return
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawInputReaderEnable(false)
            touchHelper?.closeRawDrawing()
        } catch (e: Exception) { Log.w(TAG, "Onyx ink detach error: ${e.message}") }
        touchHelper = null
        isActive = false
        Log.i(TAG, "Onyx ink controller detached")
    }

    private fun makeRawInputCallback(sink: StrokeCallback) = object : RawInputCallback() {

        // DOWN-side state captured for first-MOVE delta. nanoTime() is
        // monotonic; uptimeMillis() pairs with TouchPoint.timestamp for the
        // kernel→JVM cross-clock measurements.
        private var downJvmNs = 0L
        private var firstMoveOfStroke = false

        // Cross-clock dispatch latency: TouchPoint.timestamp is set by the
        // SDK when the input event is delivered, in the SystemClock.uptimeMillis
        // epoch. Subtract from now to get kernel/SDK→JVM hop. Recorded for
        // every event for parity with Bigme's event.kernel_to_jvm.
        private fun recordDispatch(tp: TouchPoint, metric: PerfMetric) {
            val tsMs = tp.timestamp
            if (tsMs <= 0L) return
            val deltaMs = SystemClock.uptimeMillis() - tsMs
            if (deltaMs < 0L) return
            PerfCounters.recordDirect(metric, deltaMs * 1_000_000L)
        }

        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            val handlerStart = System.nanoTime()
            recordDispatch(tp, PerfMetric.EVENT_KERNEL_TO_JVM)
            recordDispatch(tp, PerfMetric.PEN_KERNEL_TO_JVM)
            downJvmNs = handlerStart
            firstMoveOfStroke = true
            val sIdx = strokeIndex.incrementAndGet()
            // Mirror Bigme's SLOW_STROKE log so per-pause touch-IC wakes
            // (or any sustained dispatch backlog) surface in logcat with
            // wall-clock context — Onyx can't measure post-event paint, so
            // we use the pen.kernel_to_jvm side as the headline proxy.
            val k2jMs = if (tp.timestamp > 0L) {
                (SystemClock.uptimeMillis() - tp.timestamp).coerceAtLeast(0L)
            } else -1L
            if (sIdx <= 10) {
                Log.i(TAG, "FIRST_STROKE #$sIdx: kernel_to_jvm=${k2jMs}ms")
            }
            if (k2jMs >= SLOW_STROKE_MS) {
                Log.i(TAG, "SLOW_STROKE #$sIdx @${wallClockHms()}: kernel_to_jvm=${k2jMs}ms")
            }
            try { sink.onStrokeBegin(tp.x, tp.y, tp.pressure, tp.timestamp) }
            finally {
                PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - handlerStart)
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            val handlerStart = System.nanoTime()
            recordDispatch(tp, PerfMetric.EVENT_KERNEL_TO_JVM)
            if (firstMoveOfStroke) {
                firstMoveOfStroke = false
                if (downJvmNs != 0L) {
                    PerfCounters.recordDirect(
                        PerfMetric.PEN_JVM_TO_FIRST_MOVE,
                        handlerStart - downJvmNs,
                    )
                }
            }
            try { sink.onStrokeMove(tp.x, tp.y, tp.pressure, tp.timestamp) }
            finally {
                PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - handlerStart)
            }
        }

        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {}

        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            val handlerStart = System.nanoTime()
            recordDispatch(tp, PerfMetric.EVENT_KERNEL_TO_JVM)
            try { sink.onStrokeEnd(tp.x, tp.y, tp.pressure, tp.timestamp) }
            finally {
                PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - handlerStart)
                downJvmNs = 0L
            }
        }

        override fun onBeginRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(tpl: TouchPointList) {}
    }

    companion object {
        private const val TAG = "OnyxInkController"

        // Threshold (ms) above which a stroke's kernel→JVM dispatch is
        // logged as a SLOW_STROKE. Tuned to match Bigme's threshold so
        // logs are comparable across devices.
        private const val SLOW_STROKE_MS = 30L

        private val wallClockFormatter = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        private fun wallClockHms(): String = wallClockFormatter.format(System.currentTimeMillis())
    }
}
