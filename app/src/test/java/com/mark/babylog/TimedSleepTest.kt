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
                BabyProfile(
                    birthday = "2026-07-01",
                    dueDate = "2026-07-15",
                    rhythmZoneId = "Europe/Amsterdam",
                    updatedAt = 10,
                )
            )
            assertEquals(
                "Europe/Amsterdam",
                org.json
                    .JSONObject(db.events().pending().single().payload)
                    .getString("rhythmZoneId"),
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
            val merged = db.events().sleepSnapshot(0).single()
            assertEquals(2000L, merged.startedAt)
            assertNull(merged.endedAt)
            assertEquals(SyncState.PENDING, merged.syncState)
            assertEquals(2, db.events().allForTest().size)
            assertFalse(db.events().pending().any { it.command == "DELETE" })
            repo.applyProfile(mapOf("birthday" to "2026-06-30", "updatedAt" to 5L))
            assertEquals("2026-07-01", db.events().profile()!!.birthday)
            repo.applyProfile(mapOf("birthday" to "2026-06-30", "updatedAt" to 20L))
            assertEquals("2026-06-30", db.events().profile()!!.birthday)
            assertEquals("Europe/Amsterdam", db.events().profile()!!.rhythmZoneId)
            repo.applyProfile(
                mapOf(
                    "birthday" to "2026-06-30",
                    "updatedAt" to 30L,
                    "rhythmZoneId" to "Asia/Tokyo",
                )
            )
            assertEquals("Asia/Tokyo", db.events().profile()!!.rhythmZoneId)
            repo.applyProfile(
                mapOf(
                    "birthday" to "2026-06-30",
                    "updatedAt" to 40L,
                    "rhythmZoneId" to "Invalid/Zone",
                )
            )
            assertEquals("Asia/Tokyo", db.events().profile()!!.rhythmZoneId)
            repo.saveProfile(BabyProfile(birthday = "2026-07-01", updatedAt = 50L))
            assertEquals("Asia/Tokyo", db.events().profile()!!.rhythmZoneId)
        } finally {
            db.close()
        }
    }

    @Test
    fun mergedSleepActionsUpdateEverySourceAndLateWakeIsPreserved() = runTest {
        val db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    BabyDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val dao = db.events()
            val repo = BabyLogRepository(db)
            fun remote(id: String, start: Long, end: Long? = null, updated: Long = 10000) =
                mapOf(
                    "remoteId" to id,
                    "type" to "SLEEP",
                    "detail" to "BACK",
                    "startedAt" to start,
                    "endedAt" to end,
                    "updatedAt" to updated,
                    "sleepRecordVersion" to 1,
                    "sleepConflict" to (id != "a"),
                )
            repo.applyRemote(listOf(remote("a", 1000), remote("b", 1200), remote("c", 1100)))
            assertEquals(3, dao.allForTest().size)
            assertTrue(dao.pending().isEmpty())
            assertEquals(1000L, dao.activeSleep()!!.startedAt)
            assertEquals(1, dao.sleepSnapshot(0).size)
            repo.finishSleep(5000)
            assertNull(dao.activeSleep())
            assertTrue(dao.allForTest().all { it.endedAt == 5000L })
            repo.applyRemote(listOf(remote("b", 1200, 4000, Long.MAX_VALUE)))
            assertEquals(4000L, dao.sleepSnapshot(0).single().endedAt)
            // Merge before cropping the display window: a later duplicate wake
            // must not make a sleep spill into the next day.
            assertTrue(dao.sleepSnapshot(4500).isEmpty())
            assertNull(dao.activeSleep())
            repo.beginSleep(6000)
            assertEquals(6000L, dao.activeSleep()!!.startedAt)
            val completed = dao.sleepSnapshot(0).first()
            repo.updateEvent(completed.copy(startedAt = 900, endedAt = 3900))
            assertTrue(
                dao.allForTest()
                    .filter { it.remoteId in setOf("a", "b", "c") }
                    .all { it.startedAt == 900L && it.endedAt == 3900L }
            )
            repo.deleteEvent(dao.sleepSnapshot(0).first())
            assertEquals(1, dao.allForTest().size)
        } finally {
            db.close()
        }
    }
}
