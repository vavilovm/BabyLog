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
class SleepTransitionTest {
    @Test fun sleepMarksAreInstantAndDoNotStopFeeding() = runTest {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),BabyDatabase::class.java).allowMainThreadQueries().build()
        val dao=db.events();dao.startFeeding(FeedingKind.LEFT,500);dao.startSleep(SleepPosition.LEFT,1_000);dao.startSleep(SleepPosition.RIGHT,2_000)
        val events=dao.allForTest();val segments=dao.allSegmentsForTest()
        assertEquals(3,events.size);assertTrue(segments.isEmpty())
        assertNull(events[0].endedAt)
        assertEquals(1_000L,events[1].endedAt);assertEquals(2_000L,events[2].endedAt)
        assertEquals("LEFT",dao.active()?.detail)
        db.close()
    }
}
