package com.mark.babylog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.data.BabyDatabase
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.BabyReminder
import com.mark.babylog.data.EventType
import com.mark.babylog.data.FamilyMembership
import com.mark.babylog.data.ReminderCompletion
import com.mark.babylog.data.SyncState
import com.mark.babylog.reminders.ReminderRepository
import com.mark.babylog.reminders.occursOn
import com.mark.babylog.reminders.shouldApplyRemote
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
        val completed=db.events().reminderCompletion(reminder.id,reminder.anchorEpochDay)!!
        db.events().putReminderCompletion(completed.copy(updatedAt=completed.updatedAt+1,deletedAt=completed.updatedAt+1))
        assertTrue(db.events().observeReminderCompletions().first().isEmpty())
        assertEquals(1,db.events().allReminderCompletionsForSync().size)
    }

    @Test fun deletedReminderIsHiddenButKeptForOtherDevices()=runTest{
        val reminder=BabyReminder(title="Удалить",anchorEpochDay=LocalDate.now().toEpochDay())
        db.events().putReminder(reminder)
        db.events().putReminder(reminder.copy(deletedAt=42,updatedAt=42))
        assertTrue(db.events().observeReminders().first().isEmpty())
        assertEquals(reminder.id,db.events().allRemindersForSync().single().id)
    }

    @Test fun newerPendingLocalChangeWinsUntilServerCatchesUp(){
        assertFalse(shouldApplyRemote(SyncState.PENDING,200,199))
        assertTrue(shouldApplyRemote(SyncState.PENDING,200,200))
        assertTrue(shouldApplyRemote(SyncState.SYNCED,200,199))
    }

    @Test fun existingLocalListIsQueuedWhenFamilyConnects()=runTest{
        val reminder=BabyReminder(title="Витамин К",anchorEpochDay=LocalDate.now().toEpochDay())
        db.events().putReminder(reminder)
        db.events().putReminderCompletion(ReminderCompletion(reminder.id,reminder.anchorEpochDay))
        db.events().putMembership(FamilyMembership(householdId="family",memberId="mama",displayName="Мама"))
        ReminderRepository(BabyLogApp(),db,requestSync={},reschedule={}).attachToFamily()
        assertEquals(setOf("REMINDER_UPSERT","REMINDER_COMPLETE"),db.events().pending().map{it.command}.toSet())
        assertEquals(SyncState.PENDING,db.events().allRemindersForSync().single().syncState)
        assertEquals(SyncState.PENDING,db.events().allReminderCompletionsForSync().single().syncState)
    }

    @Test fun remoteListAndDoneStateApplyWithoutOverwritingNewerPendingEdit()=runTest{
        val day=LocalDate.now().toEpochDay()
        val local=BabyReminder(id="shared",title="Новое локальное",anchorEpochDay=day,updatedAt=200,syncState=SyncState.PENDING)
        db.events().putReminder(local)
        val repository=ReminderRepository(BabyLogApp(),db,requestSync={},reschedule={})
        fun reminder(title:String,updatedAt:Long)=mapOf<String,Any?>("id" to "shared","title" to title,"hour" to 9,"minute" to 0,"intervalDays" to 1,"anchorEpochDay" to day,"enabled" to true,"createdAt" to 100L,"updatedAt" to updatedAt,"deletedAt" to null)
        repository.applyRemoteReminders(listOf(reminder("Старое с сервера",199)))
        assertEquals("Новое локальное",db.events().reminder("shared")?.title)
        repository.applyRemoteReminders(listOf(reminder("Подтверждено сервером",200)))
        assertEquals("Подтверждено сервером",db.events().reminder("shared")?.title)
        assertEquals(SyncState.SYNCED,db.events().reminder("shared")?.syncState)
        val completed=mapOf<String,Any?>("reminderId" to "shared","scheduledEpochDay" to day,"completedAt" to 300L,"updatedAt" to 300L,"deletedAt" to null)
        repository.applyRemoteCompletions(listOf(completed))
        assertEquals("shared",db.events().observeReminderCompletions().first().single().reminderId)
        repository.applyRemoteCompletions(listOf(completed+("updatedAt" to 301L)+("deletedAt" to 301L)))
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
