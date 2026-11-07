package com.mark.babylog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal fun sleepChartDays(
    forecast: List<ForecastSleep>,
    now: Long,
    zone: ZoneId,
    days: Int,
    current: CurrentSleepEstimate? = null,
): List<LocalDate> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val tomorrow = today.plusDays(1)
    val tomorrowStart = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
    val history = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
    return if (
        (forecast +
                listOfNotNull(
                    current?.let { ForecastSleep(it.expectedWake, it.expectedWake + 1, 0) }
                ))
            .any {
                it.start < tomorrow.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() &&
                    it.end > tomorrowStart
            }
    )
        history + tomorrow
    else history
}

@Composable
internal fun SleepChart(
    events: List<BabyEvent>,
    forecast: List<ForecastSleep>,
    now: Long,
    zone: ZoneId,
    days: Int,
    onSelect: (BabyEvent) -> Unit,
    current: CurrentSleepEstimate? = null,
    modifier: Modifier = Modifier,
    initialViewport: SleepChartViewport = SleepChartViewport(),
    onEdit: (BabyEvent) -> Unit = {},
    dismissSignal: Int = 0,
) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val colour = MaterialTheme.colorScheme.primary
    val viewport = initialViewport
    var selection by remember(days, zone, today) { mutableStateOf<Pair<LocalDate, String>?>(null) }
    LaunchedEffect(dismissSignal) { selection = null }
    val selectedEvent = events.firstOrNull { it.remoteId == selection?.second }
    val selectedForecast = forecast.firstOrNull { "forecast:${it.start}" == selection?.second }
    val highlight = MaterialTheme.colorScheme.tertiary
    var inspection by
        remember(days, zone, initialViewport) { mutableStateOf<Pair<LocalDate, Float>?>(null) }
    var selectedDay by remember(zone, today) { mutableStateOf(today) }
    val validSessions = remember(events, now) { SleepPredictor.validSessions(events, now) }
    val textMeasurer = rememberTextMeasurer()
    val barTextStyle = MaterialTheme.typography.labelSmall
    Column(modifier.testTag("sleep-chart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Нажмите дату для итогов дня · нажмите сон для подробностей",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "${selectedDay.format(DateTimeFormatter.ofPattern("dd.MM"))} · Дневной сон: ${duration(daytimeSleepTotal(validSessions, selectedDay, now, zone))}",
            Modifier.testTag("sleep-chart-day-total"),
            style = MaterialTheme.typography.titleSmall,
        )
        val inspected = inspection
        val inspectedAxis = inspected?.let { SleepDayAxis(it.first, zone) }
        val inspectedTime =
            inspected?.let {
                inspectedAxis!!.start +
                    (inspectedAxis.duration * (viewport.start + it.second / viewport.scale))
                        .toLong()
            }
        val inspectedSleep =
            inspectedTime?.let { time ->
                events.firstOrNull {
                    it.sleepRecordVersion == 1 && it.startedAt <= time && (it.endedAt ?: now) > time
                }
            }
        Text(
            if (inspected != null && inspectedAxis != null && inspectedTime != null)
                "${Instant.ofEpochMilli(inspectedTime).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM"))} · ${graphClock(inspectedTime, zone, inspectedAxis.changesOffset)}" +
                    (inspectedSleep?.let {
                        " · сон ${graphClock(it.startedAt, zone, inspectedAxis.changesOffset)} — ${it.endedAt?.let { end -> graphClock(end, zone, inspectedAxis.changesOffset) } ?: "сейчас"}"
                    } ?: "")
            else "Время появится при наведении или удержании",
            modifier = Modifier.testTag("sleep-chart-time").heightIn(min = 32.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        val chartDays = sleepChartDays(forecast, now, zone, days, current)
        chartDays.forEachIndexed { index, day ->
            val axis = SleepDayAxis(day, zone)
            val start = axis.start
            val end = axis.end
            fun fraction(t: Long): Float = viewport.fraction(axis.fraction(t))
            fun visible(a: Long, b: Long): Boolean =
                axis.fraction(b) > viewport.start && axis.fraction(a) < viewport.end
            if (axis.changesOffset) {
                val from = Instant.ofEpochMilli(start).atZone(zone).offset
                val to = Instant.ofEpochMilli(end - 1).atZone(zone).offset
                Text(
                    "${day.format(DateTimeFormatter.ofPattern("dd.MM"))} · ${duration(axis.duration)} · UTC$from → UTC$to",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (
                index == 0 ||
                    axis.changesOffset ||
                    SleepDayAxis(chartDays[index - 1], zone).changesOffset
            )
                SleepChartTimeAxis(axis, viewport, zone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.format(DateTimeFormatter.ofPattern("dd.MM")),
                    Modifier.width(54.dp)
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selectedDay == day) highlight.copy(alpha = 0.15f)
                            else Color.Transparent
                        )
                        .semantics {
                            selected = selectedDay == day
                            contentDescription = "Итоги сна за ${day.format(dateFormat)}"
                        }
                        .clickable { selectedDay = day }
                        .wrapContentHeight(),
                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelSmall,
                )
                BoxWithConstraints(
                    Modifier.weight(1f)
                        .height(38.dp)
                        .testTag("sleep-chart-row-$day")
                        .pointerInput(day, viewport) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    when (event.type) {
                                        PointerEventType.Enter,
                                        PointerEventType.Move -> {
                                            if (event.changes.none { it.pressed }) {
                                                val x = event.changes.first().position.x
                                                inspection =
                                                    day to (x / size.width).coerceIn(0f, 1f)
                                            }
                                        }
                                        PointerEventType.Exit -> {
                                            if (inspection?.first == day) inspection = null
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(day, viewport) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { position ->
                                    inspection = day to (position.x / size.width).coerceIn(0f, 1f)
                                },
                                onDrag = { change, _ ->
                                    inspection =
                                        day to (change.position.x / size.width).coerceIn(0f, 1f)
                                    change.consume()
                                },
                                onDragEnd = { inspection = null },
                                onDragCancel = { inspection = null },
                            )
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (day == today) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
                        )
                ) {
                    val width = maxWidth
                    sleepChartTicks(axis, viewport)
                        .map { axis.fraction(it) }
                        .forEach { time ->
                            val tick = viewport.fraction(time)
                            Box(
                                Modifier.offset(x = width * tick)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                            )
                        }
                    events
                        .filter {
                            it.sleepRecordVersion == 1 &&
                                it.startedAt < end &&
                                (it.endedAt ?: now) > start &&
                                visible(it.startedAt, it.endedAt ?: now)
                        }
                        .forEach { e ->
                            val left = fraction(e.startedAt)
                            val right = fraction(e.endedAt ?: now)
                            val isSelected = selectedEvent?.remoteId == e.remoteId
                            val emphasis by
                                animateFloatAsState(
                                    if (isSelected) 1f else 0f,
                                    tween(260),
                                    label = "sleep-emphasis",
                                )
                            val opacity by
                                animateFloatAsState(
                                    if (selection == null || isSelected) 1f else 0.45f,
                                    tween(260),
                                    label = "sleep-opacity",
                                )
                            Box(
                                Modifier.offset(x = width * left)
                                    .width(
                                        (width * (right - left).coerceAtLeast(0.005f))
                                            .coerceAtLeast(4.dp)
                                    )
                                    .fillMaxHeight()
                                    .padding(vertical = 5.dp)
                                    .graphicsLayer {
                                        scaleY = 1f + 0.12f * emphasis
                                        alpha = opacity
                                    }
                                    .drawBehind {
                                        val extra = 2.dp.toPx() * emphasis
                                        drawRoundRect(
                                            highlight.copy(alpha = 0.35f),
                                            topLeft = Offset(-extra, -extra),
                                            size =
                                                Size(
                                                    size.width + extra * 2,
                                                    size.height + extra * 2,
                                                ),
                                            cornerRadius = CornerRadius(6.dp.toPx()),
                                        )
                                    }
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (e.syncState == SyncState.CONFLICT)
                                            MaterialTheme.colorScheme.error
                                        else if (isSelected) MaterialTheme.colorScheme.tertiary
                                        else colour
                                    )
                                    .semantics {
                                        selected = isSelected
                                        contentDescription =
                                            "${day.format(dateFormat)}: сон ${graphClock(e.startedAt, zone, axis.changesOffset)} — ${e.endedAt?.let { graphClock(it, zone, axis.changesOffset) } ?: "сейчас"}"
                                    }
                                    .clickable {
                                        selectedDay = day
                                        selection =
                                            if (selection == day to e.remoteId) null
                                            else day to e.remoteId
                                        if (selection != null) onSelect(e)
                                    },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                val label = graphClock(e.startedAt, zone, axis.changesOffset)
                                val labelWidth =
                                    textMeasurer.measure(label, barTextStyle).size.width
                                val visibleWidth = width * (right - left)
                                if (
                                    e.startedAt >= start &&
                                        axis.fraction(e.startedAt) >= viewport.start &&
                                        with(androidx.compose.ui.platform.LocalDensity.current) {
                                            visibleWidth.toPx() >= labelWidth + 8.dp.toPx()
                                        }
                                ) {
                                    Text(
                                        label,
                                        Modifier.padding(horizontal = 4.dp),
                                        color =
                                            if (e.syncState == SyncState.CONFLICT)
                                                MaterialTheme.colorScheme.onError
                                            else if (isSelected)
                                                MaterialTheme.colorScheme.onTertiary
                                            else MaterialTheme.colorScheme.onPrimary,
                                        style = barTextStyle,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    forecast
                        .filter { it.start < end && it.end > start && visible(it.start, it.end) }
                        .forEach { f ->
                            val isSelected = selectedForecast == f
                            val left = fraction(f.start)
                            val right = fraction(f.end)
                            Box(
                                Modifier.offset(x = width * left)
                                    .width(width * (right - left).coerceAtLeast(0.005f))
                                    .fillMaxHeight()
                                    .padding(vertical = if (isSelected) 7.dp else 10.dp)
                                    .border(
                                        1.dp,
                                        colour.copy(alpha = 0.75f),
                                        RoundedCornerShape(3.dp),
                                    )
                                    .background(
                                        colour.copy(alpha = if (isSelected) 0.22f else 0.08f)
                                    )
                                    .semantics {
                                        contentDescription =
                                            "Прогноз сна ${graphClock(f.start, zone, axis.changesOffset)} — ${graphClock(f.end, zone, axis.changesOffset)}"
                                        selected = isSelected
                                    }
                                    .clickable {
                                        selectedDay = day
                                        selection =
                                            if (selection == day to "forecast:${f.start}") null
                                            else day to "forecast:${f.start}"
                                    }
                            )
                        }
                    current
                        ?.expectedWake
                        ?.takeIf { it >= start && it < end && visible(it, it + 1) }
                        ?.let { time ->
                            Box(
                                Modifier.offset(x = (width - 3.dp) * fraction(time))
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(colour)
                                    .semantics {
                                        contentDescription =
                                            "Прогноз пробуждения около ${clock(time, zone)}"
                                    }
                                    .clickable {
                                        selectedDay = day
                                        events
                                            .firstOrNull {
                                                it.endedAt == null &&
                                                    it.startedAt == current.startedAt
                                            }
                                            ?.let {
                                                selection =
                                                    if (selection == day to it.remoteId) null
                                                    else day to it.remoteId
                                            }
                                    }
                            )
                        }
                    if (day == today && visible(now, now + 1))
                        Box(
                            Modifier.offset(x = width * fraction(now))
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.tertiary)
                                .semantics { contentDescription = "Сейчас ${clock(now, zone)}" }
                        )
                    inspection?.let { (_, position) ->
                        Box(
                            Modifier.offset(x = (width * position).coerceAtMost(width - 1.dp))
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.onSurface)
                                .testTag("sleep-chart-time-guide-$day")
                        )
                        if (inspection?.first == day && inspectedTime != null) {
                            Layout(
                                modifier = Modifier.fillMaxSize(),
                                content = {
                                    Text(
                                        graphClock(inspectedTime, zone, axis.changesOffset),
                                        Modifier.testTag("sleep-chart-time-badge")
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.inverseSurface)
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                        color = MaterialTheme.colorScheme.inverseOnSurface,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                },
                            ) { measurables, constraints ->
                                val label =
                                    measurables
                                        .single()
                                        .measure(constraints.copy(minWidth = 0, minHeight = 0))
                                layout(constraints.maxWidth, constraints.maxHeight) {
                                    label.placeRelative(
                                        (constraints.maxWidth * position)
                                            .toInt()
                                            .coerceIn(
                                                0,
                                                (constraints.maxWidth - label.width).coerceAtLeast(
                                                    0
                                                ),
                                            ),
                                        0,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible =
                    (selectedEvent != null || selectedForecast != null) && selection?.first == day,
                enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                selectedEvent
                    ?.takeIf { selection?.first == day }
                    ?.let { event ->
                        SleepDetailCard(
                            event,
                            now,
                            zone,
                            onEdit = { onEdit(event) },
                            onDismiss = { selection = null },
                            awakeBefore = awakeBeforeSleep(events, event.startedAt),
                            current =
                                current?.takeIf {
                                    event.endedAt == null && it.startedAt == event.startedAt
                                },
                        )
                    }
                selectedForecast
                    ?.takeIf { selection?.first == day }
                    ?.let { projected ->
                        ForecastDetailCard(
                            projected,
                            zone,
                            awakeBefore =
                                (forecast
                                        .filter { it.end <= projected.start }
                                        .maxOfOrNull { it.end }
                                        ?: current?.expectedWake?.takeIf { it <= projected.start }
                                        ?: events
                                            .mapNotNull { it.endedAt }
                                            .filter { it <= projected.start }
                                            .maxOrNull())
                                    ?.let { projected.start - it },
                            onDismiss = { selection = null },
                        )
                    }
            }
            current
                ?.expectedWake
                ?.takeIf { it >= start && it < end && visible(it, it + 1) }
                ?.let { time ->
                    Text(
                        "Около ${clock(time / 300_000 * 300_000, zone)}",
                        modifier = Modifier.fillMaxWidth().padding(start = 54.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colour,
                    )
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Сон" to 1f, "Прогноз" to 0.08f).forEach { (label, alpha) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .border(
                                if (alpha < 1f) 1.dp else 0.dp,
                                colour,
                                RoundedCornerShape(2.dp),
                            )
                            .background(colour.copy(alpha = alpha))
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "│ Сейчас",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SleepDetailCard(
    event: BabyEvent,
    now: Long,
    zone: ZoneId,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    awakeBefore: Long? = null,
    current: CurrentSleepEstimate? = null,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(event.remoteId) {
        // Wait for expansion so the scroll includes the full card and its actions.
        delay(280)
        bringIntoView.bringIntoView()
    }
    OutlinedCard(
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).testTag("sleep-detail-card"),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Выбранный сон",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
            Text(
                "${clock(event.startedAt, zone)} — ${event.endedAt?.let { clock(it, zone) } ?: "сейчас"}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                (if (event.endedAt == null) "Спит · " else "Длительность · ") +
                    duration((event.endedAt ?: now) - event.startedAt)
            )
            awakeBefore?.let { Text("Бодрствование перед сном · ${duration(it)}") }
            if (event.endedAt == null) {
                if (current == null) Text("Для прогноза пробуждения пока мало данных")
                else {
                    Text(
                        "Ожидаемая длительность · ${duration(current.expectedWake - event.startedAt)}"
                    )
                    Text("Пробуждение около ${clock(current.expectedWake, zone)}")
                    PredictionRange(
                        current.earliestWake,
                        current.expectedWake,
                        current.latestWake,
                        now,
                        zone,
                    )
                    Text(
                        predictionExplanation(null, current, true),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (now > current.latestWake) Text("Окно прогноза прошло · сон продолжается")
                }
            }
            if (event.syncState == SyncState.CONFLICT)
                Text("Проверьте время: конфликт записей", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text("Изменить сон")
            }
        }
    }
}

@Composable
private fun SleepChartTimeAxis(axis: SleepDayAxis, viewport: SleepChartViewport, zone: ZoneId) {
    val ticks = sleepChartTicks(axis, viewport)
    Layout(
        modifier = Modifier.fillMaxWidth().padding(start = 54.dp),
        content = {
            ticks.forEach { time ->
                Text(
                    if (time == axis.end) "24:00" else clock(time, zone),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    ) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, labels.maxOfOrNull { it.height } ?: 0) {
            labels.forEachIndexed { index, label ->
                val center = viewport.fraction(axis.fraction(ticks[index])) * constraints.maxWidth
                label.placeRelative(
                    (center.toInt() - label.width / 2).coerceIn(
                        0,
                        (constraints.maxWidth - label.width).coerceAtLeast(0),
                    ),
                    0,
                )
            }
        }
    }
}

@Composable
internal fun SleepChartPeriod(days: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Период: $days дней ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(7, 14, 30).forEach { count ->
                DropdownMenuItem(
                    text = { Text("$count дней") },
                    onClick = {
                        onChange(count)
                        expanded = false
                    },
                    modifier = Modifier.semantics { selected = days == count },
                )
            }
        }
    }
}

internal fun awakeBeforeSleep(events: List<BabyEvent>, start: Long): Long? =
    events
        .filter { it.sleepRecordVersion == 1 && it.startedAt < start }
        .mapNotNull { it.endedAt }
        .filter { it <= start }
        .maxOrNull()
        ?.let { start - it }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForecastDetailCard(
    forecast: ForecastSleep,
    zone: ZoneId,
    awakeBefore: Long?,
    onDismiss: () -> Unit,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(forecast.start) {
        delay(280)
        bringIntoView.bringIntoView()
    }
    OutlinedCard(
        Modifier.fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .testTag("forecast-detail-card")
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Прогноз сна",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
            Text(
                "${clock(forecast.start, zone)} — ${clock(forecast.end, zone)}",
                fontWeight = FontWeight.SemiBold,
            )
            Text("Ожидаемая длительность · ${duration(forecast.end - forecast.start)}")
            awakeBefore?.let { Text("Бодрствование перед сном · ${duration(it)}") }
            val uncertainty = forecast.uncertaintyMinutes * 60_000L
            Text(
                "Окно начала · ${clock(forecast.start - uncertainty, zone)} — ${clock(forecast.start + uncertainty, zone)}"
            )
            Text(
                "Ориентировочный прогноз. Время и длительность могут измениться.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun Modifier.dismissSleepChartOnOutsidePress(
    bounds: Rect?,
    onDismiss: () -> Unit,
): Modifier =
    pointerInput(bounds) {
        // Observe before child controls handle the press, without consuming their gestures.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (bounds?.contains(down.position) != true) onDismiss()
        }
    }
