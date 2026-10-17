package com.mark.babylog.reminders

import com.mark.babylog.BabyLogApp
import com.mark.babylog.data.BabyDatabase
import com.mark.babylog.data.BabyReminder
import com.mark.babylog.data.ReminderCompletion
import com.mark.babylog.data.SyncOperation
import com.mark.babylog.data.SyncState
import java.time.LocalDate
import org.json.JSONObject

// Keep legacy records compatible with older family devices and existing sync outboxes.
// This repository no longer creates reminders or schedules notifications.
class ReminderRepository(
    private val app: BabyLogApp,
    private val database: BabyDatabase = app.database,
    private val requestSync: () -> Unit = { app.familySync.schedule() },
    private val onRemoteApplied: suspend () -> Unit = {
        RetiredReminderNotifications.cancelAll(app)
    },
) {
    private val dao
        get() = database.events()

    suspend fun attachToFamily() {
        if (dao.membership() == null) return
        var queued = false
        dao.allRemindersForSync()
            .filter { it.syncState == SyncState.LOCAL_ONLY }
            .forEach { value ->
                val pending = value.copy(syncState = SyncState.PENDING)
                dao.putReminder(pending)
                queue(
                    if (value.deletedAt == null) "REMINDER_UPSERT" else "REMINDER_DELETE",
                    reminderJson(pending),
                    pending.updatedAt,
                )
                queued = true
            }
        dao.allReminderCompletionsForSync()
            .filter { it.syncState == SyncState.LOCAL_ONLY }
            .forEach { value ->
                val pending = value.copy(syncState = SyncState.PENDING)
                dao.putReminderCompletion(pending)
                queue(
                    if (value.deletedAt == null) "REMINDER_COMPLETE" else "REMINDER_UNDO",
                    completionJson(pending),
                    pending.updatedAt,
                )
                queued = true
            }
        if (queued) requestSync()
    }

    suspend fun applyRemoteReminders(values: List<Map<String, Any?>>) {
        values.forEach { raw ->
            val id = raw["id"] as? String ?: return@forEach
            val remoteUpdatedAt = (raw["updatedAt"] as? Number)?.toLong() ?: 0
            val existing = dao.reminder(id)
            if (!shouldApplyRemote(existing?.syncState, existing?.updatedAt ?: 0, remoteUpdatedAt))
                return@forEach
            val value =
                BabyReminder(
                    id = id,
                    title = raw["title"] as? String ?: return@forEach,
                    hour = (raw["hour"] as? Number)?.toInt() ?: 9,
                    minute = (raw["minute"] as? Number)?.toInt() ?: 0,
                    intervalDays =
                        ((raw["intervalDays"] as? Number)?.toInt() ?: 1).coerceAtLeast(1),
                    anchorEpochDay =
                        (raw["anchorEpochDay"] as? Number)?.toLong()
                            ?: LocalDate.now().toEpochDay(),
                    enabled = raw["enabled"] as? Boolean ?: true,
                    createdAt = (raw["createdAt"] as? Number)?.toLong() ?: remoteUpdatedAt,
                    updatedAt = remoteUpdatedAt,
                    deletedAt = (raw["deletedAt"] as? Number)?.toLong(),
                    syncState = SyncState.SYNCED,
                )
            dao.putReminder(value)
        }
        onRemoteApplied()
    }

    suspend fun applyRemoteCompletions(values: List<Map<String, Any?>>) {
        values.forEach { raw ->
            val reminderId = raw["reminderId"] as? String ?: return@forEach
            if (dao.reminder(reminderId) == null) return@forEach
            val epochDay = (raw["scheduledEpochDay"] as? Number)?.toLong() ?: return@forEach
            val remoteUpdatedAt = (raw["updatedAt"] as? Number)?.toLong() ?: 0
            val existing = dao.reminderCompletionIncludingDeleted(reminderId, epochDay)
            if (!shouldApplyRemote(existing?.syncState, existing?.updatedAt ?: 0, remoteUpdatedAt))
                return@forEach
            dao.putReminderCompletion(
                ReminderCompletion(
                    reminderId = reminderId,
                    scheduledEpochDay = epochDay,
                    completedAt = (raw["completedAt"] as? Number)?.toLong() ?: remoteUpdatedAt,
                    updatedAt = remoteUpdatedAt,
                    deletedAt = (raw["deletedAt"] as? Number)?.toLong(),
                    syncState = SyncState.SYNCED,
                )
            )
        }
        onRemoteApplied()
    }

    private suspend fun queue(command: String, payload: JSONObject, time: Long) {
        dao.put(SyncOperation(command = command, payload = payload.toString(), occurredAt = time))
    }

    private fun reminderJson(value: BabyReminder) =
        JSONObject().apply {
            put("id", value.id)
            put("title", value.title)
            put("hour", value.hour)
            put("minute", value.minute)
            put("intervalDays", value.intervalDays)
            put("anchorEpochDay", value.anchorEpochDay)
            put("enabled", value.enabled)
            put("createdAt", value.createdAt)
            put("updatedAt", value.updatedAt)
            put("deletedAt", value.deletedAt ?: JSONObject.NULL)
        }

    private fun completionJson(value: ReminderCompletion) =
        JSONObject().apply {
            put("reminderId", value.reminderId)
            put("scheduledEpochDay", value.scheduledEpochDay)
            put("completedAt", value.completedAt)
            put("updatedAt", value.updatedAt)
            put("deletedAt", value.deletedAt ?: JSONObject.NULL)
        }
}

internal fun shouldApplyRemote(
    localState: SyncState?,
    localUpdatedAt: Long,
    remoteUpdatedAt: Long,
) = localState != SyncState.PENDING || localUpdatedAt <= remoteUpdatedAt
