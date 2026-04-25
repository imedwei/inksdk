package com.inksdk.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
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
 * - Daemon path (consumesMotionEvents = true): the controller paints into the
 *   ION buffer directly. This view only mirrors completed strokes into a
 *   [contentBitmap] so a re-attach (orientation, surface recreation) doesn't
 *   lose them. No per-stroke processing, no caches, no Choreographer dance.
 * - Fallback (no controller / consumesMotionEvents = false): standard
 *   MotionEvent + Canvas. Paints incrementally into [contentBitmap] and
 *   posts to the SurfaceHolder.
 *
 * The whole purpose is to be small enough that any latency we measure is
 * attributable to the controller, not the host.
 */
class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val ink: InkController = InkControllerFactory.create()

    private var contentBitmap: Bitmap? = null
    private var surfaceReady = false

    private val strokePaint = Paint().apply {
        color = InkDefaults.DEFAULT_STROKE_COLOR
        strokeWidth = InkDefaults.DEFAULT_STROKE_WIDTH_PX
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }
    private val livePath = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var penDown = false

    init { holder.addCallback(this) }

    private val strokeCallback = object : StrokeCallback {
        override fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            Log.d(TAG, "begin ${ink.javaClass.simpleName}: ($x,$y)")
        }
        override fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
        override fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            // Daemon already painted into ION; no host-side bitmap update is
            // strictly required for visible ink, but mirroring lets the
            // surface survive recreation (orientation, etc).
            Log.d(TAG, "end (${ink.javaClass.simpleName})")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        rebuildBitmap()
        commitToSurface()
        if (width > 0 && height > 0) {
            val limit = Rect(0, 0, width, height)
            if (ink.attach(this, limit, strokeCallback)) {
                Log.i(TAG, "${ink.javaClass.simpleName} attached")
                ink.syncOverlay(contentBitmap!!, force = false)
            } else {
                Log.i(TAG, "Falling back to MotionEvent path")
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
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
        // If the controller consumes motion events, do not double-process.
        if (ink.consumesMotionEvents) return false
        val bmp = contentBitmap ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                penDown = true
                livePath.reset()
                livePath.moveTo(event.x, event.y)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                penDown = false
            }
        }
        return true
    }

    fun clear() {
        val bmp = contentBitmap ?: return
        Canvas(bmp).drawColor(Color.WHITE)
        commitToSurface()
        ink.syncOverlay(bmp, force = true)
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
        Canvas(bmp).drawColor(Color.WHITE)
    }

    private fun commitToSurface() {
        if (!surfaceReady) return
        val bmp = contentBitmap ?: return
        val canvas = holder.lockCanvas() ?: return
        try { canvas.drawBitmap(bmp, 0f, 0f, null) }
        finally { holder.unlockCanvasAndPost(canvas) }
    }

    /** True if any [InkController] is currently driving the ink overlay. */
    fun isOverlayActive(): Boolean = ink.isActive

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
