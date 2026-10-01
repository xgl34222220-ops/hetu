package io.github.xgl34222220.hetu

/** Counter observations, not percentages. A cache hit keeps its original sample time. */
internal data class CpuCounterSample(
    val pid: Int,
    val processTicks: Long,
    val systemTicks: Long,
    val elapsedSeconds: Long,
    val sampledAt: Long,
    val valid: Boolean,
)

internal data class CpuUsageReading(val percent: Float, val sampledAt: Long)

/** Requires two fresh observations from one process and breaks continuity after missing data. */
internal class CpuSampleTracker {
    private var previous: CpuCounterSample? = null
    private var reading: CpuUsageReading? = null

    fun clear(): CpuUsageReading? {
        previous = null
        reading = null
        return null
    }

    fun update(sample: CpuCounterSample?, running: Boolean, cores: Int): CpuUsageReading? {
        if (!running || sample == null || !sample.valid || sample.pid <= 0 || sample.sampledAt <= 0L ||
            sample.processTicks < 0L || sample.systemTicks <= 0L) return clear()
        val before = previous
        if (sample == before) return reading // Cached observation, never a second measurement.
        previous = sample
        if (before == null || before.pid != sample.pid || sample.elapsedSeconds < before.elapsedSeconds ||
            sample.processTicks < before.processTicks || sample.sampledAt <= before.sampledAt) {
            reading = null
            return null
        }
        if (sample.systemTicks <= before.systemTicks) return clear()
        reading = CpuUsageReading(
            ((sample.processTicks - before.processTicks).toDouble() /
                (sample.systemTicks - before.systemTicks).toDouble() * 100.0 * cores.coerceAtLeast(1))
                .toFloat().coerceIn(0f, 100f),
            sample.sampledAt,
        )
        return reading
    }
}

internal fun resourceSampleSegments(samples: List<Long?>): List<List<IndexedValue<Long>>> = buildList {
    var segment = mutableListOf<IndexedValue<Long>>()
    samples.forEachIndexed { index, value ->
        if (value != null) segment.add(IndexedValue(index, value))
        else if (segment.isNotEmpty()) { add(segment); segment = mutableListOf() }
    }
    if (segment.isNotEmpty()) add(segment)
}
