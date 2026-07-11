package com.mark.babylog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class EventType { FEEDING, SLEEP }
enum class FeedingKind { LEFT, RIGHT, BOTTLE }
enum class SleepPosition { LEFT, RIGHT, BACK, OTHER }

@Entity(tableName = "events")
data class BabyEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: EventType,
    val detail: String,
    val startedAt: Long,
    val endedAt: Long? = null
)

@Entity(tableName = "sleep_segments", foreignKeys = [ForeignKey(entity = BabyEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class SleepSegment(@PrimaryKey(autoGenerate = true) val id: Long = 0, val eventId: Long, val position: SleepPosition, val startedAt: Long, val endedAt: Long? = null)

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY startedAt DESC") fun observeAll(): Flow<List<BabyEvent>>
    @Query("SELECT * FROM events ORDER BY startedAt") suspend fun allForTest(): List<BabyEvent>
    @Query("SELECT * FROM sleep_segments ORDER BY startedAt") suspend fun allSegmentsForTest(): List<SleepSegment>
    @Query("SELECT * FROM sleep_segments ORDER BY startedAt") fun observeSegments(): Flow<List<SleepSegment>>
    @Query("SELECT * FROM events WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun active(): BabyEvent?
    @Query("SELECT * FROM events WHERE type = 'FEEDING' ORDER BY startedAt DESC LIMIT 1") suspend fun lastFeed(): BabyEvent?
    @Insert suspend fun insert(event: BabyEvent): Long
    @Insert suspend fun insert(segment: SleepSegment): Long
    @Update suspend fun update(event: BabyEvent)
    @Update suspend fun update(segment: SleepSegment)
    @Delete suspend fun delete(event: BabyEvent)
    @Query("UPDATE events SET endedAt=:time WHERE id=:id") suspend fun finish(id: Long, time: Long)
    @Query("UPDATE sleep_segments SET endedAt=:time WHERE eventId=:eventId AND endedAt IS NULL") suspend fun finishSegment(eventId: Long, time: Long)

    @Transaction suspend fun startFeeding(kind: FeedingKind, time: Long): Long {
        active()?.let { current -> if (current.type == EventType.SLEEP) finishSegment(current.id, time); finish(current.id, time) }
        return insert(BabyEvent(type = EventType.FEEDING, detail = kind.name, startedAt = time))
    }

    @Transaction suspend fun startSleep(position: SleepPosition, time: Long): Long {
        active()?.let { current -> if (current.type == EventType.SLEEP) finishSegment(current.id, time); finish(current.id, time) }
        val id = insert(BabyEvent(type = EventType.SLEEP, detail = position.name, startedAt = time))
        insert(SleepSegment(eventId = id, position = position, startedAt = time))
        return id
    }

    @Transaction suspend fun stopActive(time: Long) {
        active()?.let { current -> if (current.type == EventType.SLEEP) finishSegment(current.id, time); finish(current.id, time) }
    }
}

class Converters {
    @TypeConverter fun eventToString(v: EventType) = v.name
    @TypeConverter fun stringToEvent(v: String) = EventType.valueOf(v)
    @TypeConverter fun posToString(v: SleepPosition) = v.name
    @TypeConverter fun stringToPos(v: String) = SleepPosition.valueOf(v)
}

@Database(entities = [BabyEvent::class, SleepSegment::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class BabyDatabase : RoomDatabase() { abstract fun events(): EventDao }
