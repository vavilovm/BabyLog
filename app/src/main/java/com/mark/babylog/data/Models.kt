package com.mark.babylog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class EventType { FEEDING, PUMPING, SLEEP }
enum class FeedingKind { LEFT, RIGHT, BOTTLE }
enum class SleepPosition { LEFT, RIGHT, BACK, OTHER }
enum class SyncState { LOCAL_ONLY, PENDING, SYNCED, FAILED, CONFLICT }

fun feedingKindOf(detail:String)=runCatching{FeedingKind.valueOf(detail.substringBefore(':'))}.getOrDefault(FeedingKind.BOTTLE)
fun bottleVolumeMl(detail:String)=detail.takeIf{feedingKindOf(it)==FeedingKind.BOTTLE}?.substringAfter(':',"")?.toIntOrNull()

@Entity(tableName="events",indices=[Index(value=["remoteId"],unique=true)])
data class BabyEvent(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val type:EventType,
    val detail:String,
    val startedAt:Long,
    val endedAt:Long?=null,
    val remoteId:String=UUID.randomUUID().toString(),
    val householdId:String?=null,
    val authorId:String?=null,
    val authorName:String?=null,
    val updatedAt:Long=System.currentTimeMillis(),
    val syncState:SyncState=SyncState.LOCAL_ONLY,
    val deletedAt:Long?=null
)

@Entity(tableName="sleep_segments",foreignKeys=[ForeignKey(entity=BabyEvent::class,parentColumns=["id"],childColumns=["eventId"],onDelete=ForeignKey.CASCADE)],indices=[Index("eventId"),Index(value=["remoteId"],unique=true)])
data class SleepSegment(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val eventId:Long,
    val position:SleepPosition,
    val startedAt:Long,
    val endedAt:Long?=null,
    val remoteId:String=UUID.randomUUID().toString(),
    val updatedAt:Long=System.currentTimeMillis(),
    val syncState:SyncState=SyncState.LOCAL_ONLY
)

@Entity(tableName="sync_operations")
data class SyncOperation(@PrimaryKey val id:String=UUID.randomUUID().toString(),val command:String,val payload:String,val occurredAt:Long,val state:SyncState=SyncState.PENDING,val attempts:Int=0,val error:String?=null)

@Entity(tableName="family_membership")
data class FamilyMembership(@PrimaryKey val singleton:Int=1,val householdId:String,val memberId:String,val displayName:String,val inviteCode:String?=null,val updatedAt:Long=System.currentTimeMillis())

@Entity(tableName="sync_metadata")
data class SyncMetadata(@PrimaryKey val key:String,val value:String)

@Entity(tableName="reminders")
data class BabyReminder(
    @PrimaryKey val id:String=UUID.randomUUID().toString(),
    val title:String,
    val hour:Int=9,
    val minute:Int=0,
    val intervalDays:Int=1,
    val anchorEpochDay:Long,
    val enabled:Boolean=true,
    val createdAt:Long=System.currentTimeMillis(),
    val updatedAt:Long=System.currentTimeMillis(),
    val deletedAt:Long?=null,
    val syncState:SyncState=SyncState.LOCAL_ONLY
)

