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
        assertEquals(SyncState.LOCAL_ONLY,db.events().active()?.syncState)
        assertTrue(db.events().pending().isEmpty())
    }
}
