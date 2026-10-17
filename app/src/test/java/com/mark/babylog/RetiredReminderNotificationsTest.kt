package com.mark.babylog

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.reminders.RetiredReminderNotifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetiredReminderNotificationsTest {
    @Test
    fun upgradeClearsOldReminderAlarmsAndNotificationsButKeepsSleepStatus() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val alarms = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                "scheduled_reminders_silent",
                "Old reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        notifications.createNotificationChannel(
            NotificationChannel("sleep_status", "Sleep", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val ids = listOf("default-vitamin-d", "custom-reminder")
        ids.forEach { id ->
            val code = id.hashCode() and 0x3fffffff
            val oldIntent =
                Intent()
                    .setClassName(context, "com.mark.babylog.reminders.ReminderReceiver")
                    .setAction("com.mark.babylog.REMINDER_FIRE")
                    .setData(Uri.parse("babylog://reminder/$id"))
                    .putExtra("reminder_day", 20_000L)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    code,
                    oldIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            alarms.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, pending)
            notifications.notify(
                10_000 + code % 100_000,
                Notification.Builder(context, "scheduled_reminders_silent")
                    .setSmallIcon(R.drawable.ic_stat_timer)
                    .setContentTitle(id)
                    .build(),
            )
        }
        notifications.notify(
            42,
            Notification.Builder(context, "sleep_status")
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle("Sleep continues")
                .build(),
        )
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        RetiredReminderNotifications.cancel(context, ids)
        RetiredReminderNotifications.cancel(context, ids) // Safe to repeat after reboot or sync.
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertNull(notifications.getNotificationChannel("scheduled_reminders_silent"))
        assertNotNull(notifications.getNotificationChannel("sleep_status"))
        assertEquals(listOf(42), notifications.activeNotifications.map { it.id })
    }
}
