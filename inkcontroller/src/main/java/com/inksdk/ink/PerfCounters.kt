package com.inksdk.ink

/**
 * Performance metrics recorded by the ink pipeline. Each maps to a pre-
 * allocated ring buffer in [DefaultSink] indexed by [ordinal] — no HashMap
 * lookup on the hot path when the default sink is in use.
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
 * Sink for ink-pipeline timing measurements. Each [record] call delivers
 * one labelled timing sample.
 *
 * The default sink ([DefaultSink]) is an in-process ring buffer served by
 * [PerfCounters.snapshot]/[PerfCounters.get]/[PerfCounters.reset]. Hosts
 * that already have a perf system can replace [PerfCounters.sink] at startup
 * to route every measurement into it — no polling, no enum coupling, no
 * separate ring buffer.
 *
 * `metricLabel` is the full [PerfMetric.label] (with [PerfCounters.prefix]
 * applied), so a host sink can route purely by string and never imports
 * the [PerfMetric] enum.
 */
fun interface PerfSink {
    fun record(metricLabel: String, elapsedNanos: Long)
}

/**
 * Zero-allocation hot-path performance counters.
 *
 * By default routes to [DefaultSink] (a ring-buffer per [PerfMetric] served
 * by [snapshot]/[get]/[reset]). Replace [sink] at startup to route into a
 * host perf system instead — after replacement, the polling APIs return
 * empty results because nothing populates the default ring buffer.
 */
object PerfCounters {

    /** Prefix prepended to every [PerfMetric.label]. Defaults to `"ink."`.
     *  Set once at startup; not safe to mutate while metrics are being read. */
    @Volatile var prefix: String = "ink."

    /** Where every measurement goes. Defaults to [DefaultSink]; hosts can
     *  replace at startup to route into their own perf system. After
     *  replacement, [snapshot]/[get]/[reset] become no-op-ish (they only
     *  see the default ring buffer, which no longer receives writes). */
    @Volatile var sink: PerfSink = DefaultSink

    /** Time a block and record the elapsed nanos. Returns the block result. */
    inline fun <T> time(metric: PerfMetric, block: () -> T): T {
        val t0 = System.nanoTime()
        return try {
            block()
        } finally {
            recordDirect(metric, System.nanoTime() - t0)
        }
    }

    /** Record an externally-measured nanos value (e.g. cross-clock latency). */
    fun recordDirect(metric: PerfMetric, elapsedNanos: Long) {
        // Fast path: when the default sink is installed, skip the label
        // round-trip and write to the ordinal-indexed ring directly.
        val s = sink
        if (s === DefaultSink) {
            DefaultSink.recordTyped(metric, elapsedNanos)
        } else {
            s.record(metric.label, elapsedNanos)
        }
    }

    fun get(metric: PerfMetric): CounterSnapshot = DefaultSink.get(metric)

    fun snapshot(): Map<PerfMetric, CounterSnapshot> = DefaultSink.snapshot()

    fun reset() = DefaultSink.reset()
}

/**
 * Default sink — an ordinal-indexed ring buffer per [PerfMetric].
 *
 * Hosts that don't replace [PerfCounters.sink] consume it via
 * [PerfCounters.snapshot]/[PerfCounters.get]/[PerfCounters.reset]. Hosts
 * that *do* install a custom sink leave this object empty.
 */
object DefaultSink : PerfSink {

    private const val WINDOW_SIZE = 200

    private val counters = Array(PerfMetric.entries.size) { RingCounter(WINDOW_SIZE) }

    private val labelToMetric: Map<String, PerfMetric> by lazy {
        PerfMetric.entries.associateBy { it.label }
    }

    /** [PerfSink] entry point — a host that installs another sink AND
     *  also calls back into [DefaultSink.record] (for tests, mostly) ends
     *  up here; we resolve the label back to a [PerfMetric] if known. */
    override fun record(metricLabel: String, elapsedNanos: Long) {
        labelToMetric[metricLabel]?.let { counters[it.ordinal].record(elapsedNanos) }
    }

    /** Hot path for the in-process default route. Skips the string lookup. */
    internal fun recordTyped(metric: PerfMetric, elapsedNanos: Long) {
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
