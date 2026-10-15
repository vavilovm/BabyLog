package com.mark.babylog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimedSleepTest {
    @Test
    fun duplicateStartAndWakeAreIdempotentAndLegacyMarksRemainUntouched() = runTest {
        val db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    BabyDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val repo = BabyLogRepository(db)
            db.events().startSleep(SleepPosition.LEFT, 1000)
            repo.beginSleep(2000)
            repo.beginSleep(2500)
            assertEquals(2, db.events().allForTest().size)
            assertEquals(2000L, db.events().activeSleep()!!.startedAt)
            repo.finishSleep(5000)
            repo.finishSleep(6000)
            assertNull(db.events().activeSleep())
            val events = db.events().allForTest()
            assertEquals(1000L, events.first().endedAt)
            assertEquals(0, events.first().sleepRecordVersion)
            assertEquals(5000L, events.last().endedAt)
            assertEquals(1, events.last().sleepRecordVersion)
        } finally {
            db.close()
        }
    }

    @Test
    fun profileAndTimedRecordsRoundTripThroughOutboxAndRemoteSync() = runTest {
        val db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    BabyDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val repo = BabyLogRepository(db)
            db.events()
                .putMembership(
                    FamilyMembership(
                        householdId = "family",
                        memberId = "parent",
                        displayName = "Мама",
                    )
                )
            repo.saveProfile(
                BabyProfile(birthday = "2026-07-01", dueDate = "2026-07-15", updatedAt = 10)
            )
            repo.beginSleep(2000)
            assertEquals(
                listOf("PROFILE_UPSERT", "SLEEP_START_V1"),
                db.events().pending().map { it.command },
            )
            repo.applyRemote(
                listOf(
                    mapOf(
                        "remoteId" to "other-parent",
                        "type" to "SLEEP",
                        "detail" to "BACK",
                        "startedAt" to 3000L,
                        "endedAt" to null,
                        "updatedAt" to 4000L,
                        "sleepRecordVersion" to 1,
                        "sleepConflict" to true,
                    )
                )
            )
            assertEquals(SyncState.CONFLICT, db.events().allForTest().last().syncState)
            repo.applyProfile(mapOf("birthday" to "2026-06-30", "updatedAt" to 5L))
            assertEquals("2026-07-01", db.events().profile()!!.birthday)
            repo.applyProfile(mapOf("birthday" to "2026-06-30", "updatedAt" to 20L))
            assertEquals("2026-06-30", db.events().profile()!!.birthday)
        } finally {
            db.close()
        }
    }
}
