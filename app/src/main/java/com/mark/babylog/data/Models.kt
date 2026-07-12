package com.mark.babylog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class EventType { FEEDING, SLEEP }
enum class FeedingKind { LEFT, RIGHT, BOTTLE }
enum class SleepPosition { LEFT, RIGHT, BACK, OTHER }
enum class SyncState { LOCAL_ONLY, PENDING, SYNCED, FAILED, CONFLICT }

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

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startedAt DESC") fun observeAll():Flow<List<BabyEvent>>
    @Query("SELECT * FROM events WHERE deletedAt IS NULL ORDER BY startedAt") suspend fun allForTest():List<BabyEvent>
    @Query("SELECT * FROM sleep_segments ORDER BY startedAt") suspend fun allSegmentsForTest():List<SleepSegment>
    @Query("SELECT * FROM sleep_segments ORDER BY startedAt") fun observeSegments():Flow<List<SleepSegment>>
    @Query("SELECT * FROM events WHERE endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun active():BabyEvent?
    @Query("SELECT * FROM events WHERE type='FEEDING' AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun lastFeed():BabyEvent?
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

    @Transaction suspend fun startFeeding(kind:FeedingKind,time:Long,owner:FamilyMembership?=null):BabyEvent {
        active()?.let{current->if(current.type==EventType.SLEEP)finishSegment(current.id,time);finish(current.id,time)}
        val value=BabyEvent(type=EventType.FEEDING,detail=kind.name,startedAt=time,householdId=owner?.householdId,authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        return value.copy(id=insert(value))
    }
    @Transaction suspend fun startSleep(position:SleepPosition,time:Long,owner:FamilyMembership?=null):BabyEvent {
        active()?.let{current->if(current.type==EventType.SLEEP)finishSegment(current.id,time);finish(current.id,time)}
        val value=BabyEvent(type=EventType.SLEEP,detail=position.name,startedAt=time,householdId=owner?.householdId,authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        val saved=value.copy(id=insert(value));insert(SleepSegment(eventId=saved.id,position=position,startedAt=time,syncState=saved.syncState));return saved
    }
    @Transaction suspend fun stopActive(time:Long):BabyEvent? {val current=active()?:return null;if(current.type==EventType.SLEEP)finishSegment(current.id,time);finish(current.id,time);return current.copy(endedAt=time,updatedAt=time)}
}

class Converters {
    @TypeConverter fun eventToString(v:EventType)=v.name
    @TypeConverter fun stringToEvent(v:String)=EventType.valueOf(v)
    @TypeConverter fun posToString(v:SleepPosition)=v.name
    @TypeConverter fun stringToPos(v:String)=SleepPosition.valueOf(v)
    @TypeConverter fun syncToString(v:SyncState)=v.name
    @TypeConverter fun stringToSync(v:String)=SyncState.valueOf(v)
}

@Database(entities=[BabyEvent::class,SleepSegment::class,SyncOperation::class,FamilyMembership::class,SyncMetadata::class],version=2,exportSchema=false)
@TypeConverters(Converters::class)
abstract class BabyDatabase:RoomDatabase(){abstract fun events():EventDao}
