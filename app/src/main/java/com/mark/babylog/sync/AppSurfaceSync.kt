package com.mark.babylog.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.runComposition
import com.mark.babylog.BabyLogApp
import com.mark.babylog.MainActivity
import com.mark.babylog.R
import com.mark.babylog.data.*
import com.mark.babylog.reminders.ReminderScheduler
import com.mark.babylog.widget.FeedingHorizontalWidget
import com.mark.babylog.widget.FeedingHorizontalWidgetReceiver
import com.mark.babylog.widget.FeedingMiniWidget
import com.mark.babylog.widget.FeedingMiniWidgetReceiver
import com.mark.babylog.widget.FeedingWidget
import com.mark.babylog.widget.FeedingWidgetReceiver
import com.mark.babylog.widget.SleepWidget
import com.mark.babylog.widget.SleepWidgetReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SuppressLint("RestrictedApi", "MissingPermission")
object AppSurfaceSync {
    // This channel id keeps the persistent quick-log notification out of the
    // easily hidden "silent/minimized" bucket for existing installs as well.
    private const val FEEDING_START_CHANNEL = "feeding_starts_visible"
    private const val LEGACY_NOTIFICATION_ID = 42
    private const val NOTIFICATION_ID = 43
    private const val FEEDING_START_NOTIFICATION_ID = 44
    private val refreshMutex = Mutex()

    suspend fun refresh(context: Context) =
        refreshMutex.withLock {
            supervisorScope {
                launch {
                    runCatching { refreshWidgets(context) }
                        .onFailure { Log.w("BabyLog", "Widget refresh failed", it) }
                }
                launch {
                    runCatching {
                            val lastFeed =
                                (context.applicationContext as BabyLogApp)
                                    .database
                                    .events()
                                    .lastFeed()
                            refreshNotification(context, lastFeed)
                        }
                        .onFailure { Log.w("BabyLog", "Notification refresh failed", it) }
                }
            }
        }

    suspend fun refreshFromWidget(context: Context, id: GlanceId, widget: GlanceAppWidget) {
        runCatching { pushImmediately(context, widget, id) }
        refreshMutex.withLock {
            supervisorScope {
                launch { runCatching { refreshWidgets(context) } }
                launch {
                    val lastFeed =
                        (context.applicationContext as BabyLogApp).database.events().lastFeed()
                    runCatching { refreshNotification(context, lastFeed) }
                }
            }
        }
    }

    private suspend fun refreshWidgets(context: Context) = supervisorScope {
        val manager = AppWidgetManager.getInstance(context)
        val targets =
            listOf(
                FeedingWidget() to FeedingWidgetReceiver::class.java,
                SleepWidget() to SleepWidgetReceiver::class.java,
                FeedingHorizontalWidget() to FeedingHorizontalWidgetReceiver::class.java,
                FeedingMiniWidget() to FeedingMiniWidgetReceiver::class.java,
            )
        targets
            .flatMap { (widget, receiver) ->
                manager.getAppWidgetIds(ComponentName(context, receiver)).map { appWidgetId ->
                    async {
                        runCatching { pushImmediately(context, widget, AppWidgetId(appWidgetId)) }
                    }
                }
            }
            .awaitAll()
    }

    @OptIn(androidx.glance.ExperimentalGlanceApi::class)
    private suspend fun pushImmediately(context: Context, widget: GlanceAppWidget, id: GlanceId) {
        val views = widget.runComposition(context, id).first()
        AppWidgetManager.getInstance(context)
            .updateAppWidget((id as AppWidgetId).appWidgetId, views)
    }

    private fun refreshNotification(context: Context, lastFeed: BabyEvent?) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(LEGACY_NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        showFeedingStartNotification(context, manager, lastFeed)
    }

    private fun showFeedingStartNotification(
        context: Context,
        manager: NotificationManagerCompat,
        lastFeed: BabyEvent?,
    ) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                        FEEDING_START_CHANNEL,
                        "Быстрая отметка кормления",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply {
                        description = "Отметка завершённого кормления с экрана блокировки"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
            )
        val open =
            PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val actions = feedingStartActions()
        val summary = lastFeedSummary(lastFeed)
        val compact = feedingStartCompactNotification(context, actions, lastFeed)
        val builder =
            NotificationCompat.Builder(context, FEEDING_START_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle("Отметить кормление")
                .setContentText(summary ?: "Одно нажатие — одна запись")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCustomContentView(compact)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        actions.forEach { builder.addAction(0, it.label, action(context, it.command, it.request)) }
        manager.notify(FEEDING_START_NOTIFICATION_ID, builder.build())
    }

