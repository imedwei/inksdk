package com.inksdk.ink

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Low-latency ink for Bigme e-ink devices via the undocumented `com.xrz.HandwrittenClient`
 * API (in `framework.jar`'s classes5.dex; BOOTCLASSPATH-reachable). The client
 * connects to the native `/system/bin/handwrittenservice` daemon over binder,
 * binds a host view, and exposes an ION-backed Canvas the app draws to — the
 * daemon then refreshes the EPD for each `inValidate()` region.
 *
 * Verified on Bigme HiBreak Plus (Android 14, daemon v1.4.0).
 *
 * ## API surface (all reflective; classes exist only on xrz firmware)
 * ```
 * HandwrittenClient(Context)
 *   int bindView(View)
 *   boolean connect(int width, int height)
 *   void registerInputListener(InputListener)
 *   void setInputEnabled(boolean)
 *   void setOverlayEnabled(boolean)
 *   void setBlendEnabled(boolean)
 *   void setUseRawInputEvent(boolean)
 *   void inValidate(Rect, int mode)
 *   Canvas getCanvas()
 *   Bitmap getContent()
 *   Rect getViewLayout() / getPhyViewLayout()
 *   int getPhyRotation() / getCurViewRotation()
 *   boolean updateLayout()
 *   boolean updateRotation()
 *   void unBindView()
 *   void disconnect()
 *
 * HandwrittenClient.InputListener
 *   int onInputTouch(action, x, y, pressure, tool)
 *   int onInputTouch(action, x, y, pressure, tool, time)
 *
 * Constants: ACTION_NEAR=0 DOWN=1 MOVE=2 UP=3 LEAVE=4
 *            TOOL_PEN=0 RUBBER=1 FINGER=2
 *            FORMAT_GRAY8=0 RGBA8888=1
 *            MODE_HANDWRITE=1029 MODE_RUBBER=1030 MODE_GU16=132 MODE_GC16=4
 * ```
 */
class BigmeInkController : InkController {

    override var isActive: Boolean = false
        private set

    /** Daemon consumes input events once connected (similar to Onyx TouchHelper). */
    override val consumesMotionEvents: Boolean get() = isActive

    private var client: Any? = null
    private var clientClass: Class<*>? = null
    private var attachedView: SurfaceView? = null
    // Re-entrance guard — bindView() synchronously fires surfaceCreated, which
    // can re-enter attach(). Without this guard we'd build a second client.
    private var attaching: Boolean = false

    private var pendingWidth: Float = InkDefaults.DEFAULT_STROKE_WIDTH_PX

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        if (isActive) return true
        if (attaching) return false
        if (!isBigmeDevice()) return false
        attaching = true
        return try {
            val cls = Class.forName(HANDWRITTEN_CLIENT)
            val c = cls.getConstructor(android.content.Context::class.java).newInstance(view.context)

            cls.getMethod("bindView", android.view.View::class.java).invoke(c, view)

            val listenerCls = Class.forName(INPUT_LISTENER)
            val listener = Proxy.newProxyInstance(
                cls.classLoader,
                arrayOf(listenerCls),
                InputProxy(callback, view, getClient = { client }, getClientClass = { clientClass }, getStrokeWidth = { pendingWidth }),
            )
            cls.getMethod("registerInputListener", listenerCls).invoke(c, listener)

            // connect(width, height): daemon's two ints are buffer dims, not
            // FORMAT_*/MODE_* despite the constant naming.
            val w = if (view.width > 0) view.width else limit.width()
            val h = if (view.height > 0) view.height else limit.height()
            val connected = cls.getMethod(
                "connect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(c, w, h) as Boolean
            if (!connected) {
                Log.w(TAG, "HandwrittenClient.connect returned false")
                cleanupClient(cls, c)
                return false
            }

            runCatching { cls.getMethod("updateLayout").invoke(c) }
            runCatching { cls.getMethod("updateRotation").invoke(c) }

            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            cls.getMethod("setOverlayEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            runCatching {
                cls.getMethod("setBlendEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            }
            val phyRot = runCatching { cls.getMethod("getPhyRotation").invoke(c) }.getOrNull()
            val viewLayout = runCatching { cls.getMethod("getViewLayout").invoke(c) }.getOrNull()
            val phyView = runCatching { cls.getMethod("getPhyViewLayout").invoke(c) }.getOrNull()
            Log.i(TAG, "post-connect: phyRot=$phyRot viewLayout=$viewLayout phyViewLayout=$phyView")

            client = c
            clientClass = cls
            attachedView = view
            isActive = true
            Log.i(TAG, "BigmeInkController attached — daemon engaged on $view (limit=$limit)")
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(TAG, "attach failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
            reset()
            false
        } finally {
            attaching = false
        }
    }

    private fun cleanupClient(cls: Class<*>, c: Any) {
        runCatching { cls.getMethod("disconnect").invoke(c) }
        runCatching { cls.getMethod("unBindView").invoke(c) }
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) {
        pendingWidth = widthPx
        // HandwrittenClient does not expose a direct stroke-style API in this
        // firmware — the daemon paints with whatever Paint is used in the
        // InputProxy (configured via [pendingWidth]). Color is fixed to
        // BLACK at the EPD's natural ink colour.
    }

    override fun setEnabled(enabled: Boolean) {
        val c = client ?: return
        val cls = clientClass ?: return
        try {
            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "setEnabled($enabled) failed: ${t.message}")
        }
    }

    override fun syncOverlay(bitmap: android.graphics.Bitmap, region: Rect?, force: Boolean) {
        val c = client ?: return
        val cls = clientClass ?: return
        val view = attachedView ?: return
        try {
            // Blit the host bitmap onto the daemon's ION canvas. The buffer
            // is sized to (view.width, view.height) at connect time. SRC mode
            // resets every pixel in one pass so the daemon's accumulated
            // stroke ink is replaced by the host's canonical state.
            val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas ?: return
            val paint = android.graphics.Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (!force) return
            // Force-refresh: GU16 is a 16-level grey-update waveform — no
            // flash (unlike GC16), transitions both directions cleanly
            // (unlike MODE_HANDWRITE). Limit to [region] when provided.
            cls.getMethod("setOverlayEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            val rect = region ?: Rect(0, 0, view.width, view.height)
            cls.getMethod("inValidate", Rect::class.java, Int::class.javaPrimitiveType)
                .invoke(c, rect, MODE_GU16)
            Log.i(TAG, "syncOverlay: GU16 refresh $rect")
        } catch (t: Throwable) {
            Log.w(TAG, "syncOverlay failed: ${t.message}")
        }
    }

    override fun detach() {
        if (!isActive) return
        val c = client
        val cls = clientClass
        if (c != null && cls != null) {
            runCatching { cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, false) }
            runCatching { cls.getMethod("disconnect").invoke(c) }
            runCatching { cls.getMethod("unBindView").invoke(c) }
        }
        reset()
        Log.i(TAG, "BigmeInkController detached")
    }

    private fun reset() {
        client = null
        clientClass = null
        attachedView = null
        isActive = false
    }

    /**
     * Dynamic-proxy handler for HandwrittenClient.InputListener.
     *
     * The daemon fires callbacks on a binder thread with raw input. We do two
     * things per event:
     *  1. Draw the stroke segment to the daemon's ION-backed Canvas and
     *     `inValidate(rect, MODE_HANDWRITE)` — that's what makes the EPD
     *     refresh at sub-16ms latency.
     *  2. Marshal to main thread and fire [StrokeCallback] so the app-level
     *     pipeline runs on the UI thread.
     */
    private class InputProxy(
        private val sink: StrokeCallback,
        view: SurfaceView,
        private val getClient: () -> Any?,
        private val getClientClass: () -> Class<*>?,
        private val getStrokeWidth: () -> Float,
    ) : InvocationHandler {
        private val mainHandler = android.os.Handler(view.context.mainLooper)
        private val paint = android.graphics.Paint().apply {
            // EPD uses discrete greyscale levels; AA edges end up dithered
            // differently per inValidate, producing "train track" ghosts.
            isAntiAlias = false
            color = android.graphics.Color.BLACK
            strokeWidth = getStrokeWidth()
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private var lastX = 0f
        private var lastY = 0f
        // Accumulate dirty rect across MOVEs so EPD commits batch up. One-rect-
        // per-MOVE produced "train track" refresh artifacts on long strokes.
        private val accumDirty = android.graphics.Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        private val strokeBbox = android.graphics.Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        private var lastCommitMs = 0L
        // Force-commit the first MOVE of each stroke so the user sees ink
        // immediately rather than waiting for the COMMIT_INTERVAL_MS gate.
        private var firstMoveOfStroke = false
        private var downStartNs = 0L
        private var firstMoveArrivalNs = 0L
        // Throttle EPD pre-warm on ACTION_NEAR to ≤ 1 inValidate every 500ms
        // so we don't spam the daemon during hover.
        private var lastPreWarmNs = 0L
        private val preWarmRect = android.graphics.Rect(0, 0, 1, 1)
        private val PRE_WARM_INTERVAL_NS = 500_000_000L
        private val COMMIT_INTERVAL_MS = 16L  // one per vsync

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onInputTouch" && args != null && args.size >= 5) {
                val invokeStart = System.nanoTime()
                // Daemon dispatch latency: args[5] is CLOCK_REALTIME nanos
                // (wall-clock since Unix epoch) set by the daemon when it
                // reads the /dev/input event. Subtract from wall-now to get
                // the kernel → daemon → binder → JVM dispatch delay.
                if (args.size >= 6) {
                    val tsArg = args[5]
                    val daemonNs = when (tsArg) {
                        is Long -> tsArg
                        is Int -> tsArg.toLong()
                        else -> 0L
                    }
                    if (daemonNs != 0L) {
                        val nowWallNs = System.currentTimeMillis() * 1_000_000L
                        PerfCounters.recordDirect(
                            PerfMetric.INK_DAEMON_DISPATCH_LATENCY,
                            nowWallNs - daemonNs,
                        )
                    }
                }
                val action = args[0] as Int
                val x = (args[1] as Int).toFloat()
                val y = (args[2] as Int).toFloat()
                val pressure = (args[3] as Int).toFloat() / 4096f
                // The daemon's 6-arg onInputTouch passes a raw input-event
                // timestamp NOT in uptimeMillis epoch. Use our own.
                val ts = android.os.SystemClock.uptimeMillis()

                // Coords arriving here are ALREADY view-local: the daemon's
                // dispatcher calls HandwrittenClient.convertXY internally
                // before invoking the InputListener (unless mUseRawInputEvent
                // is true, which we never set). Double-conversion was the bug.
                val cls = getClientClass()
                val client = getClient()
                if (cls != null && client != null) {
                    try {
                        val canvas = cls.getMethod("getCanvas").invoke(client) as? android.graphics.Canvas
                        if (action == ACTION_DOWN) {
                            android.util.Log.i(TAG, "DOWN: canvas=$canvas view=($x,$y)")
                        }
                        if (canvas != null) {
                            when (action) {
                                ACTION_NEAR -> {
                                    val now = System.nanoTime()
                                    if (now - lastPreWarmNs >= PRE_WARM_INTERVAL_NS) {
                                        lastPreWarmNs = now
                                        try {
                                            cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                                                .invoke(client, preWarmRect, MODE_HANDWRITE)
                                        } catch (_: Throwable) { /* tolerate */ }
                                    }
                                }
                                ACTION_DOWN -> {
                                    lastX = x; lastY = y
                                    accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    strokeBbox.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    lastCommitMs = ts
                                    firstMoveOfStroke = true
                                    downStartNs = System.nanoTime()
                                    paint.strokeWidth = getStrokeWidth()
                                }
                                ACTION_MOVE -> {
                                    if (firstMoveOfStroke) firstMoveArrivalNs = System.nanoTime()
                                    val drawStart = System.nanoTime()
                                    canvas.drawLine(lastX, lastY, x, y, paint)
                                    PerfCounters.recordDirect(
                                        PerfMetric.INK_DAEMON_DRAW_LINE,
                                        System.nanoTime() - drawStart,
                                    )
                                    val pad = paint.strokeWidth.toInt() + 2
                                    val segL = minOf(lastX, x).toInt() - pad
                                    val segT = minOf(lastY, y).toInt() - pad
                                    val segR = maxOf(lastX, x).toInt() + pad
                                    val segB = maxOf(lastY, y).toInt() + pad
                                    accumDirty.union(segL, segT, segR, segB)
                                    strokeBbox.union(segL, segT, segR, segB)
                                    if (firstMoveOfStroke || ts - lastCommitMs >= COMMIT_INTERVAL_MS) {
                                        val wasFirst = firstMoveOfStroke
                                        firstMoveOfStroke = false
                                        val invStart = System.nanoTime()
                                        cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                                            .invoke(client, accumDirty, MODE_HANDWRITE)
                                        val invEnd = System.nanoTime()
                                        PerfCounters.recordDirect(
                                            PerfMetric.INK_DAEMON_INVALIDATE,
                                            invEnd - invStart,
                                        )
                                        if (wasFirst) {
                                            PerfCounters.recordDirect(
                                                PerfMetric.INK_DAEMON_DOWN_TO_PAINT,
                                                invEnd - downStartNs,
                                            )
                                            PerfCounters.recordDirect(
                                                PerfMetric.INK_DAEMON_DOWN_TO_FIRST_MOVE,
                                                firstMoveArrivalNs - downStartNs,
                                            )
                                            PerfCounters.recordDirect(
                                                PerfMetric.INK_DAEMON_FIRST_MOVE_TO_PAINT,
                                                invEnd - firstMoveArrivalNs,
                                            )
                                        }
                                        accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                        lastCommitMs = ts
                                    }
                                    lastX = x; lastY = y
                                }
                                ACTION_UP, ACTION_LEAVE -> {
                                    // Flush pending partial-refresh segment.
                                    if (accumDirty.left != Int.MAX_VALUE) {
                                        val invStart = System.nanoTime()
                                        cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                                            .invoke(client, accumDirty, MODE_HANDWRITE)
                                        PerfCounters.recordDirect(
                                            PerfMetric.INK_DAEMON_INVALIDATE,
                                            System.nanoTime() - invStart,
                                        )
                                        accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    }
                                    strokeBbox.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    lastX = x; lastY = y
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "paint threw: ${t.message}", t)
                    }
                }

                mainHandler.post {
                    when (action) {
                        ACTION_DOWN -> sink.onStrokeBegin(x, y, pressure, ts)
                        ACTION_MOVE -> sink.onStrokeMove(x, y, pressure, ts)
                        ACTION_UP, ACTION_LEAVE -> sink.onStrokeEnd(x, y, pressure, ts)
                    }
                }
                PerfCounters.recordDirect(
                    PerfMetric.INK_DAEMON_INVOKE_TOTAL,
                    System.nanoTime() - invokeStart,
                )
                return 0
            }
            return when (method.name) {
                "toString" -> "BigmeInkController.InputProxy"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.getOrNull(0) === proxy
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "BigmeInkController"
        private const val HANDWRITTEN_CLIENT = "com.xrz.HandwrittenClient"
        private const val INPUT_LISTENER = "com.xrz.HandwrittenClient\$InputListener"

        const val ACTION_NEAR = 0
        const val ACTION_DOWN = 1
        const val ACTION_MOVE = 2
        const val ACTION_UP = 3
        const val ACTION_LEAVE = 4
        const val MODE_HANDWRITE = 1029
        const val MODE_GC16 = 4
        const val MODE_GU16 = 132

        fun isBigmeDevice(): Boolean =
            Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
                Build.BRAND.equals("Bigme", ignoreCase = true)
    }
}