@Entity(
    tableName="reminder_completions",
    primaryKeys=["reminderId","scheduledEpochDay"],
    foreignKeys=[ForeignKey(entity=BabyReminder::class,parentColumns=["id"],childColumns=["reminderId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("reminderId")]
)
data class ReminderCompletion(
    val reminderId:String,
    val scheduledEpochDay:Long,
    val completedAt:Long=System.currentTimeMillis(),
    val updatedAt:Long=System.currentTimeMillis(),
    val deletedAt:Long?=null,
    val syncState:SyncState=SyncState.LOCAL_ONLY
)

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startedAt DESC") fun observeAll():Flow<List<BabyEvent>>
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startedAt DESC LIMIT :limit") fun observeRecent(limit:Int):Flow<List<BabyEvent>>
    @Query("SELECT * FROM events WHERE deletedAt IS NULL AND startedAt >= :startInclusive AND startedAt < :endExclusive ORDER BY startedAt") fun observeDay(startInclusive:Long,endExclusive:Long):Flow<List<BabyEvent>>
    @Query("SELECT COUNT(*) FROM events WHERE deletedAt IS NULL") fun observeVisibleCount():Flow<Int>
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startedAt") suspend fun allForTest():List<BabyEvent>
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startedAt") suspend fun allForExport():List<BabyEvent>
    @Query("SELECT * FROM sleep_segments ORDER BY startedAt") suspend fun allSegmentsForTest():List<SleepSegment>
    @Query("SELECT * FROM sleep_segments ORDER BY startedAt") fun observeSegments():Flow<List<SleepSegment>>
    @Query("SELECT * FROM events WHERE type='FEEDING' AND endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun active():BabyEvent?
    @Query("SELECT * FROM events WHERE type='FEEDING' AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun lastFeed():BabyEvent?
    @Query("SELECT * FROM events WHERE type='SLEEP' AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun lastSleep():BabyEvent?
    @Query("SELECT * FROM events WHERE id=:id") suspend fun byId(id:Long):BabyEvent?
    @Query("SELECT * FROM events WHERE remoteId=:remoteId LIMIT 1") suspend fun byRemoteId(remoteId:String):BabyEvent?
    @Query("SELECT * FROM sleep_segments WHERE eventId=:eventId ORDER BY startedAt") suspend fun segments(eventId:Long):List<SleepSegment>
    @Insert suspend fun insert(event:BabyEvent):Long
    @Insert suspend fun insert(segment:SleepSegment):Long
    @Update suspend fun update(event:BabyEvent)
    @Update suspend fun update(segment:SleepSegment)
    @Delete suspend fun delete(event:BabyEvent)
    @Query("UPDATE events SET endedAt=:time,updatedAt=:time WHERE id=:id") suspend fun finish(id:Long,time:Long)
    @Query("UPDATE sleep_segments SET endedAt=:time,updatedAt=:time WHERE eventId=:eventId AND endedAt IS NULL") suspend fun finishSegment(eventId:Long,time:Long)
    @Query("SELECT * FROM sync_operations WHERE state IN ('PENDING','FAILED') ORDER BY occurredAt LIMIT :limit") suspend fun pending(limit:Int=50):List<SyncOperation>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(operation:SyncOperation)
    @Query("DELETE FROM sync_operations WHERE id=:id") suspend fun removeOperation(id:String)
    @Query("UPDATE sync_operations SET state='FAILED', attempts=attempts+1, error=:error WHERE id=:id") suspend fun failOperation(id:String,error:String)
    @Query("SELECT COUNT(*) FROM sync_operations WHERE state IN ('PENDING','FAILED')") fun observePendingCount():Flow<Int>
    @Query("SELECT * FROM family_membership WHERE singleton=1") fun observeMembership():Flow<FamilyMembership?>
    @Query("SELECT * FROM family_membership WHERE singleton=1") suspend fun membership():FamilyMembership?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putMembership(value:FamilyMembership)
    @Query("DELETE FROM family_membership") suspend fun clearMembership()
    @Query("SELECT * FROM reminders WHERE deletedAt IS NULL ORDER BY createdAt, title") fun observeReminders():Flow<List<BabyReminder>>
    @Query("SELECT * FROM reminder_completions WHERE deletedAt IS NULL ORDER BY completedAt DESC") fun observeReminderCompletions():Flow<List<ReminderCompletion>>
    @Query("SELECT * FROM reminders WHERE deletedAt IS NULL ORDER BY createdAt, title") suspend fun reminders():List<BabyReminder>
    @Query("SELECT * FROM reminders ORDER BY createdAt, title") suspend fun allRemindersForSync():List<BabyReminder>
    @Query("SELECT * FROM reminder_completions ORDER BY completedAt") suspend fun allReminderCompletionsForSync():List<ReminderCompletion>
    @Query("SELECT * FROM reminders WHERE id=:id LIMIT 1") suspend fun reminder(id:String):BabyReminder?
    @Query("SELECT COUNT(*) FROM reminders WHERE deletedAt IS NULL") suspend fun reminderCount():Int
    @Upsert suspend fun putReminder(reminder:BabyReminder)
    @Delete suspend fun hardDeleteReminder(reminder:BabyReminder)
    @Upsert suspend fun putReminderCompletion(completion:ReminderCompletion)
    @Query("DELETE FROM reminder_completions WHERE reminderId=:reminderId AND scheduledEpochDay=:epochDay") suspend fun deleteReminderCompletion(reminderId:String,epochDay:Long)
    @Query("SELECT * FROM reminder_completions WHERE reminderId=:reminderId AND scheduledEpochDay=:epochDay AND deletedAt IS NULL LIMIT 1") suspend fun reminderCompletion(reminderId:String,epochDay:Long):ReminderCompletion?
    @Query("SELECT * FROM reminder_completions WHERE reminderId=:reminderId AND scheduledEpochDay=:epochDay LIMIT 1") suspend fun reminderCompletionIncludingDeleted(reminderId:String,epochDay:Long):ReminderCompletion?

    @Transaction suspend fun startFeeding(kind:FeedingKind,time:Long,owner:FamilyMembership?=null):BabyEvent {
        active()?.let{finish(it.id,time)}
        val value=BabyEvent(type=EventType.FEEDING,detail=kind.name,startedAt=time,householdId=owner?.householdId,authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        return value.copy(id=insert(value))
    }
    @Transaction suspend fun startSleep(position:SleepPosition,time:Long,owner:FamilyMembership?=null):BabyEvent {
        val value=BabyEvent(type=EventType.SLEEP,detail=position.name,startedAt=time,endedAt=time,householdId=owner?.householdId,authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        return value.copy(id=insert(value))
    }
    @Transaction suspend fun logPumping(side:FeedingKind,volumeMl:Int,time:Long,owner:FamilyMembership?=null):BabyEvent {
        require(side==FeedingKind.LEFT||side==FeedingKind.RIGHT)
        require(volumeMl>0)
        val value=BabyEvent(type=EventType.PUMPING,detail="${side.name}:$volumeMl",startedAt=time,endedAt=time,householdId=owner?.householdId,authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        return value.copy(id=insert(value))
    }
    @Transaction suspend fun logBottle(volumeMl:Int,time:Long,owner:FamilyMembership?=null):BabyEvent {
        require(volumeMl>0)
        val value=BabyEvent(type=EventType.FEEDING,detail="BOTTLE:$volumeMl",startedAt=time,endedAt=time,householdId=owner?.householdId,authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        return value.copy(id=insert(value))
    }
    @Transaction suspend fun stopActive(time:Long):BabyEvent? {val current=active()?:return null;finish(current.id,time);return current.copy(endedAt=time,updatedAt=time)}
}

class Converters {
    @TypeConverter fun eventToString(v:EventType)=v.name
    @TypeConverter fun stringToEvent(v:String)=EventType.valueOf(v)
    @TypeConverter fun posToString(v:SleepPosition)=v.name
    @TypeConverter fun stringToPos(v:String)=SleepPosition.valueOf(v)
    @TypeConverter fun syncToString(v:SyncState)=v.name
    @TypeConverter fun stringToSync(v:String)=SyncState.valueOf(v)
}

@Database(entities=[BabyEvent::class,SleepSegment::class,SyncOperation::class,FamilyMembership::class,SyncMetadata::class,BabyReminder::class,ReminderCompletion::class],version=4,exportSchema=false)
@TypeConverters(Converters::class)
abstract class BabyDatabase:RoomDatabase(){abstract fun events():EventDao}
