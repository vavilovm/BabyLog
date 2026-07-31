package com.mark.babylog

import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import com.mark.babylog.sync.AppSurfaceSync
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBehaviorTest {
    @Test
    fun chronometerCountsFromCompletedFeedingEndTime() {
        val event =
            BabyEvent(
                id = 1,
                type = EventType.FEEDING,
                detail = "LEFT",
                startedAt = 10_000,
                endedAt = 40_000,
            )

        assertEquals(
            940_000,
            AppSurfaceSync.feedingChronometerBase(
                lastFeed = event,
                wallClockNow = 100_000,
                elapsedRealtimeNow = 1_000_000,
            ),
        )
    }

    @Test
    fun futureWallClockValuesDoNotCreateNegativeElapsedDuration() {
        val event =
            BabyEvent(
                id = 1,
                type = EventType.FEEDING,
                detail = "RIGHT",
                startedAt = 110_000,
                endedAt = 110_000,
            )

        assertEquals(1_000_000, AppSurfaceSync.feedingChronometerBase(event, 100_000, 1_000_000))
    }
}
