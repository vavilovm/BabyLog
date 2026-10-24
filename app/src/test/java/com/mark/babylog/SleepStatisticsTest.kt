package com.mark.babylog

import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import com.mark.babylog.sleep.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SleepStatisticsTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val day = LocalDate.of(2026, 10, 24)

    private fun at(date: LocalDate, hour: Int) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sleep(start: Long, end: Long?) =
        BabyEvent(
            type = EventType.SLEEP,
            detail = "BACK",
            startedAt = start,
            endedAt = end,
            sleepRecordVersion = 1,
        )

    @Test
    fun overnightSleepKeepsItsTypeAndIsSplitBetweenDates() {
        val sessions =
            listOf(sleep(at(day.minusDays(1), 21), at(day, 7)), sleep(at(day, 12), at(day, 14)))
        val today = sleepDayStatistics(sessions, day, at(day, 23), zone)
        assertEquals(7 * 3_600_000L, today.nighttimeMs)
        assertEquals(2 * 3_600_000L, today.daytimeMs)
        assertEquals(9 * 3_600_000L, today.totalMs)
        assertEquals(1, today.naps)
        assertEquals(2 * 3_600_000L, today.averageNapMs)
        assertEquals(7 * 3_600_000L, today.longestMs)
        val yesterday = sleepDayStatistics(sessions, day.minusDays(1), at(day, 23), zone)
        assertEquals(3 * 3_600_000L, yesterday.nighttimeMs)
    }

    @Test
    fun liveNapStopsAtNowAndFutureSleepDoesNotContribute() {
        val result =
            sleepDayStatistics(
                listOf(sleep(at(day, 12), null), sleep(at(day, 15), at(day, 16))),
                day,
                at(day, 13),
                zone,
            )
        assertEquals(3_600_000L, result.daytimeMs)
        assertEquals(1, result.naps)
        assertEquals(3_600_000L, result.averageNapMs)
    }

    @Test
    fun dayBoundariesFollowDstAndPreserveAllElapsedTime() {
        val fallback = LocalDate.of(2026, 10, 25)
        val session = sleep(at(fallback, 0), at(fallback.plusDays(1), 0))
        val result =
            sleepDayStatistics(listOf(session), fallback, at(fallback.plusDays(1), 1), zone)
        assertEquals(25 * 3_600_000L, result.totalMs)
        assertEquals(25 * 3_600_000L, result.nighttimeMs)
    }

    @Test
    fun averagesIgnoreMissingDaysAndUnfinishedToday() {
        val yesterday = day.minusDays(1)
        val session = sleep(at(yesterday, 12), at(yesterday, 14))
        val days =
            (0..6).map {
                sleepDayStatistics(listOf(session), day.minusDays(it.toLong()), at(day, 13), zone)
            }
        val average = requireNotNull(sleepPeriodAverage(days, day))
        assertEquals(1, average.days)
        assertEquals(2 * 3_600_000L, average.totalMs)
        assertNull(sleepPeriodAverage(days.filter { !it.hasRecords }, day))
        val today = sleepDayStatistics(listOf(sleep(at(day, 12), null)), day, at(day, 13), zone)
        assertNull(sleepPeriodAverage(listOf(today), day))
    }
}
