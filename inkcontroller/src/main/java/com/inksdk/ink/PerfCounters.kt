package com.inksdk.ink

/**
 * Performance metrics recorded by the ink pipeline. Each maps to a pre-
 * allocated ring buffer in [PerfCounters] indexed by [ordinal] — no HashMap
 * lookup on the hot path.
 *
 * Naming follows three tiers by sample rate:
 *  - `pen.*`   — one sample per stroke (perceived first-paint latencies)
 *  - `event.*` — one sample per binder input event (dispatch overhead)
 *  - `paint.*` — one sample per draw segment (per MOVE)
 *
 * See `docs/metrics.md` and `docs/metrics-timeline.svg` for the timeline
 * diagram and what each metric covers.
 *
 * The [label] is the metric's full name including [PerfCounters.prefix],
 * so hosts can repurpose the names by setting `PerfCounters.prefix = "..."`
 * once at startup.
 */
enum class PerfMetric(private val baseName: String) {
    /** Wall-clock from kernel pen-down (daemon CLOCK_REALTIME ts) to first
     *  inValidate returns. The headline first-paint metric. */
    PEN_KERNEL_TO_PAINT("pen.kernel_to_paint"),

    /** Wall-clock from kernel pen-down to DOWN event arriving in
     *  InputProxy.invoke. DOWN-only subset of [EVENT_KERNEL_TO_JVM]. */
    PEN_KERNEL_TO_JVM("pen.kernel_to_jvm"),

    /** JVM-monotonic from DOWN landing in JVM to first inValidate returns. */
    PEN_JVM_TO_PAINT("pen.jvm_to_paint"),

    /** JVM-monotonic from DOWN landing in JVM to first MOVE landing in
     *  JVM. Includes user pen-movement speed — not pure stack overhead. */
    PEN_JVM_TO_FIRST_MOVE("pen.jvm_to_first_move"),

    /** First MOVE landing in JVM → first inValidate returns. Pure
     *  JVM-side processing without user-input contamination. */
    PEN_MOVE_TO_PAINT("pen.move_to_paint"),

    /** Wall-clock from kernel input-event read (daemon CLOCK_REALTIME)
     *  to InputProxy.invoke entry, recorded for every binder event. */
    EVENT_KERNEL_TO_JVM("event.kernel_to_jvm"),

    /** Whole InputProxy.invoke wall time, recorded for every event. */
    EVENT_HANDLER("event.handler"),

    /** Canvas.drawLine into the daemon ION buffer, recorded per MOVE. */
    PAINT_DRAW_SEGMENT("paint.draw_segment"),

    /** inValidate(rect, mode) round-trip into the daemon, per call. */
    PAINT_INVALIDATE_CALL("paint.invalidate_call"),
    ;

    /** Full metric name including the runtime [PerfCounters.prefix]. */
    val label: String get() = "${PerfCounters.prefix}$baseName"
}

/**
 * Zero-allocation hot-path performance counters.
 *
 * Each [PerfMetric] gets a ring buffer of the last [WINDOW_SIZE] timings.
 * [recordDirect] is one synchronized array write — safe to call from any
 * thread (binder thread, UI thread, etc).
 *
 * Percentiles are computed lazily by [snapshot] / [get].
 *
 * Hosts that want to merge these counters into their own perf system can
 * override the [prefix] once at startup (e.g. `PerfCounters.prefix = "myapp.ink."`).
 */
object PerfCounters {

    private const val WINDOW_SIZE = 200

    /** Prefix prepended to every [PerfMetric.label]. Defaults to `"ink."`.
     *  Set once at startup; not safe to mutate while metrics are being read. */
    @Volatile var prefix: String = "ink."

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
