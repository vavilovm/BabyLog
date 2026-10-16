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
