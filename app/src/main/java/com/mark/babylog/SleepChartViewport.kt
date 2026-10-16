package com.mark.babylog

/** Shared elapsed-day window; zoom keeps the time beneath the fingers stationary. */
internal data class SleepChartViewport(val scale: Float = 1f, val start: Float = 0f) {
    val end: Float
        get() = start + 1f / scale

    fun fraction(dayFraction: Float): Float = ((dayFraction - start) * scale).coerceIn(0f, 1f)

    fun transform(zoom: Float, centroid: Float, pan: Float): SleepChartViewport {
        val nextScale = (scale * zoom).coerceIn(1f, 8f)
        val nextStart = start + centroid / scale - (centroid + pan) / nextScale
        return SleepChartViewport(nextScale, nextStart.coerceIn(0f, 1f - 1f / nextScale))
    }
}

/** One tick list drives both labels and grid lines, including during zoom and DST days. */
internal fun sleepChartTicks(
    axis: com.mark.babylog.sleep.SleepDayAxis,
    viewport: SleepChartViewport,
): List<Long> {
    val visibleHours = axis.duration.toDouble() / 3_600_000 / viewport.scale
    val step = listOf(1, 2, 3, 6).firstOrNull { it >= visibleHours / 4 } ?: 6
    return ((axis.start until axis.end step 3_600_000L).filter {
            java.time.Instant.ofEpochMilli(it).atZone(axis.zone).hour % step == 0
        } + axis.end)
        .filter {
            val fraction = axis.fraction(it)
            fraction >= viewport.start - 0.00001f && fraction <= viewport.end + 0.00001f
        }
}
