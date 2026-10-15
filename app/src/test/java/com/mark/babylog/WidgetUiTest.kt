package com.mark.babylog

import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import com.mark.babylog.widget.feedingWidgetUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUiTest {
    @Test
    fun finishedFeedingUsesEndTime() {
        val now = 1_000_000L
        val ui =
            feedingWidgetUi(
                BabyEvent(1, EventType.FEEDING, "RIGHT", now - 120_000, now - 30_000),
                now,
            )
        assertTrue(ui.status.contains("0:30 назад"))
    }

    @Test
    fun feedingButtonsNeverTurnIntoStop() {
        val now = 1_000_000L
        val ui =
            feedingWidgetUi(
                BabyEvent(1, EventType.FEEDING, "LEFT", now - 42_000, now - 42_000),
                now,
            )
        assertEquals(listOf("LEFT", "RIGHT", "BOTTLE"), ui.buttons.map { it.command })
    }

    @Test
    fun sleepWidgetUsesExplicitStartAndWakeActions() {
        val now = 1_000_000L
        val empty = com.mark.babylog.widget.sleepWidgetUi(null, now)
        assertEquals(listOf("SLEEP"), empty.buttons.map { it.command })
        val active =
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = now - 120_000,
                sleepRecordVersion = 1,
            )
        val asleep = com.mark.babylog.widget.sleepWidgetUi(active, now)
        assertEquals(listOf("WAKE"), asleep.buttons.map { it.command })
        assertTrue(asleep.status.contains("Спит"))
        val awake = com.mark.babylog.widget.sleepWidgetUi(active.copy(endedAt = now - 60_000), now)
        assertEquals(listOf("SLEEP"), awake.buttons.map { it.command })
        assertTrue(awake.status.contains("Бодрствует"))
    }
}
