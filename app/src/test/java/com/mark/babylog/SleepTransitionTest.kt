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
    @Test fun startingRightSleep_closesLeftSleepAndStartsNewEvent() = runTest {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),BabyDatabase::class.java).allowMainThreadQueries().build()
        val dao=db.events();dao.startSleep(SleepPosition.LEFT,1_000);dao.startSleep(SleepPosition.RIGHT,2_000)
        val events=dao.allForTest();val segments=dao.allSegmentsForTest()
        assertEquals(2,events.size);assertEquals(2,segments.size)
        assertEquals(2_000L,events[0].endedAt);assertEquals(2_000L,segments[0].endedAt)
        assertEquals("RIGHT",events[1].detail);assertNull(events[1].endedAt);assertNull(segments[1].endedAt)
        db.close()
    }
}
