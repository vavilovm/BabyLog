package com.mark.babylog

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepChartViewportTest {
    @Test
    fun labelsAndGridShareWholeHourTicksAtEveryZoomAndAcrossDst() {
        val zone = java.time.ZoneId.of("Europe/Amsterdam")
        listOf(
                java.time.LocalDate.of(2026, 10, 15),
                java.time.LocalDate.of(2026, 10, 25),
                java.time.LocalDate.of(2026, 3, 29),
            )
            .forEach { day ->
                val axis = com.mark.babylog.sleep.SleepDayAxis(day, zone)
                listOf(
                        SleepChartViewport(),
                        SleepChartViewport(2f, 0.13f),
                        SleepChartViewport(8f, 0.5f),
                    )
                    .forEach { viewport ->
                        val ticks = sleepChartTicks(axis, viewport)
                        org.junit.Assert.assertTrue(ticks.isNotEmpty())
                        ticks.forEach { time ->
                            org.junit.Assert.assertEquals(
                                0,
                                java.time.Instant.ofEpochMilli(time).atZone(zone).minute,
                            )
                            org.junit.Assert.assertTrue(
                                axis.fraction(time) >= viewport.start - 0.00001f
                            )
                            org.junit.Assert.assertTrue(
                                axis.fraction(time) <= viewport.end + 0.00001f
                            )
                        }
                    }
            }
    }

    @Test
    fun pinchKeepsSelectedTimeUnderFingersWhilePanning() {
        val original = SleepChartViewport()
        val zoomed = original.transform(4f, 0.65f, 0.1f)
        assertEquals(0.75f, zoomed.fraction(0.65f), 0.0001f)
        val panned = zoomed.transform(1f, 0.5f, -0.1f)
        assertEquals(0.65f, panned.fraction(0.65f), 0.0001f)
    }

    @Test
    fun windowStaysInsideDayAndZoomOutRestoresWholeDay() {
        val zoomed = SleepChartViewport().transform(100f, 1f, 0f)
        assertEquals(8f, zoomed.scale, 0f)
        assertEquals(1f, zoomed.end, 0f)
        val left = zoomed.transform(1f, 0.5f, 100f)
        assertEquals(0f, left.start, 0f)
        val reset = left.transform(0.001f, 0.5f, -100f)
        assertEquals(SleepChartViewport(), reset)
    }

    @Test
    fun partiallyVisibleSleepsAreClippedToWindow() {
        val window = SleepChartViewport(4f, 0.5f)
        assertEquals(0f, window.fraction(0.25f), 0f)
        assertEquals(0.5f, window.fraction(0.625f), 0f)
        assertEquals(1f, window.fraction(0.9f), 0f)
    }
}
