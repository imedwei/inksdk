package com.inksdk.ink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfCountersTest {

    @After
    fun tearDown() {
        PerfCounters.sink = DefaultSink
        PerfCounters.reset()
        PerfCounters.prefix = "ink."
    }

    @Test
    fun emptyCounterReportsZeros() {
        val s = PerfCounters.get(PerfMetric.EVENT_HANDLER)
        assertEquals(0L, s.count)
        assertEquals(0L, s.p50Ms)
        assertEquals(0L, s.p95Ms)
        assertEquals(0L, s.maxMs)
        assertTrue(s.samples.isEmpty())
    }

    @Test
    fun recordDirectAccumulatesAndComputesPercentiles() {
        for (i in 1..100) {
            PerfCounters.recordDirect(PerfMetric.PAINT_DRAW_SEGMENT, i.toLong() * 1_000_000)
        }
        val s = PerfCounters.get(PerfMetric.PAINT_DRAW_SEGMENT)
        assertEquals(100L, s.count)
        assertEquals(100L, s.maxMs)
        assertEquals(51L, s.p50Ms)
        assertEquals(96L, s.p95Ms)
    }

    @Test
    fun ringBufferKeepsLatestWindow() {
        for (i in 1..250) {
            PerfCounters.recordDirect(PerfMetric.PAINT_INVALIDATE_CALL, i.toLong() * 1_000_000)
        }
        val s = PerfCounters.get(PerfMetric.PAINT_INVALIDATE_CALL)
        assertEquals(250L, s.count)
        assertEquals(200, s.samples.size)
        assertEquals(250L, s.maxMs)
        assertEquals(151L, s.p50Ms)
    }

    @Test
    fun timeBlockRecordsElapsed() {
        val result = PerfCounters.time(PerfMetric.PEN_KERNEL_TO_PAINT) {
            var x = 0; for (i in 0 until 1_000) x = (x + i) % 7; x
        }
        assertTrue("PerfCounters.time recorded: $result", result >= 0)
        assertEquals(1L, PerfCounters.get(PerfMetric.PEN_KERNEL_TO_PAINT).count)
    }

    @Test
    fun resetClearsAllCounters() {
        PerfCounters.recordDirect(PerfMetric.EVENT_KERNEL_TO_JVM, 5_000_000)
        assertEquals(1L, PerfCounters.get(PerfMetric.EVENT_KERNEL_TO_JVM).count)
        PerfCounters.reset()
        assertEquals(0L, PerfCounters.get(PerfMetric.EVENT_KERNEL_TO_JVM).count)
    }

    @Test
    fun defaultPrefixIsInk() {
        assertEquals("ink.", PerfCounters.prefix)
        assertEquals("ink.pen.kernel_to_paint", PerfMetric.PEN_KERNEL_TO_PAINT.label)
        assertEquals("ink.event.handler", PerfMetric.EVENT_HANDLER.label)
        assertEquals("ink.paint.draw_segment", PerfMetric.PAINT_DRAW_SEGMENT.label)
    }

    @Test
    fun customPrefixAppliesToAllLabels() {
        PerfCounters.prefix = "myapp.ink."
        assertEquals("myapp.ink.pen.kernel_to_paint", PerfMetric.PEN_KERNEL_TO_PAINT.label)
        assertEquals("myapp.ink.event.kernel_to_jvm", PerfMetric.EVENT_KERNEL_TO_JVM.label)
        // Empty prefix is honoured (no namespace at all).
        PerfCounters.prefix = ""
        assertEquals("pen.kernel_to_paint", PerfMetric.PEN_KERNEL_TO_PAINT.label)
    }

    @Test
    fun installedSinkReceivesRecordingsWithFullLabels() {
        val received = mutableListOf<Pair<String, Long>>()
        PerfCounters.sink = PerfSink { label, nanos -> received += label to nanos }

        PerfCounters.recordDirect(PerfMetric.PEN_KERNEL_TO_PAINT, 5_000_000L)
        PerfCounters.time(PerfMetric.EVENT_HANDLER) { /* no-op */ }

        assertEquals(2, received.size)
        assertEquals("ink.pen.kernel_to_paint", received[0].first)
        assertEquals(5_000_000L, received[0].second)
        assertEquals("ink.event.handler", received[1].first)
        assertTrue("non-negative elapsed", received[1].second >= 0L)
    }

    @Test
    fun installedSinkReceivesPrefixedLabels() {
        PerfCounters.prefix = "myapp.ink."
        val received = mutableListOf<String>()
        PerfCounters.sink = PerfSink { label, _ -> received += label }

        PerfCounters.recordDirect(PerfMetric.PAINT_DRAW_SEGMENT, 1_000L)
        assertEquals("myapp.ink.paint.draw_segment", received.single())
    }

    @Test
    fun pollingApisReturnEmptyAfterSinkReplaced() {
        // After installing a custom sink, the default ring buffer never sees
        // recordings — snapshot/get/reset operate against the default sink
        // and so report empty. This is the documented contract: a host that
        // takes ownership of recording also owns its own snapshot.
        PerfCounters.sink = PerfSink { _, _ -> /* swallow */ }
        PerfCounters.recordDirect(PerfMetric.PEN_KERNEL_TO_PAINT, 5_000_000L)

        assertEquals(0L, PerfCounters.get(PerfMetric.PEN_KERNEL_TO_PAINT).count)
        assertTrue(PerfCounters.snapshot().values.all { it.count == 0L })
    }

    @Test
    fun defaultSinkIsBackwardCompatible() {
        // No sink replacement — the existing API contract holds.
        PerfCounters.recordDirect(PerfMetric.PEN_KERNEL_TO_PAINT, 5_000_000L)
        assertEquals(1L, PerfCounters.get(PerfMetric.PEN_KERNEL_TO_PAINT).count)
    }

    @Test
    fun allExpectedMetricsExist() {
        // Smoke-check that the rename didn't drop any metric. If this list
        // changes, update both the enum and docs/metrics.md.
        val baseNames = PerfMetric.entries.map { it.label.removePrefix(PerfCounters.prefix) }.toSet()
        val expected = setOf(
            "pen.kernel_to_paint",
            "pen.kernel_to_jvm",
            "pen.jvm_to_paint",
            "pen.jvm_to_first_move",
            "pen.move_to_paint",
            "event.kernel_to_jvm",
            "event.handler",
            "paint.draw_segment",
            "paint.invalidate_call",
        )
        assertEquals(expected, baseNames)
    }
}
