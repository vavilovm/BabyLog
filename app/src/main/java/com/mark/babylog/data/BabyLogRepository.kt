package com.mark.babylog.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

class BabyLogRepository(private val db: BabyDatabase) {
    val dao = db.events()
    val membership = dao.observeMembership()
    val pendingCount = dao.observePendingCount()

    suspend fun logFeeding(kind: FeedingKind, time: Long = System.currentTimeMillis()) =
        db.withTransaction {
            normalizeLegacyActiveFeedingInTransaction(time)
            val owner = dao.membership()
            val event = dao.logFeeding(kind, time, owner)
            // The deployed backend already treats LOG_BOTTLE as a generic instant
            // feeding log, regardless of whether detail is LEFT, RIGHT, or BOTTLE.
            enqueue("LOG_BOTTLE", event, time)
        }

    suspend fun startSleep(position: SleepPosition, time: Long = System.currentTimeMillis()) =
        db.withTransaction {
            val owner = dao.membership()
            val event = dao.startSleep(position, time, owner)
            enqueue("LOG_SLEEP", event, time)
        }

    suspend fun logPumping(
        side: FeedingKind,
        volumeMl: Int,
        time: Long = System.currentTimeMillis(),
    ) =
        db.withTransaction {
            val owner = dao.membership()
            val event = dao.logPumping(side, volumeMl, time, owner)
            enqueue("LOG_PUMPING", event, time)
        }

    suspend fun logBottle(volumeMl: Int, time: Long = System.currentTimeMillis()) =
        db.withTransaction {
            val owner = dao.membership()
            val event = dao.logBottle(volumeMl, time, owner)
            enqueue("LOG_BOTTLE", event, time)
        }

    suspend fun stop(time: Long = System.currentTimeMillis()) =
        db.withTransaction { dao.stopActive(time)?.let { enqueue("STOP", it, time) } }

    suspend fun updateEvent(event: BabyEvent) =
        db.withTransaction {
            val owner = dao.membership()
            val changed =
                event.copy(
                    updatedAt = System.currentTimeMillis(),
                    authorId = owner?.memberId,
                    authorName = owner?.displayName,
                    syncState = if (owner == null) SyncState.LOCAL_ONLY else SyncState.PENDING,
                )
            dao.update(changed)
            enqueue("UPDATE", changed, changed.updatedAt)
        }

