package com.mark.babylog

import com.mark.babylog.data.*
import com.mark.babylog.widget.dailySleepSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSummaryTest {
    private val start = 86_400_000L
    private val now = start + 3_600_000
    private fun sleep(from: Long, to: Long? = null) = BabyEvent(
        type = EventType.SLEEP, detail = "BACK", startedAt = from,
        endedAt = to, sleepRecordVersion = 1
    )

    @Test fun clipsOvernightSleepAndIncludesActiveSleep() {
        assertEquals("1 ч 0 мин · эпизодов: 2", dailySleepSummary(
            listOf(sleep(start - 3_600_000, start + 1_800_000),
                sleep(start + 1_800_000)), start, now))
    }

    @Test fun ignoresLegacyDeletedConflictedAndFutureRecords() {
        val record = sleep(start, now)
        assertEquals("Пока нет записей", dailySleepSummary(listOf(
            record.copy(sleepRecordVersion = 0),
            record.copy(deletedAt = now),
            record.copy(syncState = SyncState.CONFLICT),
            sleep(now + 60_000),
            sleep(start - 120_000, start - 60_000)
        ), start, now))
    }

    @Test fun neverCountsBeyondNow() {
        assertEquals("1 ч 0 мин · эпизодов: 1",
            dailySleepSummary(listOf(sleep(start, now + 60_000)), start, now))
    }
}
