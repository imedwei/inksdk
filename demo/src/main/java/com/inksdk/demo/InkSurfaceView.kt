package com.inksdk.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.inksdk.ink.InkController
import com.inksdk.ink.InkControllerFactory
import com.inksdk.ink.InkDefaults
import com.inksdk.ink.StrokeCallback

/**
 * Slim ink surface — minimum machinery to exercise the [InkController]:
 *
 * - Daemon path (`consumesMotionEvents = true`): the controller paints into
 *   the ION overlay directly. We additionally mirror each completed stroke
 *   into [contentBitmap] so the SurfaceView underlay carries the ink across
 *   system-driven UI composes (status TextView updates, etc.) that would
 *   otherwise blit a stale white SurfaceView buffer over the EPD region.
 *   The mirror first tries [InkController.mirrorOverlay] (pixel-perfect
 *   copy) and falls back to coordinate replay using daemon-delivered
 *   view-local coords (see Mokke commit d439fdd: `convertXY` runs inside
 *   the daemon's dispatcher, so coords arrive view-local).
 *
 * - Fallback (no controller / `consumesMotionEvents = false`): standard
 *   MotionEvent + Canvas. Paints incrementally into [contentBitmap] and
 *   posts to the SurfaceHolder.
 *
 * Kept small so any latency we measure is attributable to the controller,
 * not the host.
 */
