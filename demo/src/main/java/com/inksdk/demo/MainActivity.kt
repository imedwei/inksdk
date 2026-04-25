package com.inksdk.demo

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.inksdk.ink.PerfCounters

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val ink = findViewById<InkSurfaceView>(R.id.inkSurface)
        val status = findViewById<TextView>(R.id.txtStatus)
        findViewById<Button>(R.id.btnClear).setOnClickListener { ink.clear() }
        findViewById<Button>(R.id.btnDumpPerf).setOnClickListener {
            val s = buildString {
                append("Counters:\n")
                for ((m, snap) in PerfCounters.snapshot()) {
                    if (snap.count == 0L) continue
                    append("${m.label}: count=${snap.count} p50=${snap.p50Ms}ms ")
                    append("p95=${snap.p95Ms}ms max=${snap.maxMs}ms\n")
                }
            }
            Log.i(TAG, s)
            status.text = s
        }
        ink.post {
            status.text = if (ink.isOverlayActive()) "overlay active" else "fallback (Canvas)"
        }
    }

    companion object { private const val TAG = "MainActivity" }
}
