package com.mark.babylog

import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DaytimeSleepTotalTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 24)

    private fun time(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sleep(start: Long, end: Long?) =
        BabyEvent(
            type = EventType.SLEEP,
            detail = "BACK",
            startedAt = start,
            endedAt = end,
            sleepRecordVersion = 1,
        )

    @Test
    fun sumsNapsForSelectedLocalDayAndExcludesNightAndOtherDays() {
        val sessions =
            listOf(
                sleep(time(6), time(7)),
                sleep(time(12), time(14)),
                sleep(time(18), time(20)),
                sleep(time(5), time(6)),
                sleep(time(10) - 86_400_000, time(11) - 86_400_000),
            )
        assertEquals(3 * 3_600_000L, daytimeSleepTotal(sessions, day, time(23), zone))
        assertEquals(3_600_000L, daytimeSleepTotal(sessions, day.minusDays(1), time(23), zone))
    }

    @Test
    fun includesElapsedActiveNapAndClampsAtNowAndDayBoundary() {
        assertEquals(
            3_600_000L,
            daytimeSleepTotal(listOf(sleep(time(12), null)), day, time(13), zone),
        )
        assertEquals(0L, daytimeSleepTotal(emptyList(), day, time(13), zone))
        assertEquals(0L, daytimeSleepTotal(listOf(sleep(time(14), null)), day, time(13), zone))
        assertEquals(
            7 * 3_600_000L,
            daytimeSleepTotal(
                listOf(sleep(time(17), time(23) + 3_600_000 * 3)),
                day,
                time(23) + 3_600_000 * 4,
                zone,
            ),
        )
    }
}
