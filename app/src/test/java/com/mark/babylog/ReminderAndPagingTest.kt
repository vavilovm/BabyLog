package com.mark.babylog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.data.BabyDatabase
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.BabyReminder
import com.mark.babylog.data.EventType
import com.mark.babylog.data.ReminderCompletion
import com.mark.babylog.reminders.occursOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ReminderAndPagingTest{
    private lateinit var db:BabyDatabase
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),BabyDatabase::class.java).allowMainThreadQueries().build()}
    @After fun close(){db.close()}

    @Test fun intervalUsesAnchorDate(){
        val anchor=LocalDate.of(2026,7,16)
        val reminder=BabyReminder(title="Витамин",intervalDays=2,anchorEpochDay=anchor.toEpochDay())
        assertTrue(reminder.occursOn(anchor))
        assertFalse(reminder.occursOn(anchor.plusDays(1)))
        assertTrue(reminder.occursOn(anchor.plusDays(2)))
    }

    @Test fun completionCanBeUndone()=runTest{
        val reminder=BabyReminder(title="Витамин Д",anchorEpochDay=LocalDate.now().toEpochDay())
        db.events().putReminder(reminder)
        db.events().putReminderCompletion(ReminderCompletion(reminder.id,reminder.anchorEpochDay))
        assertEquals(reminder.id,db.events().observeReminderCompletions().first().single().reminderId)
        db.events().deleteReminderCompletion(reminder.id,reminder.anchorEpochDay)
        assertTrue(db.events().observeReminderCompletions().first().isEmpty())
    }

    @Test fun historyIsReadInBoundedPagesNewestFirst()=runTest{
        repeat(250){index->db.events().insert(BabyEvent(type=EventType.SLEEP,detail="LEFT",startedAt=index.toLong()))}
        val firstPage=db.events().observeRecent(100).first()
        assertEquals(100,firstPage.size)
        assertEquals(249L,firstPage.first().startedAt)
        assertEquals(250,db.events().observeVisibleCount().first())
    }
}
