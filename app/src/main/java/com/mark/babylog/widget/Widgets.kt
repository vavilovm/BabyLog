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
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.*
import com.mark.babylog.BabyLogApp
import com.mark.babylog.MainActivity
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync

data class WidgetButton(val label: String, val command: String, val imageRes: Int? = null)

data class WidgetUi(
    val title: String,
    val status: String,
    val buttons: List<WidgetButton>,
    val prediction: String? = null,
    val summary: String? = null,
)

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
    val active = d.activeSleep()
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val records = d.sleepSnapshot(start)
    val ui =
        sleepWidgetUi(active ?: d.lastSleep(), now)
            .copy(summary = dailySleepSummary(records, start, now))
    if (active != null) return ui
    val next =
        com.mark.babylog.sleep.SleepPredictor.calculate(
                d.sleepSnapshot(now - 32L * 86_400_000),
                now,
                profile = d.profile(),
            )
            .next ?: return ui
    val format = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    fun time(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).format(format)
    return ui.copy(
        prediction =
            if (now > next.latest) "Окно сна прошло · проверьте журнал"
            else "Сон ≈ ${time(next.earliest)}–${time(next.latest)}"
    )
}

private val kindKey = ActionParameters.Key<String>("kind")

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
        AppSurfaceSync.refresh(context)
    }
}

class FeedingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = sleepSurfaceUi(context)
        provideContent { SleepCard(ui, showSummary = true) }
    }
}

class FeedingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FeedingWidget()
}

class SleepWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = sleepSurfaceUi(context)
        provideContent { SleepCard(ui, showSummary = false) }
    }
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SleepWidget()
}

class FeedingHorizontalWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = sleepSurfaceUi(context)
        provideContent { SleepStrip(ui) }
    }
}

class FeedingHorizontalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FeedingHorizontalWidget()
}

class FeedingMiniWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = sleepSurfaceUi(context)
        provideContent { SleepSquare(ui) }
    }
}

class FeedingMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FeedingMiniWidget()
}

// Neutral surfaces and a single teal accent stay legible in both launcher themes.
private val cardColor = ColorProvider(Color(0xFFF8FAF9), Color(0xFF172421))
private val inkColor = ColorProvider(Color(0xFF183C35), Color(0xFFE7F3EE))
private val mutedColor = ColorProvider(Color(0xFF536C64), Color(0xFFA9C1B7))
private val accentColor = ColorProvider(Color(0xFF246B59), Color(0xFF92D6BC))
private val actionInk = ColorProvider(Color.White, Color(0xFF123C30))

@Composable
private fun SleepCard(ui: WidgetUi, showSummary: Boolean) {
    val height = LocalSize.current.height
    Column(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(cardColor)
            .cornerRadius(24.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(16.dp)
    ) {
        Caption(if (showSummary) "BABYLOG · СЕГОДНЯ" else "BABYLOG · СОН")
        Spacer(GlanceModifier.height(8.dp))
        Text(
            ui.status,
            style = TextStyle(color = inkColor, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            maxLines = 2,
        )
        if (height >= 180.dp) {
            Spacer(GlanceModifier.height(8.dp))
            Caption(
                ui.prediction
                    ?: if (ui.buttons.first().command == "WAKE") "Тихого сна малышу"
                    else "Новый сон — одним касанием"
            )
        }
        if (showSummary && height >= 240.dp) {
            Spacer(GlanceModifier.height(16.dp))
            Caption("СОН ЗА СЕГОДНЯ")
            Text(
                ui.summary ?: "Пока нет записей",
                style = TextStyle(color = inkColor, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 2,
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        SleepButton(ui.buttons.first(), GlanceModifier.fillMaxWidth())
    }
}

@Composable
private fun SleepStrip(ui: WidgetUi) {
    Row(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(cardColor)
            .cornerRadius(24.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Caption("BABYLOG · СОН")
            Text(
                ui.status,
                style = TextStyle(color = inkColor, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 2,
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        SleepButton(ui.buttons.first(), GlanceModifier.width(112.dp))
    }
}

@Composable
private fun SleepSquare(ui: WidgetUi) {
    val item = ui.buttons.first()
    // The entire 1 × 1 tile is the action; no padded button inside another box.
    Column(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(accentColor)
            .cornerRadius(24.dp)
            .clickable(actionRunCallback<SleepAction>(actionParametersOf(kindKey to item.command)))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (item.command == "WAKE") "☀" else "☾",
            style = TextStyle(color = actionInk, fontSize = 24.sp),
        )
        Text(
            item.label,
            style =
                TextStyle(
                    color = actionInk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            maxLines = 2,
        )
    }
}

@Composable
private fun Caption(value: String) {
    Text(value, style = TextStyle(color = mutedColor, fontSize = 12.sp), maxLines = 2)
}

@Composable
private fun SleepButton(item: WidgetButton, modifier: GlanceModifier) {
    Box(
        modifier
            .height(48.dp)
            .background(accentColor)
            .cornerRadius(16.dp)
            .clickable(actionRunCallback<SleepAction>(actionParametersOf(kindKey to item.command))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            item.label,
            style =
                TextStyle(
                    color = actionInk,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            maxLines = 2,
        )
    }
}

internal fun dailySleepSummary(records: List<BabyEvent>, start: Long, now: Long): String {
    val valid =
        records.filter {
            it.type == EventType.SLEEP &&
                it.sleepRecordVersion == 1 &&
                it.deletedAt == null &&
                it.syncState != SyncState.CONFLICT &&
                it.startedAt < now &&
                (it.endedAt ?: now) > start
        }
    val minutes =
        valid.sumOf {
            ((it.endedAt ?: now).coerceAtMost(now) - it.startedAt.coerceAtLeast(start))
                .coerceAtLeast(0)
        } / 60_000
    return if (valid.isEmpty()) "Пока нет записей"
    else "${minutes / 60} ч ${minutes % 60} мин · эпизодов: ${valid.size}"
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
