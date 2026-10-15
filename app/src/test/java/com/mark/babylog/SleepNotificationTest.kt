package com.mark.babylog

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import com.mark.babylog.data.SyncState
import com.mark.babylog.sync.AppSurfaceSync
import com.mark.babylog.sync.TimerActionReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SleepNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_000_000L

    private fun checkAction(event: BabyEvent?, label: String, command: String) {
        // Inspect the outgoing action without starting background database synchronization.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, TimerActionReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0,
        )
        val notification = AppSurfaceSync.sleepNotification(context, event, "Next sleep", now)
        // Inflate the actual collapsed RemoteViews, rather than the native expanded action list.
        val view = notification.contentView.apply(context, FrameLayout(context))
        val button = view.findViewById<TextView>(R.id.sleep_notification_action)
        assertEquals(label, button.text.toString())
        assertTrue(button.performClick())
        val intent =
            shadowOf(context).nextStartedService // Buttons use a broadcast, never an activity.
        assertNull(intent)
        val sent = shadowOf(context).broadcastIntents.last()
        assertEquals(TimerActionReceiver::class.java.name, sent.component?.className)
        assertEquals(command, sent.getStringExtra("command"))
        assertEquals(
            if (command == "SLEEP_WAKE") event?.remoteId else null,
            sent.getStringExtra("sleepId"),
        )
        assertNotNull(notification.bigContentView)
        if (event != null && event.syncState != SyncState.CONFLICT) {
            val timer = view.findViewById<Chronometer>(R.id.sleep_notification_timer)
            assertEquals(
                SystemClock.elapsedRealtime() - (now - (event.endedAt ?: event.startedAt)),
                timer.base,
            )
        }
    }

    @Test
    fun collapsedStartActionIsClickableWithoutExpanding() =
        checkAction(null, "Начать сон", "SLEEP_START")

    @Test
    fun collapsedWakeActionTargetsTheCurrentSleep() =
        checkAction(
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = now - 120_000,
                sleepRecordVersion = 1,
            ),
            "Проснулась",
            "SLEEP_WAKE",
        )

    @Test
    fun awakeTimerStartsAtLastWake() =
        checkAction(
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = now - 180_000,
                endedAt = now - 60_000,
                sleepRecordVersion = 1,
            ),
            "Начать сон",
            "SLEEP_START",
        )

    @Test
    fun conflictedSleepDoesNotOfferWake() =
        checkAction(
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = now - 120_000,
                sleepRecordVersion = 1,
                syncState = SyncState.CONFLICT,
            ),
            "Начать сон",
            "SLEEP_START",
        )
}
