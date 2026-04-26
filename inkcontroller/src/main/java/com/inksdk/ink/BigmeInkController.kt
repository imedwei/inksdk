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

    // Per-session diagnostic counter — kept here (not on InputProxy) so the
    // host can reset it on demand (e.g. Clear button) without detaching.
    private val strokeIndex = java.util.concurrent.atomic.AtomicInteger(0)

    override fun resetDiagnostics() {
        strokeIndex.set(0)
    }

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
                InputProxy(
                    callback,
                    view,
                    getClient = { client },
                    getClientClass = { clientClass },
                    getStrokeWidth = { pendingWidth },
                    strokeIndex = strokeIndex,
                ),
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

    override fun mirrorOverlay(target: android.graphics.Bitmap): Boolean {
        val c = client ?: return false
        val cls = clientClass ?: return false
        val view = attachedView ?: return false
        return try {
            // Preferred: HandwrittenClient.getContent() returns the ION-
            // backed bitmap. On some xrz firmwares this stays null even
            // after first commit — fall back to reflecting the Canvas's
            // hidden mBitmap field, which holds the same underlying buffer.
            // Requires HiddenApiBypass.addHiddenApiExemptions("L") at host
            // Application.onCreate() on Android 14+.
            var src = cls.getMethod("getContent").invoke(c) as? android.graphics.Bitmap
            if (src == null) {
                val daemonCanvas = cls.getMethod("getCanvas").invoke(c)
                    as? android.graphics.Canvas
                if (daemonCanvas != null) {
                    src = canvasBitmapField()?.get(daemonCanvas) as? android.graphics.Bitmap
                }
            }
            if (src == null) {
                Log.w(TAG, "mirrorOverlay: no source bitmap (getContent and Canvas.mBitmap both unavailable)")
                return false
            }
            // The daemon's content buffer can be either view-sized or panel-
            // sized depending on firmware. If it matches our target, blit
            // straight; otherwise treat src as panel-coords and crop the
            // view-region out using the view's screen position.
            val canvas = android.graphics.Canvas(target)
            if (src.width == target.width && src.height == target.height) {
                canvas.drawBitmap(src, 0f, 0f, null)
                Log.i(TAG, "mirrorOverlay: 1:1 copy ${src.width}x${src.height}")
            } else {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val sx = loc[0].coerceIn(0, maxOf(0, src.width - target.width))
                val sy = loc[1].coerceIn(0, maxOf(0, src.height - target.height))
                val srcRect = android.graphics.Rect(
                    sx, sy,
                    (sx + target.width).coerceAtMost(src.width),
                    (sy + target.height).coerceAtMost(src.height),
                )
                val dstRect = android.graphics.Rect(0, 0, srcRect.width(), srcRect.height())
                canvas.drawBitmap(src, srcRect, dstRect, null)
                Log.i(TAG, "mirrorOverlay: panel-crop src=${src.width}x${src.height} " +
                    "target=${target.width}x${target.height} viewLoc=(${loc[0]},${loc[1]}) srcRect=$srcRect")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "mirrorOverlay failed: ${t.message}", t)
            false
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

    override fun clearRegion(region: android.graphics.Rect) {
        if (region.isEmpty) return
        val c = client ?: return
        val cls = clientClass ?: return
        try {
            val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas ?: return
            canvas.save()
            canvas.clipRect(region)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.restore()
            // Intentionally NO inValidate — leaves the daemon's already-
            // displayed EPD pixels alone (the host's SurfaceView compose has
            // them under the canonical bitmap by now). Wiping only the ION
            // buffer prevents ghost-accumulation and the position-shift
            // symptom where two pipelines composit the same ink at slightly
            // different panel pixels.
        } catch (t: Throwable) {
            Log.w(TAG, "clearRegion failed: ${t.message}")
        }
    }

    /** Diagnostic: passive inspection of the daemon's Canvas/content buffer
     *  and view layouts. Does NOT call any drawing or invalidation — just
     *  reflectively inspects properties so it cannot upset the daemon
     *  state. Returns a multi-line string for on-screen display AND mirrors
     *  to logcat. */
    fun diagnostics(): String {
        val c = client ?: return "not attached"
        val cls = clientClass ?: return "no clientClass"
        val view = attachedView ?: return "no attachedView"
        val sb = StringBuilder()
        try {
            val canvas = runCatching { cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas }.getOrNull()
            sb.appendLine("=== Daemon Canvas ===")
            sb.appendLine("instance: $canvas")
            if (canvas != null) {
                sb.appendLine("class:    ${canvas.javaClass.name}")
                sb.appendLine("dims:     ${canvas.width} x ${canvas.height}")
                // Probe only the well-known field name; don't iterate all
                // declared fields (some are native pointers and touching
                // them aggressively appears to upset the daemon).
                val mBitmap = runCatching {
                    android.graphics.Canvas::class.java.getDeclaredField("mBitmap")
                        .apply { isAccessible = true }
                        .get(canvas)
                }.getOrNull()
                sb.appendLine("Canvas.mBitmap: ${shortValue(mBitmap)}")
            }
            sb.appendLine()
            sb.appendLine("=== getContent() ===")
            val content = runCatching { cls.getMethod("getContent").invoke(c) }.getOrNull()
            sb.appendLine(if (content == null) "null" else "${content::class.java.name}: ${shortValue(content)}")
            sb.appendLine()
            sb.appendLine("=== Daemon view layouts ===")
            sb.appendLine("getViewLayout():     ${runCatching { cls.getMethod("getViewLayout").invoke(c) }.getOrNull()}")
            sb.appendLine("getPhyViewLayout():  ${runCatching { cls.getMethod("getPhyViewLayout").invoke(c) }.getOrNull()}")
            sb.appendLine("getPhyRotation():    ${runCatching { cls.getMethod("getPhyRotation").invoke(c) }.getOrNull()}")
            sb.appendLine("getCurViewRotation():${runCatching { cls.getMethod("getCurViewRotation").invoke(c) }.getOrNull()}")
            sb.appendLine()
            sb.appendLine("=== Attached View ===")
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            sb.appendLine("class:            ${view.javaClass.name}")
            sb.appendLine("locationOnScreen: (${loc[0]}, ${loc[1]})")
            sb.appendLine("size:             ${view.width} x ${view.height}")
            sb.appendLine("Build:            ${android.os.Build.MANUFACTURER}/${android.os.Build.BRAND}/${android.os.Build.MODEL}/SDK${android.os.Build.VERSION.SDK_INT}")
        } catch (t: Throwable) {
            sb.appendLine("diagnostics threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        Log.i(TAG, "diagnostics:\n$sb")
        return sb.toString()
    }

    private fun shortValue(v: Any?): String {
        if (v == null) return "null"
        if (v is android.graphics.Bitmap) return "Bitmap(${v.width}x${v.height}, ${v.config})"
        val s = v.toString()
        return if (s.length > 80) s.take(77) + "..." else s
    }

    /** Diagnostic: paint a single 30x30 filled rect at daemon-canvas coord
     *  ([x], [y]) and request a HANDWRITE-mode refresh on a tight rect.
     *  Single-shot only — earlier multi-fixture batches with GU16 caused a
     *  hard reboot on the HiBreak Plus, so this is intentionally minimal. */
    fun paintFixture(x: Int, y: Int) {
        val c = client ?: return
        val cls = clientClass ?: return
        try {
            val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas ?: return
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = false
            }
            val r = android.graphics.Rect(x - 15, y - 15, x + 15, y + 15)
            canvas.drawRect(r, paint)
            val refresh = android.graphics.Rect(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2)
            cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                .invoke(c, refresh, MODE_HANDWRITE)
            Log.i(TAG, "paintFixture: canvas($x, $y) → rect=$r refreshed=$refresh mode=HANDWRITE")
        } catch (t: Throwable) {
            Log.w(TAG, "paintFixture failed: ${t.message}", t)
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
        private val strokeIndex: java.util.concurrent.atomic.AtomicInteger,
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
        private var downStartNs = 0L          // System.nanoTime() at JVM-DOWN entry
        private var firstMoveArrivalNs = 0L   // System.nanoTime() at first MOVE
        // Daemon CLOCK_REALTIME at the kernel pen-down read. Captured at
        // ACTION_DOWN, consumed by the first inValidate to compute the
        // wall-clock pen.kernel_to_paint headline metric.
        private var downKernelWallNs = 0L
        // strokeIndex / nearEventCount are now shared with the parent
        // BigmeInkController so resetDiagnostics() can clear them on demand.
        private val COMMIT_INTERVAL_MS = 16L  // one per vsync

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onInputTouch" && args != null && args.size >= 5) {
                val invokeStart = System.nanoTime()
                // Daemon dispatch latency: args[5] is CLOCK_REALTIME nanos
                // (wall-clock since Unix epoch) set by the daemon when it
                // reads the /dev/input event. Subtract from wall-now to get
                // the kernel → daemon → binder → JVM dispatch delay.
                var daemonNs = 0L
                if (args.size >= 6) {
                    val tsArg = args[5]
                    daemonNs = when (tsArg) {
                        is Long -> tsArg
                        is Int -> tsArg.toLong()
                        else -> 0L
                    }
                    if (daemonNs != 0L) {
                        val nowWallNs = System.currentTimeMillis() * 1_000_000L
                        PerfCounters.recordDirect(
                            PerfMetric.EVENT_KERNEL_TO_JVM,
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
                                // ACTION_NEAR is intentionally not handled. On
                                // Bigme HiBreak Plus the xrz daemon never
                                // dispatches NEAR events to the InputListener
                                // under cooked or raw input, so the historical
                                // pre-warm heuristic (small inValidate) was dead
                                // code. The first-stroke "wake" cost (~100 ms)
                                // lives in the touch IC sleep state and only
                                // physical capacitive proximity wakes it. See
                                // docs/metrics.md → "Touch IC wake".
                                ACTION_DOWN -> {
                                    lastX = x; lastY = y
                                    accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    strokeBbox.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    lastCommitMs = ts
                                    firstMoveOfStroke = true
                                    downStartNs = System.nanoTime()
                                    downKernelWallNs = daemonNs
                                    strokeIndex.incrementAndGet()
                                    if (daemonNs != 0L) {
                                        // pen.kernel_to_jvm: wall delta from daemon's
                                        // kernel-read ts to this DOWN landing here.
                                        val nowWallNs = System.currentTimeMillis() * 1_000_000L
                                        PerfCounters.recordDirect(
                                            PerfMetric.PEN_KERNEL_TO_JVM,
                                            nowWallNs - daemonNs,
                                        )
                                    }
                                    paint.strokeWidth = getStrokeWidth()
                                }
                                ACTION_MOVE -> {
                                    if (firstMoveOfStroke) firstMoveArrivalNs = System.nanoTime()
                                    val drawStart = System.nanoTime()
                                    canvas.drawLine(lastX, lastY, x, y, paint)
                                    PerfCounters.recordDirect(
                                        PerfMetric.PAINT_DRAW_SEGMENT,
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
                                            PerfMetric.PAINT_INVALIDATE_CALL,
                                            invEnd - invStart,
                                        )
                                        if (wasFirst) {
                                            PerfCounters.recordDirect(
                                                PerfMetric.PEN_JVM_TO_PAINT,
                                                invEnd - downStartNs,
                                            )
                                            PerfCounters.recordDirect(
                                                PerfMetric.PEN_JVM_TO_FIRST_MOVE,
                                                firstMoveArrivalNs - downStartNs,
                                            )
                                            PerfCounters.recordDirect(
                                                PerfMetric.PEN_MOVE_TO_PAINT,
                                                invEnd - firstMoveArrivalNs,
                                            )
                                            // Headline: pen.kernel_to_paint =
                                            // wall-clock from daemon's DOWN read to
                                            // first inValidate returning. Cross-clock
                                            // measurement: both endpoints are
                                            // wall-time (System.currentTimeMillis
                                            // and daemon CLOCK_REALTIME).
                                            val k2pMs = if (downKernelWallNs != 0L) {
                                                val nowWallNs = System.currentTimeMillis() * 1_000_000L
                                                val deltaNs = nowWallNs - downKernelWallNs
                                                PerfCounters.recordDirect(
                                                    PerfMetric.PEN_KERNEL_TO_PAINT,
                                                    deltaNs,
                                                )
                                                deltaNs / 1_000_000
                                            } else -1
                                            // First-N-strokes diagnostics: log the
                                            // headline directly so a slow first
                                            // stroke isn't averaged out of the
                                            // ring-buffer percentiles.
                                            val sIdx = strokeIndex.get()
                                            val jvmToPaintMs = (invEnd - downStartNs) / 1_000_000
                                            val jvmToFirstMoveMs = (firstMoveArrivalNs - downStartNs) / 1_000_000
                                            val moveToPaintMs = (invEnd - firstMoveArrivalNs) / 1_000_000
                                            if (sIdx <= 10) {
                                                android.util.Log.i(TAG,
                                                    "FIRST_STROKE #$sIdx: " +
                                                        "kernel_to_paint=${k2pMs}ms " +
                                                        "jvm_to_paint=${jvmToPaintMs}ms " +
                                                        "jvm_to_first_move=${jvmToFirstMoveMs}ms " +
                                                        "move_to_paint=${moveToPaintMs}ms")
                                            }
                                            // Always log strokes whose wall-clock first-paint
                                            // latency crosses SLOW_STROKE_MS, with wall-clock
                                            // HH:mm:ss.SSS timestamp so the user can correlate
                                            // outliers against external events (timer ticks,
                                            // GC pauses, panel waveform refreshes, etc.).
                                            if (k2pMs >= SLOW_STROKE_MS) {
                                                android.util.Log.i(TAG,
                                                    "SLOW_STROKE #$sIdx @${wallClockHms()}: " +
                                                        "kernel_to_paint=${k2pMs}ms " +
                                                        "kernel_to_jvm=${k2pMs - jvmToPaintMs}ms " +
                                                        "jvm_to_paint=${jvmToPaintMs}ms " +
                                                        "jvm_to_first_move=${jvmToFirstMoveMs}ms " +
                                                        "move_to_paint=${moveToPaintMs}ms")
                                            }
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
                                            PerfMetric.PAINT_INVALIDATE_CALL,
                                            System.nanoTime() - invStart,
                                        )
                                        accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    }
                                    strokeBbox.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    lastX = x; lastY = y
                                    downKernelWallNs = 0L
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
                    PerfMetric.EVENT_HANDLER,
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

        // Lazily-resolved Canvas.mBitmap accessor. Hidden API on AOSP, but
        // marked @UnsupportedAppUsage so it remains reachable when the host
        // calls HiddenApiBypass.addHiddenApiExemptions("L") on Android 14+.
        @Volatile private var canvasBitmapFieldCached: java.lang.reflect.Field? = null
        @Volatile private var canvasBitmapFieldChecked = false
        private fun canvasBitmapField(): java.lang.reflect.Field? {
            if (canvasBitmapFieldChecked) return canvasBitmapFieldCached
            synchronized(this) {
                if (!canvasBitmapFieldChecked) {
                    canvasBitmapFieldCached = try {
                        android.graphics.Canvas::class.java.getDeclaredField("mBitmap")
                            .apply { isAccessible = true }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Canvas.mBitmap reflection unavailable: ${t.message}")
                        null
                    }
                    canvasBitmapFieldChecked = true
                }
            }
            return canvasBitmapFieldCached
        }

        // Latency threshold (ms) above which a stroke is logged as a
        // SLOW_STROKE. Tuned to ~10× the warm p95 — anything past this is
        // worth correlating against external events.
        private const val SLOW_STROKE_MS = 30L

        private val wallClockFormatter = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        private fun wallClockHms(): String = wallClockFormatter.format(System.currentTimeMillis())

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
