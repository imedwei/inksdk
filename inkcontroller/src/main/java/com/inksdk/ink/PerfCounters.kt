package com.inksdk.ink

/**
 * Performance metrics recorded by the ink pipeline. Each maps to a pre-
 * allocated ring buffer in [PerfCounters] indexed by [ordinal] — no HashMap
 * lookup on the hot path.
 *
 * The set is intentionally narrow: this library focuses on the daemon ink
 * path's down-to-paint latency. Hosts are free to record their own metrics
 * via [PerfCounters.recordDirect].
 */
enum class PerfMetric(val label: String) {
    /** Canvas.drawLine into the daemon ION buffer (per MOVE event). */
    INK_DAEMON_DRAW_LINE("ink.daemon.draw_line"),

    /** inValidate(rect, mode) round-trip into the daemon. */
    INK_DAEMON_INVALIDATE("ink.daemon.invalidate"),

    /** Whole InputProxy.invoke hot path (one event from binder). */
    INK_DAEMON_INVOKE_TOTAL("ink.daemon.invoke_total"),

    /** ACTION_DOWN → first inValidate. End-to-end first-paint latency. */
    INK_DAEMON_DOWN_TO_PAINT("ink.daemon.down_to_paint"),

    /** ACTION_DOWN → first ACTION_MOVE arrival. Kernel + daemon + binder
     *  delivery + how long it took the user to start moving. */
    INK_DAEMON_DOWN_TO_FIRST_MOVE("ink.daemon.down_to_first_move"),

    /** First ACTION_MOVE arrival → first inValidate. Pure JVM-side processing. */
    INK_DAEMON_FIRST_MOVE_TO_PAINT("ink.daemon.first_move_to_paint"),

    /** Daemon CLOCK_REALTIME timestamp → our InputProxy.invoke entry.
     *  Kernel input-event read → binder → JVM dispatch delay. */
    INK_DAEMON_DISPATCH_LATENCY("ink.daemon.dispatch_latency"),
}

/**
 * Zero-allocation hot-path performance counters.
 *
 * Each [PerfMetric] gets a ring buffer of the last [WINDOW_SIZE] timings.
 * [recordDirect] is one synchronized array write — safe to call from any
 * thread (binder thread, UI thread, etc).
 *
 * Percentiles are computed lazily by [snapshot] / [get].
 */
object PerfCounters {

    private const val WINDOW_SIZE = 200

    @PublishedApi
    internal val counters = Array(PerfMetric.entries.size) { RingCounter(WINDOW_SIZE) }

    /** Time a block and record the elapsed nanos. Returns the block result. */
    inline fun <T> time(metric: PerfMetric, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        counters[metric.ordinal].record(System.nanoTime() - start)
        return result
    }

    /** Record an externally-measured nanos value (e.g. cross-clock latency). */
    fun recordDirect(metric: PerfMetric, elapsedNanos: Long) {
        counters[metric.ordinal].record(elapsedNanos)
    }

    fun get(metric: PerfMetric): CounterSnapshot = counters[metric.ordinal].snapshot()

    fun snapshot(): Map<PerfMetric, CounterSnapshot> =
        PerfMetric.entries.associateWith { counters[it.ordinal].snapshot() }

    fun reset() {
        for (counter in counters) counter.reset()
    }
}

data class TimingSample(val elapsedMs: Long, val timestampMs: Long)

data class CounterSnapshot(
    val count: Long,
    val lastMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val samples: List<TimingSample>,
)

@PublishedApi
internal class RingCounter(private val capacity: Int) {

    private val elapsedNanos = LongArray(capacity)
    private val timestampsMs = LongArray(capacity)
    private var writeIdx = 0
    private var totalCount = 0L

    @Synchronized
    fun record(elapsedNanos: Long) {
        val idx = writeIdx % capacity
        this.elapsedNanos[idx] = elapsedNanos
        this.timestampsMs[idx] = System.currentTimeMillis()
        writeIdx++
        totalCount++
    }

    @Synchronized
    fun snapshot(): CounterSnapshot {
        if (totalCount == 0L) return CounterSnapshot(0, 0, 0, 0, 0, emptyList())

        val size = minOf(totalCount.toInt(), capacity)
        val startIdx = if (totalCount <= capacity) 0 else writeIdx % capacity

        val samples = ArrayList<TimingSample>(size)
        val sortedMs = LongArray(size)
        for (i in 0 until size) {
            val idx = (startIdx + i) % capacity
            val ms = elapsedNanos[idx] / 1_000_000
            samples.add(TimingSample(ms, timestampsMs[idx]))
            sortedMs[i] = ms
        }
        sortedMs.sort()

        return CounterSnapshot(
            count = totalCount,
            lastMs = samples.last().elapsedMs,
            p50Ms = sortedMs[size / 2],
            p95Ms = sortedMs[(size * 95L / 100).toInt().coerceAtMost(size - 1)],
            maxMs = sortedMs[size - 1],
            samples = samples,
        )
    }

    @Synchronized
    fun reset() {
        elapsedNanos.fill(0)
        timestampsMs.fill(0)
        writeIdx = 0
        totalCount = 0
    }
}
