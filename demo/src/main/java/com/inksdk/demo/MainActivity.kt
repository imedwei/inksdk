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
    private lateinit var btnBenchmark: Button
    private lateinit var btnClear: Button
    private lateinit var btnDump: Button
    private lateinit var btnMirror: Button
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
            status.text = "Cleared — counters and diagnostics reset"
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
        }

        ink.post {
            status.text = if (ink.isOverlayActive()) "overlay active" else "fallback (Canvas)"
        }
    }

    override fun onDestroy() {
        benchmarkTimer?.cancel()
        super.onDestroy()
    }

    /** Reset counters and clear the canvas, then run a 30 s descending timer
     *  during which the user writes continuously. Auto-show results on
     *  finish. The Start button becomes Stop while running. */
    private fun startBenchmark() {
        PerfCounters.reset()
        ink.clear()
        perfPanel.visibility = View.GONE
        benchmarking = true
        btnBenchmark.text = "Stop"
        btnClear.isEnabled = false
        btnDump.isEnabled = false
        benchmarkTimer = object : CountDownTimer(30_000L, 1_000L) {
            override fun onTick(msUntilFinished: Long) {
                val s = ((msUntilFinished + 999) / 1_000).toInt()
                status.text = "Recording — write now! ${s}s left"
            }
            override fun onFinish() {
                stopBenchmark(showResults = true)
            }
        }.start()
        status.text = "Recording — write now! 30s left"
    }

    private fun stopBenchmark(showResults: Boolean) {
        benchmarkTimer?.cancel()
        benchmarkTimer = null
        benchmarking = false
        btnBenchmark.text = "Bench 30s"
        btnClear.isEnabled = true
        btnDump.isEnabled = true
        status.text = if (ink.isOverlayActive()) "overlay active" else "fallback (Canvas)"
        if (showResults) showPerfPanel()
    }

    private fun showPerfPanel() {
        val snap = PerfCounters.snapshot()

        val headline = snap[PerfMetric.PEN_KERNEL_TO_PAINT]
        perfHeadline.text = if (headline == null || headline.count == 0L) {
            "pen.kernel_to_paint — no samples yet"
        } else {
            "pen.kernel_to_paint — n=${headline.count}  " +
                "p50=${headline.p50Ms}ms  p95=${headline.p95Ms}ms  max=${headline.maxMs}ms"
        }

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
        Log.i(TAG, "─── PERF DUMP ───")
        Log.i(TAG, perfHeadline.text.toString())
        for ((m, s) in snap) {
            if (s.count == 0L) continue
            Log.i(TAG, String.format("%-32s n=%-7d p50=%-4dms p95=%-4dms max=%-4dms",
                m.label, s.count, s.p50Ms, s.p95Ms, s.maxMs))
        }
        Log.i(TAG, "─── END PERF DUMP ───")
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
    }
}