    suspend fun deleteEvent(event: BabyEvent) =
        db.withTransaction {
            val now = System.currentTimeMillis()
            val deleted =
                event.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING)
            dao.update(deleted)
            enqueue("DELETE", deleted, now)
        }

    suspend fun normalizeLegacyActiveFeeding(time: Long = System.currentTimeMillis()) =
        db.withTransaction { normalizeLegacyActiveFeedingInTransaction(time) }

    suspend fun attachToFamily() =
        db.withTransaction {
            val owner = dao.membership() ?: return@withTransaction
            dao.localEventsForFamilyAttach().forEach { event ->
                val pending =
                    event.copy(
                        householdId = owner.householdId,
                        authorId = event.authorId ?: owner.memberId,
                        authorName = event.authorName ?: owner.displayName,
                        syncState = SyncState.PENDING,
                    )
                dao.update(pending)
                val command =
                    when {
                        pending.deletedAt != null -> "DELETE"
                        pending.type == EventType.SLEEP -> "LOG_SLEEP"
                        pending.type == EventType.PUMPING -> "LOG_PUMPING"
                        else -> "LOG_BOTTLE"
                    }
                dao.put(
                    SyncOperation(
                        command = command,
                        payload = eventJson(pending).toString(),
                        occurredAt = pending.updatedAt,
                    )
                )
            }
        }

    private suspend fun normalizeLegacyActiveFeedingInTransaction(time: Long) {
        val legacy = dao.active() ?: return
        val owner = dao.membership()
        val changed =
            legacy.copy(
                endedAt = legacy.startedAt,
                updatedAt = time + 1,
                authorId = owner?.memberId ?: legacy.authorId,
                authorName = owner?.displayName ?: legacy.authorName,
                syncState = if (owner == null) SyncState.LOCAL_ONLY else SyncState.PENDING,
            )
        dao.update(changed)
        if (owner != null) {
            // STOP clears the old server-side active pointer; UPDATE then restores
            // the intended zero-duration end time.
            enqueue("STOP", legacy.copy(endedAt = time, updatedAt = time), time)
            enqueue("UPDATE", changed, time + 1)
        }
    }

    private suspend fun enqueue(command: String, event: BabyEvent, time: Long) {
        if (dao.membership() == null) return
        dao.put(
            SyncOperation(
                command = command,
                payload = eventJson(event).toString(),
                occurredAt = time,
            )
        )
    }

    private suspend fun eventJson(e: BabyEvent) =
        JSONObject().apply {
            put("remoteId", e.remoteId)
            put("type", e.type.name)
            put("detail", e.detail)
            put("startedAt", e.startedAt)
            put("endedAt", e.endedAt ?: JSONObject.NULL)
            put("updatedAt", e.updatedAt)
            put("deletedAt", e.deletedAt ?: JSONObject.NULL)
            put("authorId", e.authorId ?: JSONObject.NULL)
            put("authorName", e.authorName ?: JSONObject.NULL)
            put(
                "segments",
                JSONArray(
                    dao.segments(e.id).map { segment ->
                        JSONObject().apply {
                            put("remoteId", segment.remoteId)
                            put("position", segment.position.name)
                            put("startedAt", segment.startedAt)
                            put("endedAt", segment.endedAt ?: JSONObject.NULL)
                            put("updatedAt", segment.updatedAt)
                        }
                    }
                ),
            )
        }

    suspend fun applyRemote(values: List<Map<String, Any?>>) =
        db.withTransaction {
            val owner = dao.membership()
            val parsed = values.mapNotNull(::parseRemoteEvent)
            val existingByRemote =
                parsed
                    .map { it.remoteId }
                    .chunked(500)
                    .flatMap { ids -> dao.byRemoteIds(ids) }
                    .associateBy { it.remoteId }
            parsed.forEach eventLoop@{ remote ->
                val existing = existingByRemote[remote.remoteId]
                if (
                    existing?.syncState == SyncState.PENDING &&
                        existing.updatedAt > remote.updatedAt
                )
                    return@eventLoop
                val event =
                    BabyEvent(
                        id = existing?.id ?: 0,
                        type = remote.type,
                        detail = remote.detail,
                        startedAt = remote.startedAt,
                        endedAt = remote.endedAt,
                        remoteId = remote.remoteId,
                        householdId = owner?.householdId,
                        authorId = remote.authorId,
                        authorName = remote.authorName,
                        updatedAt = remote.updatedAt,
                        syncState = SyncState.SYNCED,
                        deletedAt = remote.deletedAt,
                    )
                val localId =
                    if (existing == null) dao.insert(event)
                    else {
                        dao.update(event)
                        event.id
                    }
                val localByRemote = dao.segments(localId).associateBy { it.remoteId }
                remote.segments.forEach segmentLoop@{ segment ->
                    val old = localByRemote[segment.remoteId]
                    val value =
                        SleepSegment(
                            id = old?.id ?: 0,
                            eventId = localId,
                            position = segment.position,
                            startedAt = segment.startedAt,
                            endedAt = segment.endedAt,
                            remoteId = segment.remoteId,
                            updatedAt = segment.updatedAt,
                            syncState = SyncState.SYNCED,
                        )
                    if (old == null) dao.insert(value) else dao.update(value)
                }
            }
        }

    private data class RemoteEvent(
        val remoteId: String,
        val type: EventType,
        val detail: String,
        val startedAt: Long,
        val endedAt: Long?,
        val authorId: String?,
        val authorName: String?,
        val updatedAt: Long,
        val deletedAt: Long?,
        val segments: List<RemoteSegment>,
    )

    private data class RemoteSegment(
        val remoteId: String,
        val position: SleepPosition,
        val startedAt: Long,
        val endedAt: Long?,
        val updatedAt: Long,
    )

    private fun parseRemoteEvent(raw: Map<String, Any?>): RemoteEvent? {
        val remoteId = raw["remoteId"] as? String ?: return null
        val type =
            (raw["type"] as? String)?.let { value ->
                EventType.entries.firstOrNull { it.name == value }
            } ?: return null
        val detail = raw["detail"] as? String ?: return null
        if (parseEventDetails(type, detail) == null) return null
        val startedAt = (raw["startedAt"] as? Number)?.toLong() ?: return null
        @Suppress("UNCHECKED_CAST")
        val segments =
            (raw["segments"] as? List<Map<String, Any?>>).orEmpty().mapNotNull(::parseRemoteSegment)
        return RemoteEvent(
            remoteId,
            type,
            detail,
            startedAt,
            (raw["endedAt"] as? Number)?.toLong(),
            raw["authorId"] as? String,
            raw["authorName"] as? String,
            (raw["updatedAt"] as? Number)?.toLong() ?: 0,
            (raw["deletedAt"] as? Number)?.toLong(),
            segments,
        )
    }

    private fun parseRemoteSegment(raw: Map<String, Any?>): RemoteSegment? {
        val remoteId = raw["remoteId"] as? String ?: return null
        val position =
            (raw["position"] as? String)?.let { value ->
                SleepPosition.entries.firstOrNull { it.name == value }
            } ?: return null
        val startedAt = (raw["startedAt"] as? Number)?.toLong() ?: return null
        return RemoteSegment(
            remoteId,
            position,
            startedAt,
            (raw["endedAt"] as? Number)?.toLong(),
            (raw["updatedAt"] as? Number)?.toLong() ?: 0,
        )
    }
}
