package com.mark.babylog

import com.mark.babylog.sleep.SleepDayAxis
import com.mark.babylog.sleep.resolveSleepLocalTime
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SleepDayAxisTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    @Test
    fun repeatedHourHasDistinctForwardCoordinates() {
        val axis = SleepDayAxis(LocalDate.of(2026, 10, 25), zone)
        val local = LocalDateTime.of(2026, 10, 25, 2, 30)
        val first = local.atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = local.atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertTrue(axis.changesOffset)
        assertEquals(25 * 3_600_000L, axis.duration)
        assertEquals(1f / 25, axis.fraction(second) - axis.fraction(first), 0.00001f)
        assertEquals(0f, axis.fraction(axis.start - 1), 0f)
        assertEquals(1f, axis.fraction(axis.end + 1), 0f)
    }

    @Test
    fun springGapDoesNotInventAnHourOfSleep() {
        val axis = SleepDayAxis(LocalDate.of(2026, 3, 29), zone)
        val first = axis.day.atTime(1, 30).atZone(zone).toInstant().toEpochMilli()
        val second = axis.day.atTime(3, 30).atZone(zone).toInstant().toEpochMilli()
        assertTrue(axis.changesOffset)
        assertEquals(23 * 3_600_000L, axis.duration)
        assertEquals(1f / 23, axis.fraction(second) - axis.fraction(first), 0.00001f)
    }

    @Test
    fun editingRepeatedHourKeepsSelectedOccurrence() {
        val local = LocalDateTime.of(2026, 10, 25, 2, 30)
        val earlier = resolveSleepLocalTime(local, zone, ZoneOffset.ofHours(2))
        val later = resolveSleepLocalTime(local, zone, ZoneOffset.ofHours(1))
        assertEquals(3_600_000L, later - earlier)
        assertEquals(
            later + 60_000,
            resolveSleepLocalTime(local.plusMinutes(1), zone, ZoneOffset.ofHours(1)),
        )
        val gap =
            resolveSleepLocalTime(LocalDateTime.of(2026, 3, 29, 2, 30), zone, ZoneOffset.ofHours(1))
        assertEquals(3, Instant.ofEpochMilli(gap).atZone(zone).hour)
    }

    @Test
    fun ordinaryDaysKeepTheirExistingClockPositions() {
        val axis = SleepDayAxis(LocalDate.of(2026, 10, 16), zone)
        assertFalse(axis.changesOffset)
        assertEquals(24 * 3_600_000L, axis.duration)
        assertEquals(
            0.25f,
            axis.fraction(axis.day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()),
            0f,
        )
    }
}
