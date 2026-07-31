package com.mark.babylog

import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import com.mark.babylog.data.FeedingKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLogicTest {
    @Test
    fun pumpingDefaultIsOppositeOfLatestBreastfeeding() {
        val now = 1_000L
        assertEquals(
            FeedingKind.LEFT,
            defaultPumpingSide(listOf(BabyEvent(1, EventType.FEEDING, "RIGHT", now, now))),
        )
        assertEquals(
            FeedingKind.RIGHT,
            defaultPumpingSide(listOf(BabyEvent(1, EventType.FEEDING, "LEFT", now, now))),
        )
    }

    @Test
    fun historyFilterAndMinuteMovementWork() {
        val events =
            listOf(
                BabyEvent(1, EventType.PUMPING, "LEFT:90", 1, 1),
                BabyEvent(2, EventType.FEEDING, "RIGHT", 2, 2),
            )
        assertEquals(listOf(1L), historyEvents(events, true).map { it.id })
        assertEquals(360_000L, moveMinutes(60_000, 5))
    }
}
