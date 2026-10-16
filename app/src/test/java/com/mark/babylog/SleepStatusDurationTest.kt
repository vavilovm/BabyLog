package com.mark.babylog

import com.mark.babylog.sleep.CurrentSleepEstimate
import com.mark.babylog.sleep.ForecastSleep
import java.time.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepStatusDurationTest {
    @Test
    fun showsSecondsUntilOneHour() {
        assertEquals("0 мин 0 с", sleepStatusDuration(-1))
        assertEquals("0 мин 0 с", sleepStatusDuration(999))
        assertEquals("0 мин 1 с", sleepStatusDuration(1_000))
        assertEquals("0 мин 59 с", sleepStatusDuration(59_999))
        assertEquals("1 мин 0 с", sleepStatusDuration(60_000))
        assertEquals("59 мин 59 с", sleepStatusDuration(3_599_999))
    }

    @Test
    fun showsHoursAndMinutesFromOneHour() {
        assertEquals("1 ч 0 мин", sleepStatusDuration(3_600_000))
        assertEquals("2 ч 3 мин", sleepStatusDuration(7_380_000))
    }

    @Test
    fun chartAppendsTomorrowOnlyWhenForecastOverlapsIt() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val today = LocalDate.of(2026, 10, 15)
        val now = today.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(7, sleepChartDays(emptyList(), now, zone, 7).size)
        assertEquals(7, sleepChartDays(listOf(ForecastSleep(now, midnight, 30)), now, zone, 7).size)
        val days =
            sleepChartDays(listOf(ForecastSleep(now, midnight + 8 * 3_600_000L, 30)), now, zone, 7)
        assertEquals(8, days.size)
        assertEquals(today.minusDays(6), days.first())
        assertEquals(today.plusDays(1), days.last())
    }

    @Test
    fun hiddenUpperWakeBoundaryDoesNotAddEmptyTomorrowRow() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val today = LocalDate.of(2026, 10, 15)
        val now = today.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val range =
            CurrentSleepEstimate(
                now,
                now + 3_600_000L,
                now + 2 * 3_600_000L,
                now + 6 * 3_600_000L,
                3,
                true,
            )
        val days =
            sleepChartDays(listOf(ForecastSleep(now, range.expectedWake, 30)), now, zone, 7, range)
        assertEquals(7, days.size)
        assertEquals(today, days.last())
        val tomorrowWake =
            range.copy(
                expectedWake = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        assertEquals(
            today.plusDays(1),
            sleepChartDays(emptyList(), now, zone, 7, tomorrowWake).last(),
        )
    }
}
