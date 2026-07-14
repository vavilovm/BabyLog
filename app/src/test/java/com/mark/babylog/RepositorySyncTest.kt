package com.mark.babylog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.data.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositorySyncTest{
    private lateinit var db:BabyDatabase
    private lateinit var repository:BabyLogRepository
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),BabyDatabase::class.java).allowMainThreadQueries().build();repository=BabyLogRepository(db)}
    @After fun close(){db.close()}

    @Test fun familyActionIsImmediateAndQueued()=runTest{
        db.events().putMembership(FamilyMembership(householdId="family",memberId="mama",displayName="Мама"))
        repository.startFeeding(FeedingKind.LEFT,1_000)
        val active=db.events().active()
        assertEquals("LEFT",active?.detail);assertEquals("Мама",active?.authorName);assertEquals(SyncState.PENDING,active?.syncState)
        assertEquals("START",db.events().pending().single().command)
    }

    @Test fun offlineWithoutFamilyStaysLocalAndHasNoOutbox()=runTest{
        repository.startSleep(SleepPosition.RIGHT,2_000)
        assertEquals(SyncState.LOCAL_ONLY,db.events().allForTest().single().syncState)
        assertNull(db.events().active())
        assertTrue(db.events().pending().isEmpty())
    }

    @Test fun familySleepMarkIsInstantAndQueuedAsLog()=runTest{
        db.events().putMembership(FamilyMembership(householdId="family",memberId="papa",displayName="Папа"))
        repository.startSleep(SleepPosition.LEFT,3_000)
        assertEquals(3_000L,db.events().allForTest().single().endedAt)
        assertEquals("LOG_SLEEP",db.events().pending().single().command)
    }

    @Test fun bottleVolumeIsLoggedAsInstantCompletedFeeding()=runTest{
        db.events().putMembership(FamilyMembership(householdId="family",memberId="mama",displayName="Мама"))
        repository.logBottle(120,61_000)
        val event=db.events().allForTest().single()
        assertEquals("BOTTLE:120",event.detail)
        assertEquals(61_000L,event.startedAt)
        assertEquals(61_000L,event.endedAt)
        assertNull(db.events().active())
        assertEquals("LOG_BOTTLE",db.events().pending().single().command)
    }
}
