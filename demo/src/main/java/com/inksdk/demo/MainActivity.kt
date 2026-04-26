package com.inksdk.demo

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.inksdk.ink.CounterSnapshot
import com.inksdk.ink.PerfCounters
import com.inksdk.ink.PerfMetric

class MainActivity : AppCompatActivity() {

    private lateinit var ink: InkSurfaceView
    private lateinit var status: TextView
    /** True iff the device is Onyx (i.e. raw-drawing mode owns the EPD and
     *  view-tree updates outside the limit rect won't refresh the panel
     *  without an explicit EpdController.invalidate). */
    private var onyxRefreshNeeded: Boolean = false
    /** Background thread for EPD refresh calls. The blocking
     *  `EpdController.refreshScreen` variant is the only call that
     *  reliably forces a panel update during active TouchHelper raw
     *  drawing — but it can wait many hundreds of ms for the waveform
     *  engine. Running it on a background thread keeps the main-thread
     *  CountDownTimer alive and the benchmark auto-stop honest. */
    private var epdRefreshThread: android.os.HandlerThread? = null
    private var epdRefreshHandler: android.os.Handler? = null
    private lateinit var btnBenchmark: Button
    private lateinit var btnClear: Button
    private lateinit var btnDump: Button
    private lateinit var btnMirror: Button
    private lateinit var btnTimer: Button
    /** Per-second status text update mode. 0=off, 1=1Hz (default countdown
     *  timer), 2=30Hz (frame-rate-paced status hammer). The 30Hz mode
     *  exists to test whether the timer-induced binder stall is per-update
     *  cost (would 30× as bad) or per-second-window cost (about the same). */
    private var timerMode: Int = 1
    private var timerHzHandler: android.os.Handler? = null
    private var timerHzRunnable: Runnable? = null
    /** Mode label captured at benchmark start, used to tag the perf dump. */
    private var currentBenchmarkLabel: String = ""
    /** Wall-clock at benchmark start (uptimeMillis). 0 = no benchmark. */
    private var benchmarkStartUptimeMs: Long = 0L
    /** Elapsed wall-clock of the most recent benchmark, captured at stop.
     *  Reads as <30 000 ms when the user tapped Stop early. */
    private var benchmarkElapsedMs: Long = 0L
    private val benchmarkTargetMs: Long = 30_000L
    private lateinit var perfPanel: ScrollView
    private lateinit var perfHeadline: TextView
    private lateinit var perfFooter: TextView
    private lateinit var perfTable: TableLayout

    private var benchmarkTimer: CountDownTimer? = null
    private var benchmarking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ink = findViewById(R.id.inkSurface)
        // Persisted Mirror flag, applied BEFORE surfaceCreated fires.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ink.mirrorEnabled = prefs.getBoolean(PREF_MIRROR, true)
        timerMode = prefs.getInt(PREF_TIMER_MODE, 1)
        status = findViewById(R.id.txtStatus)
        btnBenchmark = findViewById(R.id.btnBenchmark)
        btnClear = findViewById(R.id.btnClear)
        btnDump = findViewById(R.id.btnDumpPerf)
        btnMirror = findViewById(R.id.btnMirror)
        btnMirror.text = if (ink.mirrorEnabled) "Mirror: ON" else "Mirror: OFF"
        btnMirror.setOnClickListener {
            val newValue = !ink.mirrorEnabled
            prefs.edit().putBoolean(PREF_MIRROR, newValue).apply()
            Log.i(TAG, "Mirror toggled to $newValue — recreating activity")
            recreate()
        }
        btnTimer = findViewById(R.id.btnTimer)
        btnTimer.text = timerLabel()
        btnTimer.setOnClickListener {
            timerMode = (timerMode + 1) % 3
            prefs.edit().putInt(PREF_TIMER_MODE, timerMode).apply()
            btnTimer.text = timerLabel()
            Log.i(TAG, "Timer mode → $timerMode (${timerLabel()})")
        }

