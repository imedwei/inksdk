package com.inksdk.ink

import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Onyx Boox raw-drawing controller. Uses [TouchHelper] to paint strokes
 * directly at EPD refresh rate, with [com.onyx.android.sdk.api.device.epd.EpdController]
 * configuring the full-view ink region to avoid hardware dead zones.
 *
 * [attach] is the detection gate: on non-Onyx devices the SDK classes load
 * but the vendor runtime is missing, so the call throws. We catch and return
 * false, leaving [isActive] clear so the host falls back to the Canvas path.
 */
class OnyxInkController : InkController {

    override var isActive: Boolean = false
        private set

    override val consumesMotionEvents: Boolean get() = isActive

    private var touchHelper: TouchHelper? = null
    private var pendingWidth: Float = InkDefaults.DEFAULT_STROKE_WIDTH_PX
    private var pendingColor: Int = InkDefaults.DEFAULT_STROKE_COLOR

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
        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            sink.onStrokeBegin(tp.x, tp.y, tp.pressure, tp.timestamp)
        }
        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            sink.onStrokeMove(tp.x, tp.y, tp.pressure, tp.timestamp)
        }
        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {}
        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            sink.onStrokeEnd(tp.x, tp.y, tp.pressure, tp.timestamp)
        }
        override fun onBeginRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(tpl: TouchPointList) {}
    }

    companion object { private const val TAG = "OnyxInkController" }
}