class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val ink: InkController = InkControllerFactory.create()

    private var contentBitmap: Bitmap? = null
    private var surfaceReady = false

    /** When false, ALL bitmap-mirror operations are skipped:
     *  - no contentBitmap allocation
     *  - no commitToSurface
     *  - no syncOverlay at attach
     *  - no stroke-end mirror
     *  Used to A/B test whether the mirror itself is causing latency or
     *  visible artifacts. Must be set BEFORE surfaceCreated fires
     *  (typically right after the view is inflated, in onCreate). */
    var mirrorEnabled: Boolean = true

    private val strokePaint = Paint().apply {
        color = InkDefaults.DEFAULT_STROKE_COLOR
        strokeWidth = InkDefaults.DEFAULT_STROKE_WIDTH_PX
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }
    // Daemon-path stroke buffer — main-thread access.
    private val strokeBuffer = mutableListOf<PointF>()

    // Fallback (MotionEvent) path state.
    private var lastX = 0f
    private var lastY = 0f
    private var penDown = false

    init { holder.addCallback(this) }

    private val strokeCallback = object : StrokeCallback {
        override fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            strokeBuffer.clear()
            strokeBuffer.add(PointF(x, y))
        }
        override fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            strokeBuffer.add(PointF(x, y))
        }
        override fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            strokeBuffer.add(PointF(x, y))
            if (!mirrorEnabled || ink.ownsSurface) {
                // ownsSurface (Onyx): TouchHelper holds the SurfaceView's
                // surface lock for the duration of raw drawing. Calling
                // commitToSurface() → holder.lockCanvas() here blocks the
                // vendor input thread indefinitely, which back-pressures
                // SurfaceFlinger and freezes the main-thread timer. Bitmap
                // mirroring is also pointless on Onyx — the panel content
                // we'd be re-blitting is exactly what TouchHelper already
                // shows.
                strokeBuffer.clear()
                return
            }
            val bmp = contentBitmap
            if (bmp != null) {
                if (!ink.mirrorOverlay(bmp)) replayStrokeToBitmap(bmp)
                commitToSurface()
            }
            strokeBuffer.clear()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        Log.i(TAG, "surfaceCreated — mirrorEnabled=$mirrorEnabled")
        if (mirrorEnabled) {
            rebuildBitmap()
            commitToSurface()
        }
        if (width > 0 && height > 0) {
            val limit = Rect(0, 0, width, height)
            if (ink.attach(this, limit, strokeCallback)) {
                Log.i(TAG, "${ink.javaClass.simpleName} attached")
                if (mirrorEnabled) ink.syncOverlay(contentBitmap!!, force = false)
            } else {
                Log.i(TAG, "Falling back to MotionEvent path")
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!mirrorEnabled) return
        // Onyx TouchHelper owns the surface while raw drawing is active —
        // host commits would block. Bitmap stays in sync for future use.
        if (ink.ownsSurface) {
            val bmp = contentBitmap
            if (bmp == null || bmp.width != w || bmp.height != h) rebuildBitmap()
            return
        }
        val bmp = contentBitmap
        if (bmp != null && bmp.width == w && bmp.height == h) {
            commitToSurface()
            return
        }
        rebuildBitmap()
        commitToSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        ink.detach()
        contentBitmap?.recycle()
        contentBitmap = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ink.consumesMotionEvents) return false
        val bmp = contentBitmap ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                penDown = true
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!penDown) return false
                val c = Canvas(bmp)
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    c.drawLine(lastX, lastY, hx, hy, strokePaint)
                    lastX = hx; lastY = hy
                }
                c.drawLine(lastX, lastY, event.x, event.y, strokePaint)
                lastX = event.x; lastY = event.y
                commitToSurface()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { penDown = false }
        }
        return true
    }

    fun clear() {
        strokeBuffer.clear()
        val bmp = contentBitmap
        if (bmp != null) {
            Canvas(bmp).drawColor(Color.WHITE)
            // Skip the host-side surface commit when the controller owns the
            // surface (Onyx) — would block on TouchHelper's surface lock.
            // syncOverlay(force=true) cycles raw drawing and lets the EPD
            // pick up the freshly-cleared content.
            if (!ink.ownsSurface) commitToSurface()
            ink.syncOverlay(bmp, force = true)
        } else {
            // No mirror, no host bitmap. Force a full-screen GU16 refresh
            // by syncing a transient white scratch bitmap once.
            val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(scratch).drawColor(Color.WHITE)
            ink.syncOverlay(scratch, force = true)
            scratch.recycle()
        }
        // Reset session diagnostics so first-N-stroke logs fire again on
        // the next stroke. Lets the user iterate without restarting the app.
        ink.resetDiagnostics()
    }

    /** Paint the buffered stroke into [bmp] at view-local coords. The
     *  daemon's `convertXY` already translates panel coords to view-local
     *  before delivery (per the d439fdd fix in the Mokke port), so no
     *  offset subtraction is needed. */
    private fun replayStrokeToBitmap(bmp: Bitmap) {
        if (strokeBuffer.size < 2) return
        val c = Canvas(bmp)
        for (i in 1 until strokeBuffer.size) {
            val a = strokeBuffer[i - 1]
            val b = strokeBuffer[i]
            c.drawLine(a.x, a.y, b.x, b.y, strokePaint)
        }
    }

    private fun rebuildBitmap() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val existing = contentBitmap
        val bmp = if (existing != null && existing.width == w && existing.height == h) existing
        else {
            existing?.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { contentBitmap = it }
        }
        // Only clear to white when we just allocated a fresh bitmap. Reusing
        // an existing bitmap means the strokes already painted into it must
        // be preserved.
        if (bmp !== existing) Canvas(bmp).drawColor(Color.WHITE)
    }

    private fun commitToSurface() {
        if (!surfaceReady) return
        val bmp = contentBitmap ?: return
        val canvas = holder.lockCanvas() ?: return
        try { canvas.drawBitmap(bmp, 0f, 0f, null) }
        finally { holder.unlockCanvasAndPost(canvas) }
    }

    fun isOverlayActive(): Boolean = ink.isActive

    /** Pause/resume the underlying [InkController] without detaching.
     *  On Onyx this releases TouchHelper's grip on the EPD waveform engine
     *  so view-tree composes (status text, perf panel) drain to the
     *  panel; on Bigme it just stops the daemon dispatching input events.
     *  Distinct name from [View.setEnabled] to avoid disabling the view. */
    fun setOverlayEnabled(enabled: Boolean) = ink.setEnabled(enabled)

    /** Inject a synthetic stroke for tests. Drives the public StrokeCallback. */
    fun injectStrokeForTest(points: List<Triple<Float, Float, Long>>) {
        if (points.size < 2) return
        val ts = points.first().third
        strokeCallback.onStrokeBegin(points.first().first, points.first().second, 0.5f, ts)
        for (i in 1 until points.size - 1) {
            val (x, y, t) = points[i]
            strokeCallback.onStrokeMove(x, y, 0.5f, t)
        }
        val last = points.last()
        strokeCallback.onStrokeEnd(last.first, last.second, 0.5f, last.third)
    }

    companion object { private const val TAG = "InkSurfaceView" }
}
