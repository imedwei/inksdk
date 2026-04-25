package com.inksdk.ink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfCountersTest {

    @After
    fun tearDown() { PerfCounters.reset() }

    @Test
    fun emptyCounterReportsZeros() {
        val s = PerfCounters.get(PerfMetric.INK_DAEMON_INVOKE_TOTAL)
        assertEquals(0L, s.count)
        assertEquals(0L, s.p50Ms)
        assertEquals(0L, s.p95Ms)
        assertEquals(0L, s.maxMs)
        assertTrue(s.samples.isEmpty())
    }

    @Test
    fun recordDirectAccumulatesAndComputesPercentiles() {
        // 100 evenly-spaced samples: 1ms..100ms (in nanos).
        for (i in 1..100) {
            PerfCounters.recordDirect(PerfMetric.INK_DAEMON_DRAW_LINE, i.toLong() * 1_000_000)
        }
        val s = PerfCounters.get(PerfMetric.INK_DAEMON_DRAW_LINE)
        assertEquals(100L, s.count)
        assertEquals(100L, s.maxMs)
        // p50 is at index 50 in a sorted array of 1..100 → value 51
        assertEquals(51L, s.p50Ms)
        // p95: index 95 → value 96
        assertEquals(96L, s.p95Ms)
    }

    @Test
    fun ringBufferKeepsLatestWindow() {
        // 250 samples into a 200-window: only the last 200 should remain.
        for (i in 1..250) {
            PerfCounters.recordDirect(PerfMetric.INK_DAEMON_INVALIDATE, i.toLong() * 1_000_000)
        }
        val s = PerfCounters.get(PerfMetric.INK_DAEMON_INVALIDATE)
        assertEquals(250L, s.count) // total observed
        assertEquals(200, s.samples.size) // window size
        // Values 51..250 retained; max=250, p50≈value at sorted index 100 = 151.
        assertEquals(250L, s.maxMs)
        assertEquals(151L, s.p50Ms)
    }

    @Test
    fun timeBlockRecordsElapsed() {
        val result = PerfCounters.time(PerfMetric.INK_DAEMON_DOWN_TO_PAINT) {
            // Spin briefly so elapsed is > 0.
            var x = 0; for (i in 0 until 1_000) x = (x + i) % 7; x
        }
        // result is the spin output; we just need to confirm a sample landed.
        assertTrue("PerfCounters.time recorded: $result", result >= 0)
        assertEquals(1L, PerfCounters.get(PerfMetric.INK_DAEMON_DOWN_TO_PAINT).count)
    }

    @Test
    fun resetClearsAllCounters() {
        PerfCounters.recordDirect(PerfMetric.INK_DAEMON_DISPATCH_LATENCY, 5_000_000)
        assertEquals(1L, PerfCounters.get(PerfMetric.INK_DAEMON_DISPATCH_LATENCY).count)
        PerfCounters.reset()
        assertEquals(0L, PerfCounters.get(PerfMetric.INK_DAEMON_DISPATCH_LATENCY).count)
    }
}
