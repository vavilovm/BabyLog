package com.mark.babylog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import java.time.*
import kotlinx.coroutines.delay

internal fun daytimeSleepTotal(
    sessions: List<BabyEvent>,
    day: LocalDate,
    now: Long,
    zone: ZoneId,
): Long {
    val dayEnd = minOf(now, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    return sessions.sumOf { session ->
        val start = Instant.ofEpochMilli(session.startedAt).atZone(zone)
        if (start.toLocalDate() == day && start.hour in 6..17) {
            (minOf(session.endedAt ?: now, dayEnd) - session.startedAt).coerceAtLeast(0)
        } else 0L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepStatusCard(
    active: BabyEvent?,
    lastWake: Long?,
    now: Long,
    zone: ZoneId,
    onStart: () -> Unit,
    onWake: () -> Unit,
    onEdit: (BabyEvent) -> Unit,
    liveDuration: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (active != null) "Сейчас спит" else "Сейчас бодрствует",
                    style = MaterialTheme.typography.titleMedium,
                )
                val since = active?.startedAt ?: lastWake
                if (liveDuration) SleepElapsedText(since, rememberElapsedClock(now, since))
                else SleepElapsedText(since, rememberUpdatedState(now))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { if (active == null) onStart() else onWake() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(
                        if (active == null) "Начать сон" else "Проснулась",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                active?.let {
                    TextButton(
                        onClick = { onEdit(it) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("С ${clock(it.startedAt, zone)}")
                        Icon(
                            Icons.Outlined.Edit,
                            "Изменить текущий сон",
                            Modifier.padding(start = 4.dp).size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberElapsedClock(initial: Long, since: Long?): State<Long> {
    val clock = remember(since) { mutableLongStateOf(initial) }
    LaunchedEffect(since) {
        while (true) {
            clock.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return clock
}

/** Read the second clock only here; publish a new text value once per elapsed minute. */
@Composable
internal fun SleepElapsedText(since: Long?, clock: State<Long>) {
    val text by
        remember(since, clock) {
            derivedStateOf {
                since?.let { sleepStatusDuration(clock.value - it) } ?: "Начнём наблюдать её ритм"
            }
        }
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NextSleepCard(
    estimate: SleepEstimate?,
    sleeping: Boolean,
    now: Long,
    zone: ZoneId,
    current: CurrentSleepEstimate? = null,
) {
    val expected = if (sleeping) current?.expectedWake else estimate?.expected
    val earliest = if (sleeping) current?.earliestWake else estimate?.earliest
    val latest = if (sleeping) current?.latestWake else estimate?.latest
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (sleeping) "Пробуждение" else "Следующий сон",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        expected == null || earliest == null || latest == null -> "Пока мало данных"
                        now > latest -> "Окно прогноза прошло"
                        else -> "Около " + wakeClock(expected / 300_000 * 300_000, now, zone)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expected != null && earliest != null && latest != null && now <= latest) {
                PredictionRange(earliest, expected, latest, now, zone)
                if (expected < now)
                    Text(
                        "Ожидаемое время уже прошло; окно ещё открыто.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (sleeping && current != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Длительность ~${duration(expected - current.startedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            (if (now <= expected) "До ориентира · " else "После ориентира · ") +
                                duration(kotlin.math.abs(expected - now)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PredictionRange(
    earliest: Long,
    expected: Long,
    latest: Long,
    now: Long,
    zone: ZoneId,
) {
    val position =
        ((expected - earliest).toFloat() / (latest - earliest).coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            Box(
                Modifier.fillMaxWidth()
                    .height(8.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            )
            Box(
                Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.secondary)
            )
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary)
            )
            Box(
                Modifier.offset(x = (maxWidth - 4.dp) * position)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                wakeClock(earliest, now, zone),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                wakeClock(latest, now, zone),
                Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

internal fun sleepStatusDuration(ms: Long): String {
    val elapsed = ms.coerceAtLeast(0)
    return if (elapsed < 60_000) "${elapsed / 1_000} с" else duration(elapsed)
}

internal fun predictionExplanation(
    estimate: SleepEstimate?,
    current: CurrentSleepEstimate?,
    sleeping: Boolean,
): String =
    if (sleeping)
        "По ${current?.samples ?: 0} похожим ${if (current?.night == true) "ночным" else "дневным"} снам: время начала и длительность. Это ориентир."
    else
        when (estimate?.model) {
            "Личная адаптивная модель" ->
                "По ${estimate.samples} периодам: время суток, предыдущий сон и сон за сутки."
            "Личный ритм по времени суток" ->
                "По ${estimate.samples} периодам бодрствования с учётом времени суток."
            else -> "По ${estimate?.samples ?: 0} недавним периодам бодрствования."
        } +
            if (estimate?.preliminary == true) " Предварительный прогноз."
            else " Время может отличаться."

@Composable
internal fun FinishedSleepPrompt(
    event: BabyEvent,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var remaining by remember(event.remoteId) { mutableFloatStateOf(1f) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    val timeout =
        accessibility?.calculateRecommendedTimeoutMillis(
            15_000L,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: 15_000L
    LaunchedEffect(event.remoteId, timeout) {
        val started = withFrameNanos { it }
        while (remaining > 0f) {
            withFrameNanos { frame ->
                remaining =
                    (1.0 - (frame - started) / 1_000_000.0 / timeout).toFloat().coerceIn(0f, 1f)
            }
        }
        onDismiss()
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val countdownColor = if (dark) Color(0xFFFFC166) else Color(0xFF995000)
    val promptColor = if (dark) Color(0xFF382B17) else Color(0xFFFFF0D6)
    val textColor = if (dark) Color(0xFFFFE6BF) else Color(0xFF3F2A0D)
    Surface(
        Modifier.padding(horizontal = 16.dp)
            .fillMaxWidth()
            .testTag("finished-sleep-prompt")
            .pointerInput(event.remoteId) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onHorizontalDrag = { change, amount ->
                        distance += amount
                        change.consume()
                    },
                    onDragEnd = { if (kotlin.math.abs(distance) > 48.dp.toPx()) onDismiss() },
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = promptColor,
        contentColor = textColor,
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(remaining, 0f..1f) }
                .drawWithCache {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    val outline =
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    Rect(inset, inset, size.width - inset, size.height - inset),
                                    CornerRadius(16.dp.toPx() - inset),
                                )
                            )
                        }
                    val measure = PathMeasure().apply { setPath(outline, true) }
                    val segment = Path()
                    val style = Stroke(stroke, cap = StrokeCap.Round)
                    onDrawWithContent {
                        drawContent()
                        drawPath(outline, countdownColor.copy(alpha = 0.2f), style = style)
                        segment.reset()
                        measure.getSegment(
                            measure.length * (1f - remaining),
                            measure.length,
                            segment,
                            true,
                        )
                        drawPath(segment, countdownColor, style = style)
                    }
                }
        ) {
            Text(
                "Проснулась в ${clock(event.endedAt ?: event.startedAt, zone)}",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = countdownColor),
                ) {
                    Text("Изменить время")
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = countdownColor),
                ) {
                    Text("Скрыть")
                }
            }
        }
    }
}
