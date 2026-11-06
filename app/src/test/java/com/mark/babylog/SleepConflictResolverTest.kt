package com.mark.babylog

import com.mark.babylog.data.*
import org.junit.Assert.*
import org.junit.Test

class SleepConflictResolverTest {
    private fun sleep(id: String, start: Long, end: Long? = null) =
        BabyEvent(
            remoteId = id,
            type = EventType.SLEEP,
            detail = "BACK",
            startedAt = start,
            endedAt = end,
            sleepRecordVersion = 1,
            syncState = SyncState.CONFLICT,
        )

    @Test
    fun multipleOpenStartsUseEarliestStartAndStableIdentity() {
        val records = listOf(sleep("z", 100), sleep("a", 120), sleep("b", 110))
        val result = SleepConflictResolver.resolve(records, 1000).single()
        assertEquals(100L, result.merged.startedAt)
        assertEquals("a", result.merged.remoteId)
        assertNull(result.merged.endedAt)
        assertEquals(2, result.duplicates.size)
        assertEquals(result, SleepConflictResolver.resolve(records.reversed(), 1000).single())
    }

    @Test
    fun overlappingCompletedAndOpenRecordsUseEarliestKnownWake() {
        val result =
            SleepConflictResolver.resolve(
                    listOf(sleep("a", 100, 250), sleep("b", 110, 200), sleep("c", 120)),
                    1000,
                )
                .single()
        assertEquals(100L, result.merged.startedAt)
        assertEquals(200L, result.merged.endedAt)
    }

    @Test
    fun separateOrTouchingWindowsAreNotMerged() {
        assertTrue(
            SleepConflictResolver.resolve(
                    listOf(sleep("a", 100, 200), sleep("b", 200, 300), sleep("c", 400)),
                    1000,
                )
                .isEmpty()
        )
    }

    @Test
    fun ambiguousChainCannotSwallowASecondSleep() {
        assertTrue(
            SleepConflictResolver.resolve(
                    listOf(sleep("a", 100, 200), sleep("b", 150, 350), sleep("c", 300, 400)),
                    1000,
                )
                .isEmpty()
        )
    }

    @Test
    fun invalidLegacyDeletedAndFutureRecordsArePreserved() {
        val records =
            listOf(
                sleep("a", 100),
                sleep("invalid", 100, 90),
                sleep("legacy", 100).copy(sleepRecordVersion = 0),
                sleep("deleted", 100).copy(deletedAt = 200),
                sleep("future", 2000),
            )
        assertTrue(SleepConflictResolver.resolve(records, 1000).isEmpty())
    }

    @Test
    fun aKnownWakeBoundsAnOfflineOpenStartBeforeTheNextNap() {
        val projected =
            SleepConflictResolver.project(
                listOf(sleep("a", 100), sleep("b", 110, 200), sleep("c", 300)),
                1000,
            )
        assertEquals(listOf(100L, 300L), projected.map { it.startedAt })
        assertEquals(200L, projected.first().endedAt)
        assertNull(projected.last().endedAt)
    }

    @Test
    fun lateOfflineWakeCanStillMoveMergedWakeEarlier() {
        val original = listOf(sleep("a", 100, 250), sleep("b", 110))
        assertEquals(250L, SleepConflictResolver.project(original, 1000).single().endedAt)
        val late = original.map { if (it.remoteId == "b") it.copy(endedAt = 200) else it }
        val merged = SleepConflictResolver.project(late, 1000).single()
        assertEquals(200L, merged.endedAt)
        assertEquals(SyncState.SYNCED, merged.syncState)
        assertEquals(2, original.size)
    }

    @Test
    fun serverPointerConflictDoesNotHideTheNextIndependentNap() {
        val projected =
            SleepConflictResolver.project(
                listOf(sleep("a", 100), sleep("b", 110, 200), sleep("c", 300)),
                1000,
            )
        assertTrue(projected.none { it.syncState == SyncState.CONFLICT })
        assertEquals("c", projected.single { it.endedAt == null }.remoteId)
    }
}
