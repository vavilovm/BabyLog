package com.mark.babylog.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.mark.babylog.BabyLogApp
import com.mark.babylog.MainActivity
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync

data class WidgetButton(val label: String, val command: String, val imageRes: Int? = null)

data class WidgetUi(val title: String, val status: String, val buttons: List<WidgetButton>)

fun feedingWidgetUi(last: BabyEvent?, now: Long): WidgetUi {
    val buttons =
        listOf(FeedingKind.LEFT to "L", FeedingKind.RIGHT to "R", FeedingKind.BOTTLE to "Бутылочка")
            .map { (kind, label) -> WidgetButton(label, kind.name) }
    return WidgetUi(
        "Отметить кормление",
        if (last != null)
            "Последнее: ${feedLabel(last.detail)} · ${elapsed(now-(last.endedAt?:last.startedAt))} назад"
        else "Одно нажатие — одна запись",
        buttons,
    )
}

fun sleepWidgetUi(last: BabyEvent?, now: Long): WidgetUi {
    val active =
        last?.takeIf {
            it.sleepRecordVersion == 1 && it.endedAt == null && it.syncState != SyncState.CONFLICT
        }
    return WidgetUi(
        "Сон малыша",
        if (active != null) "Спит · ${elapsed(now - active.startedAt)}"
        else
            last
                ?.takeIf { it.sleepRecordVersion == 1 }
                ?.endedAt
                ?.let { "Бодрствует · ${elapsed(now - it)}" } ?: "Начнём наблюдать её ритм",
        listOf(
            WidgetButton(
                if (active != null) "Проснулась" else "Начать сон",
                if (active != null) "WAKE" else "SLEEP",
            )
        ),
    )
}

private suspend fun sleepSurfaceUi(context: Context): WidgetUi {
    val d = dao(context)
    val now = System.currentTimeMillis()
    val ui = sleepWidgetUi(d.activeSleep() ?: d.lastSleep(), now)
    if (d.activeSleep() != null) return ui
    val next =
        com.mark.babylog.sleep.SleepPredictor.calculate(
                d.sleepSnapshot(now - 32L * 86_400_000),
                now,
                profile = d.profile(),
            )
            .next ?: return ui
    val zone = java.time.ZoneId.systemDefault()
    val format = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    fun time(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).format(format)
    return ui.copy(
        status =
            if (now > next.latest) "Окно сна прошло · проверьте журнал"
            else "Сон ≈ ${time(next.earliest)}–${time(next.latest)}"
    )
}

private val kindKey = ActionParameters.Key<String>("kind")
private val surfaceKey = ActionParameters.Key<String>("surface")

private fun dao(context: Context) = (context.applicationContext as BabyLogApp).database.events()

class FeedAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as BabyLogApp
        if (parameters[kindKey] == "WAKE") app.repository.finishSleep()
        else app.repository.beginSleep()
        app.familySync.schedule()
        AppSurfaceSync.refresh(context)
    }
}

class SleepAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as BabyLogApp
        if (parameters[kindKey] == "WAKE") app.repository.finishSleep()
        else app.repository.beginSleep()
        app.familySync.schedule()
        AppSurfaceSync.refreshFromWidget(context, glanceId, SleepWidget())
    }
}

class FeedingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = dao(context)
        val now = System.currentTimeMillis()
        val ui = sleepSurfaceUi(context)
        provideContent { StandardWidgetBox(ui, SleepAction::class.java, "sleep") }
    }
}

class FeedingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FeedingWidget()
}

class SleepWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = dao(context)
        val now = System.currentTimeMillis()
        val ui = sleepSurfaceUi(context)
        provideContent { StandardWidgetBox(ui, SleepAction::class.java, "sleep") }
    }
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SleepWidget()
}

class FeedingHorizontalWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = dao(context)
        val now = System.currentTimeMillis()
        val ui = sleepSurfaceUi(context)
        provideContent { HorizontalWidgetBox(ui, SleepAction::class.java) }
    }
}

class FeedingHorizontalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FeedingHorizontalWidget()
}

class FeedingMiniWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = sleepSurfaceUi(context)
        provideContent { MiniWidgetBox(ui.buttons, SleepAction::class.java) }
    }
}

class FeedingMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FeedingMiniWidget()
}

@Composable
private fun StandardWidgetBox(ui: WidgetUi, action: Class<out ActionCallback>, surface: String) {
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xFFF4EFF7)))
            .clickable(actionStartActivity<MainActivity>())
            .padding(12.dp)
    ) {
        WidgetHeader(ui)
        Row(GlanceModifier.fillMaxWidth()) {
            WidgetButton(ui.buttons[0], action, GlanceModifier.defaultWeight(), surface)
            Spacer(GlanceModifier.width(8.dp))
            WidgetButton(ui.buttons[1], action, GlanceModifier.defaultWeight(), surface)
        }
        if (ui.buttons.size > 2) {
            Spacer(GlanceModifier.height(7.dp))
            WidgetButton(ui.buttons[2], action, GlanceModifier.fillMaxWidth(), surface)
        }
    }
}

@Composable
private fun HorizontalWidgetBox(ui: WidgetUi, action: Class<out ActionCallback>) {
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xFFF4EFF7)))
            .clickable(actionStartActivity<MainActivity>())
            .padding(12.dp)
    ) {
        WidgetHeader(ui)
        Row(GlanceModifier.fillMaxWidth()) {
            ui.buttons.forEachIndexed { index, item ->
                if (index > 0) Spacer(GlanceModifier.width(7.dp))
                WidgetButton(item, action, GlanceModifier.defaultWeight(), "horizontal")
            }
        }
    }
}

@Composable
private fun MiniWidgetBox(buttons: List<WidgetButton>, action: Class<out ActionCallback>) {
    Row(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xFFF4EFF7)))
            .clickable(actionStartActivity<MainActivity>())
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.forEachIndexed { index, item ->
            if (index > 0) Spacer(GlanceModifier.width(6.dp))
            WidgetButton(item, action, GlanceModifier.defaultWeight(), "mini")
        }
    }
}

@Composable
private fun WidgetHeader(ui: WidgetUi) {
    Text(ui.title, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp))
    Text(ui.status, style = TextStyle(fontSize = 13.sp))
    Spacer(GlanceModifier.height(6.dp))
}

@Composable
private fun WidgetButton(
    item: WidgetButton,
    action: Class<out ActionCallback>,
    modifier: GlanceModifier,
    surface: String,
) {
    Box(
        modifier
            .height(42.dp)
            .background(ColorProvider(Color(0xFF6F579C)))
            .cornerRadius(16.dp)
            .clickable(
                actionRunCallback(
                    action,
                    actionParametersOf(kindKey to item.command, surfaceKey to surface),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (item.imageRes != null)
            Image(
                ImageProvider(item.imageRes),
                item.label.ifBlank { if (item.command == "LEFT") "Левый бок" else "Правый бок" },
                GlanceModifier.size(38.dp),
            )
        else
            Text(
                item.label,
                style =
                    TextStyle(
                        color = ColorProvider(Color.White),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
                maxLines = 1,
            )
    }
}

private fun feedLabel(v: String) =
    when (v) {
        "LEFT" -> "L"
        "RIGHT" -> "R"
        else -> "бутылочка"
    }

private fun sleepLabel(v: String) =
    when (v) {
        "LEFT" -> "голова слева"
        "RIGHT" -> "голова справа"
        else -> "другое"
    }

private fun elapsed(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}"
    else "$m:${s.toString().padStart(2,'0')}"
}
