package com.mark.babylog

import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyStatisticsTest{
    private val day=LocalDate.of(2026,7,17)
    private val zone=ZoneId.of("UTC")
    private fun at(hour:Int,minute:Int=0)=day.atTime(hour,minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun consecutiveBreastSidesBecomeOneFeedingSession(){
        val events=listOf(
            BabyEvent(1,EventType.FEEDING,"LEFT",at(8),at(8,12)),
            BabyEvent(2,EventType.FEEDING,"RIGHT",at(8,12),at(8,22)),
            BabyEvent(3,EventType.FEEDING,"BOTTLE:120",at(11),at(11)),
            BabyEvent(4,EventType.FEEDING,"LEFT",at(14),at(14,20)),
            BabyEvent(5,EventType.SLEEP,"LEFT",at(9),at(9)),
            BabyEvent(6,EventType.SLEEP,"LEFT",at(12),at(12)),
            BabyEvent(7,EventType.SLEEP,"RIGHT",at(15),at(15)),
            BabyEvent(8,EventType.PUMPING,"LEFT:90",at(10),at(10)),
            BabyEvent(9,EventType.PUMPING,"RIGHT:110",at(16),at(16))
        )

        val result=dailySummary(events,day,at(18),zone)

        assertEquals(3,result.feedingCount)
        assertEquals(3*60*60_000L,result.averageFeedingIntervalMs)
        assertEquals(21*60_000L,result.averageFeedingDurationMs)
        assertEquals(42*60_000L,result.totalBreastfeedingMs)
        assertEquals(32*60_000L,result.leftBreastfeedingMs)
        assertEquals(10*60_000L,result.rightBreastfeedingMs)
        assertEquals(1,result.bottleCount)
        assertEquals(120,result.bottleVolumeMl)
        assertEquals(2,result.sleepLeftCount)
        assertEquals(1,result.sleepRightCount)
        assertEquals(2,result.pumpingCount)
        assertEquals(200,result.pumpingVolumeMl)
    }

    @Test fun breastSidesMoreThanThirtyMinutesApartAreSeparateFeedings(){
        val result=dailySummary(listOf(
            BabyEvent(1,EventType.FEEDING,"LEFT",at(8),at(8,10)),
            BabyEvent(2,EventType.FEEDING,"RIGHT",at(8,41),at(8,51))
        ),day,at(12),zone)

        assertEquals(2,result.feedingCount)
        assertEquals(41*60_000L,result.averageFeedingIntervalMs)
        assertEquals(10*60_000L,result.averageFeedingDurationMs)
    }
}