        btnBenchmark.setOnClickListener {
            if (benchmarking) stopBenchmark(showResults = true) else startBenchmark()
        }
        btnClear.setOnClickListener {
            ink.clear()
            // Wipe perf-counter ring buffers so each Clear starts a fresh
            // session — supports quick A/B iteration. ink.clear() also
            // resets controller-side diagnostics (stroke index).
            PerfCounters.reset()
            perfPanel.visibility = View.GONE
            // Resume raw drawing in case stopBenchmark paused it (Onyx flow).
            ink.setOverlayEnabled(true)
            status.text = "Cleared — counters and diagnostics reset"
            refreshStatusOnEpd()
        }
        btnDump.setOnClickListener {
            if (perfPanel.visibility == View.VISIBLE) perfPanel.visibility = View.GONE
            else showPerfPanel()
        }
        perfPanel = findViewById(R.id.perfPanel)
        perfHeadline = findViewById(R.id.txtPerfHeadline)
        perfFooter = findViewById(R.id.txtPerfFooter)
        perfTable = findViewById(R.id.tblPerf)
        findViewById<Button>(R.id.btnDismissPerf).setOnClickListener {
            perfPanel.visibility = View.GONE
            // Resume raw drawing — stopBenchmark paused it so the perf
            // overlay would actually show on Onyx.
            ink.setOverlayEnabled(true)
            status.text = if (ink.isOverlayActive()) "overlay active" else "fallback (Canvas)"
            refreshStatusOnEpd()
        }

