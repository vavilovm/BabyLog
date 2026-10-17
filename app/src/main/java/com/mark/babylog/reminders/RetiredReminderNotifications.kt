package com.mark.babylog.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mark.babylog.BabyLogApp

/** Remove alarms and notifications left by versions with care reminders. */
internal object RetiredReminderNotifications {
    private const val CHANNEL = "scheduled_reminders_silent"

    suspend fun cancelAll(context: Context) {
        val app = context.applicationContext as BabyLogApp
        val ids =
            app.database.events().allRemindersForSync().map { it.id } +
                listOf("default-vitamin-k", "default-vitamin-d")
        cancel(context, ids)
    }

    internal fun cancel(context: Context, ids: List<String>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        ids.distinct().forEach { id ->
            val requestCode = id.hashCode() and 0x3fffffff
            val intent =
                Intent()
                    .setClassName(context, "com.mark.babylog.reminders.ReminderReceiver")
                    .setAction("com.mark.babylog.REMINDER_FIRE")
                    .setData(Uri.parse("babylog://reminder/$id"))
            PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                ?.let { pending ->
                    alarms.cancel(pending)
                    pending.cancel()
                }
            notifications.cancel(10_000 + (requestCode % 100_000))
        }
        notifications.deleteNotificationChannel(CHANNEL)
    }
}
