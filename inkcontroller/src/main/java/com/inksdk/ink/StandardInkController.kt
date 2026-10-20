// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Kalamos InkSDK by josephm1; builds on InkSDK by Ed Wei and contributors.
package com.inksdk.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import kotlin.math.hypot

/**
 * Public-API Android pen capture and SurfaceView rendering (no vendor latency guarantee).
 *
 * The controller consumes only stylus gestures that begin inside the active writing area. Finger
 * and eraser gestures are not consumed; the host owns their dispatch policy. Historical points are
 * delivered before the current sample so fast pen motion does not lose Android's coalesced data.
 */
class StandardInkController : InkEditingController {
    override val requiresMotionForwarding: Boolean = true
    override fun onMotionEvent(event: MotionEvent): Boolean = handleTouch(event)
    @Volatile
    override var isActive: Boolean = false
        private set

    override val consumesMotionEvents: Boolean
        get() = isActive

    private var view: SurfaceView? = null
    private var callback: StrokeCallback? = null
    private val attachLimit = Rect()
    private val exclusion = Rect()
    private val writingBounds = Rect()
    private var writingTop = 0
    private var enabled = true
    private var eraserMode = false
    private var pressureMode = false
    private var captureOnly = false
    private var gestureCaptureOnly = false
    private var captureOnlyAt: ((Float, Float) -> Boolean)? = null
    private var excludedPenTouch: ((Int, Float, Float) -> Unit)? = null
    private var excludedPointerId = MotionEvent.INVALID_POINTER_ID
    private var dashMode = false
    private var strokeWidth = InkDefaults.DEFAULT_STROKE_WIDTH_PX
    private var strokeColor = InkDefaults.DEFAULT_STROKE_COLOR

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var strokeStarted = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0.5f
    private var lastTimestamp = 0L
    private var dashPhase = 0f
    private var pointCount = 0
    private var historicalPointCount = 0
    private var minPressure = 1f
    private var maxPressure = 0f
    private var moveEventCount = 0
    private var livePresentCount = 0

    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }
    private val dirty = Rect()
    private val motionDirty = Rect()

    private val touchListener = View.OnTouchListener { _, event -> handleTouch(event) }

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        detach()
        if (view.width <= 0 || view.height <= 0 || view.holder.surface?.isValid != true) {
            Log.w(TAG, "attach rejected: invalid surface ${view.width}x${view.height}")
            return false
        }
        this.view = view
        this.callback = callback
        attachLimit.set(limit)
        enabled = true
        ensureBacking(view.width, view.height)
        view.setOnTouchListener(touchListener)
        isActive = true
        Log.i(TAG, "attached standard stylus surface=${view.width}x${view.height} limit=$attachLimit")
        return true
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) {
        strokeWidth = widthPx.coerceAtLeast(0.5f)
        strokeColor = color
    }

    override fun setWritingTop(topPx: Int) {
        writingTop = topPx.coerceAtLeast(0)
    }

    override fun setWritingExclusion(left: Int, top: Int, right: Int, bottom: Int) {
        exclusion.set(left, top, right, bottom)
    }

    override fun setWritingBounds(left: Int, top: Int, right: Int, bottom: Int) {
        writingBounds.set(left, top, right, bottom)
    }

    override fun setEraserMode(active: Boolean) {
        eraserMode = active
    }

    override fun setPressureMode(active: Boolean) {
        pressureMode = active
    }

    override fun setDashMode(active: Boolean) {
        dashMode = active
    }

    override fun setCaptureOnly(active: Boolean) { captureOnly = active }
    override fun setCaptureOnlyAt(decision: ((Float, Float) -> Boolean)?) { captureOnlyAt = decision }
    override fun setExcludedPenTouch(listener: ((Int, Float, Float) -> Unit)?) { excludedPenTouch = listener }

    override fun syncChrome(bitmap: Bitmap, region: Rect) {
        val surface = view ?: return
        ensureBacking(surface.width, surface.height)
        val canvas = backingCanvas ?: return
        val clipped = Rect(region)
        if (!clipped.intersect(0, 0, surface.width, surface.height)) return
        canvas.save()
        canvas.clipRect(clipped)
        canvas.drawBitmap(bitmap, region.left.toFloat(), region.top.toFloat(), bitmapPaint)
        canvas.restore()
        present(surface, clipped)
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) finishAtLastPoint()
        this.enabled = enabled
    }

    override fun syncOverlay(bitmap: Bitmap, region: Rect?, force: Boolean) {
        val surface = view ?: return
        ensureBacking(bitmap.width, bitmap.height)
        val target = backingCanvas ?: return
        val copyRegion = clampedRegion(region, bitmap.width, bitmap.height)
        if (copyRegion.isEmpty) return
        target.drawBitmap(bitmap, copyRegion, copyRegion, bitmapPaint)
        present(surface, copyRegion)
    }

    override fun refreshRegionFast(bitmap: Bitmap, region: Rect) {
        syncOverlay(bitmap, region, false)
    }

    override fun mirrorOverlay(target: Bitmap): Boolean {
        val source = backingBitmap ?: return false
        if (target.width != source.width || target.height != source.height) return false
        Canvas(target).drawBitmap(source, 0f, 0f, bitmapPaint)
        return true
    }

    override fun detach() {
        finishAtLastPoint()
        view?.setOnTouchListener(null)
        view = null
        callback = null
        isActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        excludedPointerId = MotionEvent.INVALID_POINTER_ID
        strokeStarted = false
        backingBitmap = null
        backingCanvas = null
        Log.i(TAG, "detached standard stylus surface")
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val startedNs = System.nanoTime()
        try {
            if (!isActive || !enabled) return false
            if (excludedPointerId != MotionEvent.INVALID_POINTER_ID) {
                val index = event.findPointerIndex(excludedPointerId)
                if (index >= 0) excludedPenTouch?.invoke(event.actionMasked, event.getX(index), event.getY(index))
                if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL) ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == excludedPointerId) {
                    excludedPointerId = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginGesture(event)
                MotionEvent.ACTION_MOVE -> moveGesture(event)
                MotionEvent.ACTION_UP -> endGesture(event, event.actionIndex)
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        endGesture(event, event.actionIndex)
                    } else {
                        activePointerId != MotionEvent.INVALID_POINTER_ID
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    val consumed = activePointerId != MotionEvent.INVALID_POINTER_ID
                    finishAtLastPoint()
                    resetGesture()
                    consumed
                }
                else -> activePointerId != MotionEvent.INVALID_POINTER_ID
            }
        } finally {
            PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - startedNs)
        }
    }

    private fun beginGesture(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val tool = event.getToolType(index)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS ||
            event.isFromSource(InputDevice.SOURCE_STYLUS)
        if (!isStylus || tool == MotionEvent.TOOL_TYPE_ERASER || eraserMode) return false

        val x = event.getX(index)
        val y = event.getY(index)
        if (!accepts(x, y)) {
            if (excludedPenTouch == null) return false
            excludedPointerId = event.getPointerId(index)
            excludedPenTouch?.invoke(MotionEvent.ACTION_DOWN, x, y)
            return true
        }
        gestureCaptureOnly = captureOnly || captureOnlyAt?.invoke(x, y) == true

        activePointerId = event.getPointerId(index)
        pointCount = 0
        historicalPointCount = 0
        minPressure = 1f
        maxPressure = 0f
        moveEventCount = 0
        livePresentCount = 0
        startStroke(x, y, pressure(event.getPressure(index)), event.eventTime)
        return true
    }

    private fun moveGesture(event: MotionEvent): Boolean {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) {
            finishAtLastPoint()
            resetGesture()
            return true
        }
        motionDirty.setEmpty()
        for (historyIndex in 0 until event.historySize) {
            sample(
                event.getHistoricalX(index, historyIndex),
                event.getHistoricalY(index, historyIndex),
                pressure(event.getHistoricalPressure(index, historyIndex)),
                event.getHistoricalEventTime(historyIndex),
            )
            historicalPointCount++
        }
        sample(event.getX(index), event.getY(index), pressure(event.getPressure(index)), event.eventTime)
        moveEventCount++
        presentMotionBatch()
        return true
    }

    private fun endGesture(event: MotionEvent, index: Int): Boolean {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID ||
            event.getPointerId(index) != activePointerId
        ) {
            return false
        }
        motionDirty.setEmpty()
        for (historyIndex in 0 until event.historySize) {
            sample(
                event.getHistoricalX(index, historyIndex),
                event.getHistoricalY(index, historyIndex),
                pressure(event.getHistoricalPressure(index, historyIndex)),
                event.getHistoricalEventTime(historyIndex),
            )
            historicalPointCount++
        }
        val x = event.getX(index)
        val y = event.getY(index)
        val p = pressure(event.getPressure(index))
        if (accepts(x, y)) {
            if (!strokeStarted) {
                startStroke(x, y, p, event.eventTime)
            } else {
                drawSegment(lastX, lastY, x, y, lastPressure, p)
                callback?.onStrokeEnd(x, y, p, event.eventTime)
                remember(x, y, p, event.eventTime)
                pointCount++
                logCompletedStroke()
                strokeStarted = false
            }
        } else {
            finishAtLastPoint()
        }
        presentMotionBatch()
        resetGesture()
        return true
    }

    private fun sample(x: Float, y: Float, pressure: Float, timestamp: Long) {
        if (!accepts(x, y)) {
            finishAtLastPoint()
            return
        }
        if (!strokeStarted) {
            startStroke(x, y, pressure, timestamp)
            return
        }
        drawSegment(lastX, lastY, x, y, lastPressure, pressure)
        callback?.onStrokeMove(x, y, pressure, timestamp)
        remember(x, y, pressure, timestamp)
        pointCount++
    }

    private fun startStroke(x: Float, y: Float, pressure: Float, timestamp: Long) {
        dashPhase = 0f
        drawDot(x, y, pressure)
        callback?.onStrokeBegin(x, y, pressure, timestamp)
        remember(x, y, pressure, timestamp)
        pointCount++
        strokeStarted = true
    }

    private fun finishAtLastPoint() {
        if (!strokeStarted) return
        callback?.onStrokeEnd(lastX, lastY, lastPressure, lastTimestamp)
        pointCount++
        logCompletedStroke()
        strokeStarted = false
    }

    private fun resetGesture() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        strokeStarted = false
    }

    private fun remember(x: Float, y: Float, pressure: Float, timestamp: Long) {
        lastX = x
        lastY = y
        lastPressure = pressure
        lastTimestamp = timestamp
        minPressure = minOf(minPressure, pressure)
        maxPressure = maxOf(maxPressure, pressure)
    }

    private fun accepts(x: Float, y: Float): Boolean {
        if (!attachLimit.contains(x.toInt(), y.toInt())) return false
        if (writingTop > 0 && y < writingTop) return false
        if (!exclusion.isEmpty && exclusion.contains(x.toInt(), y.toInt())) return false
        if (!writingBounds.isEmpty && !writingBounds.contains(x.toInt(), y.toInt())) return false
        return true
    }

    private fun drawDot(x: Float, y: Float, pressure: Float) {
        if (gestureCaptureOnly) return
        val canvas = backingCanvas ?: return
        configurePaint(pressure)
        val radius = paint.strokeWidth / 2f
        val dotPaint = Paint(paint).apply { style = Paint.Style.FILL }
        canvas.drawCircle(x, y, radius, dotPaint)
        val pad = paint.strokeWidth.toInt() + 3
        dirty.set(
            (x.toInt() - pad).coerceAtLeast(0),
            (y.toInt() - pad).coerceAtLeast(0),
            (x.toInt() + pad).coerceAtMost(backingBitmap?.width ?: 0),
            (y.toInt() + pad).coerceAtMost(backingBitmap?.height ?: 0),
        )
        view?.let {
            present(it, dirty)
            livePresentCount++
        }
    }

    private fun drawSegment(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        fromPressure: Float,
        toPressure: Float,
    ) {
        if (gestureCaptureOnly) return
        val startedNs = System.nanoTime()
        val canvas = backingCanvas ?: return
        configurePaint((fromPressure + toPressure) / 2f)
        if (dashMode) {
            paint.pathEffect = DashPathEffect(InkDefaults.dashIntervals(), dashPhase)
            dashPhase += hypot((toX - fromX).toDouble(), (toY - fromY).toDouble()).toFloat()
        } else {
            paint.pathEffect = null
        }
        canvas.drawLine(fromX, fromY, toX, toY, paint)
        PerfCounters.recordDirect(PerfMetric.PAINT_DRAW_SEGMENT, System.nanoTime() - startedNs)

        val pad = paint.strokeWidth.toInt() + 3
        val width = backingBitmap?.width ?: return
        val height = backingBitmap?.height ?: return
        dirty.set(
            (minOf(fromX, toX).toInt() - pad).coerceAtLeast(0),
            (minOf(fromY, toY).toInt() - pad).coerceAtLeast(0),
            (maxOf(fromX, toX).toInt() + pad).coerceAtMost(width),
            (maxOf(fromY, toY).toInt() + pad).coerceAtMost(height),
        )
        if (motionDirty.isEmpty) motionDirty.set(dirty) else motionDirty.union(dirty)
    }

    /**
     * Android may deliver many historical samples in one MOVE. Drawing all of them into the backing
     * bitmap is cheap; posting one Surface transaction per sample is not. Present their union once
     * per MotionEvent so the surface tracks input cadence instead of replaying a long commit queue.
     */
    private fun presentMotionBatch() {
        if (motionDirty.isEmpty) return
        view?.let {
            present(it, motionDirty)
            livePresentCount++
        }
        motionDirty.setEmpty()
    }

    private fun configurePaint(pressure: Float) {
        paint.color = strokeColor
        paint.strokeWidth = if (pressureMode) {
            InkDefaults.pressureToWidth(strokeWidth, pressure)
        } else {
            strokeWidth
        }
    }

    private fun present(surfaceView: SurfaceView, region: Rect) {
        val bitmap = backingBitmap ?: return
        if (region.isEmpty || surfaceView.holder.surface?.isValid != true) return
        val startedNs = System.nanoTime()
        var canvas: Canvas? = null
        try {
            canvas = surfaceView.holder.lockCanvas(region)
            canvas?.drawBitmap(bitmap, region, region, bitmapPaint)
        } catch (t: Throwable) {
            Log.w(TAG, "surface present failed: ${t.message}")
        } finally {
            if (canvas != null) {
                surfaceView.holder.unlockCanvasAndPost(canvas)
                PerfCounters.recordDirect(
                    PerfMetric.PAINT_INVALIDATE_CALL,
                    System.nanoTime() - startedNs,
                )
            }
        }
    }

    private fun ensureBacking(width: Int, height: Int) {
        val current = backingBitmap
        if (current != null && current.width == width && current.height == height) return
        val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fresh)
        canvas.drawColor(Color.WHITE)
        current?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        backingBitmap = fresh
        backingCanvas = canvas
    }

    private fun clampedRegion(region: Rect?, width: Int, height: Int): Rect {
        val result = region?.let(::Rect) ?: Rect(0, 0, width, height)
        if (!result.intersect(0, 0, width, height)) result.setEmpty()
        return result
    }

    private fun pressure(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f

    private fun logCompletedStroke() {
        Log.i(
            TAG,
            "DIAG standardInkStroke points=$pointCount historical=$historicalPointCount " +
                "moveEvents=$moveEventCount livePresents=$livePresentCount " +
                "pressure=${"%.3f".format(java.util.Locale.US, minPressure)}.." +
                "${"%.3f".format(java.util.Locale.US, maxPressure)} t=${SystemClock.uptimeMillis()}",
        )
    }

    private companion object {
        const val TAG = "StandardInkController"
    }
}