        ink.post {
            // Onyx detection: brand match + overlay attached confirms TouchHelper
            // is in raw-drawing mode and we need to push the EPD ourselves on
            // every status text change.
            onyxRefreshNeeded = ink.isOverlayActive() &&
                android.os.Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
            if (onyxRefreshNeeded) startEpdRefreshThread()
            status.text = if (ink.isOverlayActive()) "overlay active" else "fallback (Canvas)"
            refreshStatusOnEpd()
        }
    }

    private fun startEpdRefreshThread() {
        if (epdRefreshThread != null) return
        val t = android.os.HandlerThread("EpdRefresh").apply { start() }
        epdRefreshThread = t
        epdRefreshHandler = android.os.Handler(t.looper)
    }

    override fun onDestroy() {
        benchmarkTimer?.cancel()
        epdRefreshThread?.quitSafely()
        epdRefreshThread = null
        epdRefreshHandler = null
        super.onDestroy()
    }

    /** On Onyx in raw-drawing mode, view-tree updates outside the TouchHelper
     *  limit rect don't push to the panel — the EPD waveform engine is busy
     *  servicing the ink path. Call this after every status.text change so
     *  the toolbar/status area visibly refreshes. No-op on non-Onyx hardware
     *  (Bigme's xrz daemon and the Noop fallback both leave SurfaceFlinger
     *  free to refresh background views).
     *
     *  We tried [com.onyx.android.sdk.api.device.epd.EpdController.postInvalidate]
     *  first — it returns immediately but the invalidate gets coalesced
     *  with the SurfaceView's pending refresh, which never lands because
     *  TouchHelper owns it.
     *
     *  [com.onyx.android.sdk.api.device.epd.EpdController.refreshScreen]
     *  bypasses the View invalidate path and tells the EPD HAL to flip
     *  the panel pixels for [status] directly. It's blocking — can wait
     *  hundreds of ms for the waveform engine while raw drawing is hot —
     *  so we run it on a dedicated background HandlerThread to keep the
     *  main-thread CountDownTimer ticking and the benchmark auto-stop
     *  honest. */
    private fun refreshStatusOnEpd() {
        if (!onyxRefreshNeeded) return
        val handler = epdRefreshHandler ?: return
        // Coalesce: drop pending refreshes — only the latest text matters.
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try {
                com.onyx.android.sdk.api.device.epd.EpdController.refreshScreen(
                    status,
                    com.onyx.android.sdk.api.device.epd.UpdateMode.DU,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "EpdController.refreshScreen failed: ${t.message}")
                onyxRefreshNeeded = false
            }
        }
    }


    /** Reset counters and clear the canvas, then run a 30 s descending timer
     *  during which the user writes continuously. Auto-show results on
     *  finish. The Start button becomes Stop while running. */
    private fun startBenchmark() {
        PerfCounters.reset()
        ink.clear()
        perfPanel.visibility = View.GONE
        benchmarking = true
        currentBenchmarkLabel = timerLabel()
        benchmarkStartUptimeMs = android.os.SystemClock.uptimeMillis()
        benchmarkElapsedMs = 0L
        Log.i(TAG, "─── BENCHMARK START [$currentBenchmarkLabel] ───")
        btnBenchmark.text = "Stop"
        btnClear.isEnabled = false
        btnDump.isEnabled = false
        benchmarkTimer = object : CountDownTimer(30_000L, 1_000L) {
            override fun onTick(msUntilFinished: Long) {
                if (timerMode != 1) return  // 1Hz path; 30Hz uses a separate handler
                val s = ((msUntilFinished + 999) / 1_000).toInt()
                status.text = "Recording — write now! ${s}s left"
                refreshStatusOnEpd()
            }
            override fun onFinish() {
                stopBenchmark(showResults = true)
            }
        }.start()
        // 30Hz mode: hammer the status TextView at frame rate by ticking a
        // monotonic counter. Tests whether the timer stall scales with
        // cadence (suggesting per-update cost) or saturates (one-shot
        // contention per second-window).
        if (timerMode == 2) {
            val handler = android.os.Handler(mainLooper)
            timerHzHandler = handler
            var counter = 0
            val r = object : Runnable {
                override fun run() {
                    if (!benchmarking) return
                    status.text = "Recording — counter=${counter++} (30Hz)"
                    refreshStatusOnEpd()
                    handler.postDelayed(this, 33L)
                }
            }
            timerHzRunnable = r
            handler.post(r)
        }
        status.text = when (timerMode) {
            0 -> "Recording — write now! (Timer OFF)"
            1 -> "Recording — write now! 30s left"
            2 -> "Recording — counter=0 (30Hz)"
            else -> "Recording…"
        }
        refreshStatusOnEpd()
    }

    private fun stopBenchmark(showResults: Boolean) {
        benchmarkTimer?.cancel()
        benchmarkTimer = null
        timerHzRunnable?.let { timerHzHandler?.removeCallbacks(it) }
        timerHzRunnable = null
        timerHzHandler = null
        benchmarking = false
        benchmarkElapsedMs = if (benchmarkStartUptimeMs > 0L)
            android.os.SystemClock.uptimeMillis() - benchmarkStartUptimeMs else 0L
        btnBenchmark.text = "Bench 30s"
        btnClear.isEnabled = true
        btnDump.isEnabled = true
        // Onyx: TouchHelper monopolises the EPD waveform engine while raw
        // drawing is enabled, so view-tree composes (status text, the perf
        // panel) don't visibly land. Pause raw drawing here so the queued
        // SurfaceFlinger composes drain to the panel and the user can
        // actually see the benchmark completed + the perf overlay.
        // Resumed by the perf-panel dismiss button or the Clear button.
        ink.setOverlayEnabled(false)
        status.text = "Bench complete — tap Clear to write again"
        refreshStatusOnEpd()
        if (showResults) showPerfPanel()
    }

    private fun timerLabel(): String = when (timerMode) {
        0 -> "Timer: OFF"
        1 -> "Timer: 1Hz"
        2 -> "Timer: 30Hz"
        else -> "Timer: ?"
    }

    private fun showPerfPanel() {
        val snap = PerfCounters.snapshot()

        // Capture window: how long this perf snapshot actually represents.
        // Tapping Stop early (before the 30 s timer fires) means the
        // counters reflect a partial window — call that out at the top so
        // small samples aren't mistaken for the steady-state distribution.
        val targetMs = benchmarkTargetMs
        val actualMs = benchmarkElapsedMs
        val partial = actualMs in 1L until (targetMs - 500L)
        val captureLine = when {
            actualMs == 0L -> "Capture window: ad-hoc dump (no benchmark)"
            partial -> "⚠️  PARTIAL CAPTURE — ${"%.1f".format(actualMs / 1000.0)}s of " +
                "${targetMs / 1000}s (Stop tapped early)"
            else -> "Capture window: ${"%.1f".format(actualMs / 1000.0)}s"
        }

        // Headline: prefer pen.kernel_to_paint (Bigme), fall back to
        // pen.kernel_to_jvm (Onyx — paint boundary is not measurable).
        val k2p = snap[PerfMetric.PEN_KERNEL_TO_PAINT]
        val k2j = snap[PerfMetric.PEN_KERNEL_TO_JVM]
        val headlineLine = when {
            k2p != null && k2p.count > 0L ->
                "pen.kernel_to_paint — n=${k2p.count}  " +
                    "p50=${k2p.p50Ms}ms  p95=${k2p.p95Ms}ms  max=${k2p.maxMs}ms"
            k2j != null && k2j.count > 0L ->
                "pen.kernel_to_jvm (Onyx proxy) — n=${k2j.count}  " +
                    "p50=${k2j.p50Ms}ms  p95=${k2j.p95Ms}ms  max=${k2j.maxMs}ms"
            else -> "no headline samples yet"
        }
        perfHeadline.text = "$captureLine\n$headlineLine"

        perfTable.removeAllViews()

        val tiers = listOf(
            "pen" to "PEN  (per stroke)",
            "event" to "EVENT  (per binder event)",
            "paint" to "PAINT  (per draw segment)",
        )
        var sectionIdx = 0
        for ((tier, label) in tiers) {
            val rows = snap.entries
                .filter { it.key.label.removePrefix(PerfCounters.prefix).startsWith("$tier.") }
                .sortedBy { it.key.ordinal }
            if (rows.isEmpty()) continue

            perfTable.addView(sectionHeader(label, topGap = sectionIdx > 0))
            perfTable.addView(headerRow())
            for ((metric, snapEntry) in rows) {
                if (snapEntry == null) continue
                val short = metric.label
                    .removePrefix(PerfCounters.prefix)
                    .removePrefix("$tier.")
                perfTable.addView(dataRow(short, snapEntry))
            }
            sectionIdx++
        }

        perfFooter.text = "prefix = \"${PerfCounters.prefix}\""
        perfPanel.visibility = View.VISIBLE
        perfPanel.scrollTo(0, 0)
        Log.i(TAG, "Perf panel shown — ${snap.values.sumOf { it.count }} total samples")
        // Mirror to logcat as a flat block so it can be pulled via adb
        // without a screen capture.
        val label = if (currentBenchmarkLabel.isNotEmpty()) " [$currentBenchmarkLabel]" else ""
        Log.i(TAG, "─── PERF DUMP$label ───")
        if (partial) {
            Log.w(TAG, "PARTIAL CAPTURE: ${"%.1f".format(actualMs / 1000.0)}s of " +
                "${targetMs / 1000}s — counters reflect an incomplete window")
        }
        Log.i(TAG, perfHeadline.text.toString().replace("\n", " | "))
        for ((m, s) in snap) {
            if (s.count == 0L) continue
            Log.i(TAG, String.format("%-32s n=%-7d p50=%-4dms p95=%-4dms max=%-4dms",
                m.label, s.count, s.p50Ms, s.p95Ms, s.maxMs))
        }
        Log.i(TAG, "─── END PERF DUMP$label ───")
    }

    private fun sectionHeader(text: String, topGap: Boolean): TableRow {
        val row = TableRow(this)
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, if (topGap) dp(16) else 0, 0, dp(4))
        }
        val lp = TableRow.LayoutParams().apply { span = 5 }
        row.addView(tv, lp)
        return row
    }

    private fun headerRow(): TableRow {
        val row = TableRow(this)
        row.addView(headerCell("metric", gravityStart = true))
        row.addView(headerCell("n"))
        row.addView(headerCell("p50ms"))
        row.addView(headerCell("p95ms"))
        row.addView(headerCell("max"))
        return row
    }

    private fun dataRow(name: String, s: CounterSnapshot): TableRow {
        val row = TableRow(this)
        row.addView(nameCell(name))
        row.addView(numberCell(s.count.toString()))
        row.addView(numberCell(s.p50Ms.toString()))
        row.addView(numberCell(s.p95Ms.toString()))
        row.addView(numberCell(s.maxMs.toString()))
        return row
    }

    private fun headerCell(text: String, gravityStart: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#555555"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = if (gravityStart) Gravity.START else Gravity.END
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

    private fun nameCell(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setSingleLine(false)
            setHorizontallyScrolling(false)
            setPadding(dp(6), dp(4), dp(12), dp(4))
            layoutParams = TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun numberCell(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "inksdk-demo"
        private const val PREF_MIRROR = "mirror_enabled"
        private const val PREF_TIMER_MODE = "timer_mode"
    }
}