    private data class CompactAction(val label: String, val command: String, val request: Int)

    private fun RemoteViews.applyCompactActions(context: Context, actions: List<CompactAction>) {
        listOf(R.id.notification_action_1, R.id.notification_action_2, R.id.notification_action_3)
            .forEachIndexed { index, id ->
                val item = actions.getOrNull(index)
                setViewVisibility(id, if (item == null) View.GONE else View.VISIBLE)
                item?.let {
                    setTextViewText(id, it.label)
                    setInt(
                        id,
                        "setBackgroundResource",
                        if (it.command == "STOP") R.drawable.notification_action_stop
                        else R.drawable.notification_action,
                    )
                    setOnClickPendingIntent(id, action(context, it.command, it.request))
                }
            }
    }

    private fun feedingStartCompactNotification(
        context: Context,
        actions: List<CompactAction>,
        lastFeed: BabyEvent?,
    ) =
        RemoteViews(context.packageName, R.layout.notification_timer_compact).apply {
            if (lastFeed == null) {
                setTextViewText(R.id.notification_chronometer, "Отметить кормление")
            } else {
                // This is deliberately the elapsed time since the last completed
                // feeding, not a timer for an active feeding.
                setChronometer(
                    R.id.notification_chronometer,
                    feedingChronometerBase(
                        lastFeed,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(),
                    ),
                    null,
                    true,
                )
                setString(
                    R.id.notification_chronometer,
                    "setFormat",
                    "${lastFeedCompactLabel(lastFeed)} · %s",
                )
            }
            applyCompactActions(context, actions)
        }

    private fun feedingStartActions() =
        listOf(CompactAction("L", "FEED_LEFT", 21), CompactAction("R", "FEED_RIGHT", 22))

    private fun lastFeedSummary(
        lastFeed: BabyEvent?,
        now: Long = System.currentTimeMillis(),
    ): String? =
        lastFeed?.let {
            val elapsed = (now - (it.endedAt ?: it.startedAt)).coerceAtLeast(0)
            "${lastFeedCompactLabel(it)} · ${if(elapsed<60_000)"только что" else "${formatElapsed(elapsed)} назад"}"
        }

    private fun lastFeedCompactLabel(lastFeed: BabyEvent) =
        when (feedingKindOf(lastFeed.detail)) {
            FeedingKind.LEFT -> "L"
            FeedingKind.RIGHT -> "R"
            FeedingKind.BOTTLE -> bottleVolumeMl(lastFeed.detail)?.let { "$it мл" } ?: "Бут."
        }

    private fun formatElapsed(elapsed: Long): String {
        val minutes = elapsed / 60_000
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин"
            else -> {
                val hours = minutes / 60
                val remainder = minutes % 60
                if (remainder == 0L) "$hours ч" else "$hours ч $remainder мин"
            }
        }
    }

    internal fun feedingChronometerBase(
        lastFeed: BabyEvent,
        wallClockNow: Long,
        elapsedRealtimeNow: Long,
    ): Long =
        elapsedRealtimeNow -
            (wallClockNow - (lastFeed.endedAt ?: lastFeed.startedAt)).coerceAtLeast(0)

    private fun action(context: Context, command: String, request: Int) =
        if (command == "FEED_BOTTLE")
            PendingIntent.getActivity(
                context,
                request,
                MainActivity.bottleIntent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        else
            PendingIntent.getBroadcast(
                context,
                request,
                Intent(context, TimerActionReceiver::class.java).putExtra("command", command),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
}

class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as BabyLogApp
                when (intent.getStringExtra("command")) {
                    "STOP" -> app.repository.stop()
                    "FEED_LEFT" -> app.repository.logFeeding(FeedingKind.LEFT)
                    "FEED_RIGHT" -> app.repository.logFeeding(FeedingKind.RIGHT)
                    "SLEEP_LEFT" -> app.repository.startSleep(SleepPosition.LEFT)
                    "SLEEP_RIGHT" -> app.repository.startSleep(SleepPosition.RIGHT)
                }
                app.familySync.schedule()
                AppSurfaceSync.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                )
        )
            return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.rescheduleAll(context)
                AppSurfaceSync.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
