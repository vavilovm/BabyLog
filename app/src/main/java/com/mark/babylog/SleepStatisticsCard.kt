package com.mark.babylog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.sleep.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

private const val HOUR_MS = 3_600_000L

private fun statDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes == 0L -> "0 мин"
        minutes % 60 == 0L -> "${minutes / 60} ч"
        minutes >= 60 -> "${minutes / 60} ч ${minutes % 60} мин"
        else -> "$minutes мин"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepTotals(sessions: List<BabyEvent>, now: Long, zone: ZoneId) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val history =
        remember(sessions, now, zone, today) {
            (14 downTo 0).map {
                sleepDayStatistics(sessions, today.minusDays(it.toLong()), now, zone)
            }
        }
    var weekOffset by rememberSaveable(today.toString()) { mutableIntStateOf(0) }
    val earliest =
        sessions.minOfOrNull { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            ?: today
    val maxOffset = (ChronoUnit.DAYS.between(earliest, today).coerceAtLeast(0) / 7).toInt()
    val offset = weekOffset.coerceIn(0, maxOffset)
    val weekEnd = today.minusWeeks(offset.toLong())
    val week =
        remember(sessions, now, zone, weekEnd) {
            (6 downTo 0).map {
                sleepDayStatistics(sessions, weekEnd.minusDays(it.toLong()), now, zone)
            }
        }
    var selectedDate by rememberSaveable(today.toString()) { mutableStateOf(today.toString()) }
    val chosen = week.firstOrNull { it.day.toString() == selectedDate } ?: week.last()
    fun changeWeek(nextOffset: Int) {
        val next = nextOffset.coerceIn(0, maxOffset)
        selectedDate = chosen.day.plusWeeks((offset - next).toLong()).toString()
        weekOffset = next
    }
    val average = sleepPeriodAverage(history.drop(7).dropLast(1), today)
    val previous = sleepPeriodAverage(history.take(7), today)
    val dayColor = MaterialTheme.colorScheme.primary
    val nightColor = MaterialTheme.colorScheme.tertiary
    var definitions by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Статистика сна",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { definitions = true }) { Text("Как считаем") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatLegend("Дневной", dayColor)
                StatLegend("Ночной", nightColor)
            }
            val scale =
                (ceil((week.maxOfOrNull { it.totalMs } ?: 0).toDouble() / HOUR_MS)
                    .toLong()
                    .coerceAtLeast(1)) * HOUR_MS
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { changeWeek(offset + 1) },
                    enabled = offset < maxOffset,
                    modifier = Modifier.semantics { contentDescription = "Предыдущая неделя" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                }
                Text(
                    "${week.first().day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${weekEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(
                    onClick = { changeWeek(offset - 1) },
                    enabled = offset > 0,
                    modifier = Modifier.semantics { contentDescription = "Следующая неделя" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            if (offset > 0)
                TextButton(
                    onClick = {
                        weekOffset = 0
                        selectedDate = today.toString()
                    }
                ) {
                    Text("К сегодня")
                }
            Text("Шкала до ${scale / HOUR_MS} ч", style = MaterialTheme.typography.labelSmall)
            Row(
                Modifier.fillMaxWidth().testTag("sleep-statistics-week").pointerInput(
                    offset,
                    maxOffset,
                    chosen.day,
                ) {
                    var distance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            distance += amount
                        },
                        onDragEnd = {
                            if (abs(distance) > 48.dp.toPx())
                                changeWeek(offset + if (distance > 0) 1 else -1)
                        },
                    )
                },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { day ->
                    val isChosen = day.day == chosen.day
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isChosen) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            )
                            .testTag("sleep-statistics-day-${day.day}")
                            .clickable(role = Role.Button) { selectedDate = day.day.toString() }
                            .semantics(mergeDescendants = true) {
                                selected = isChosen
                                contentDescription =
                                    "${day.day.format(DateTimeFormatter.ofPattern("dd.MM"))}: " +
                                        if (day.hasRecords)
                                            "всего ${statDuration(day.totalMs)}, днём ${statDuration(day.daytimeMs)}, ночью ${statDuration(day.nighttimeMs)}, бодрствование ${statDuration(day.awakeMs)}"
                                        else "нет записей"
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)
                                    )
                            )
                            Column(
                                Modifier.fillMaxWidth()
                                    .fillMaxHeight((day.totalMs.toFloat() / scale).coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(5.dp))
                            ) {
                                if (day.daytimeMs > 0)
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .weight(day.daytimeMs.toFloat())
                                            .background(dayColor)
                                    )
                                if (day.nighttimeMs > 0)
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .weight(day.nighttimeMs.toFloat())
                                            .background(nightColor)
                                    )
                            }
                        }
                        Text(
                            day.day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            SleepDayDetails(chosen, today)
            average?.let {
                HorizontalDivider()
                Text("В среднем за день", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${it.days} из последних 7 завершённых дней с записями",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatNumber("Всего", statDuration(it.totalMs), Modifier.weight(1f))
                    StatNumber("Днём", statDuration(it.daytimeMs), Modifier.weight(1f), dayColor)
                    StatNumber(
                        "Ночью",
                        statDuration(it.nighttimeMs),
                        Modifier.weight(1f),
                        nightColor,
                    )
                }
                previous?.let { prior ->
                    val difference = it.totalMs - prior.totalMs
                    Text(
                        if (abs(difference) < 60_000) "Как на прошлой неделе"
                        else
                            "${if (difference > 0) "+" else "−"}${statDuration(abs(difference))} к прошлой неделе",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (definitions)
        AlertDialog(
            onDismissRequest = { definitions = false },
            title = { Text("Статистика сна") },
            text = {
                Text(
                    "Дневной сон начинается с 06:00 до 18:00, ночной — в остальное время. Сон через полночь сохраняет свой тип, а длительность распределяется между календарными днями. Текущий сон учитывается до настоящего момента. Бодрствование — время дня за вычетом сна: для сегодняшнего дня только до текущего момента. Оно показывается для дней с записями; промежутки без записанного сна считаются бодрствованием. Средние сравнивают последние 7 завершённых дней с предыдущими 7: дни без записей и незавершённый сегодняшний день исключены. «Макс. подряд» — непрерывный сон внутри выбранного дня."
                )
            },
            confirmButton = { TextButton(onClick = { definitions = false }) { Text("Понятно") } },
        )
}

@Composable
private fun StatLegend(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatNumber(
    label: String,
    value: String,
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepDayDetails(chosen: SleepDayStatistics, today: LocalDate) {
    val dayColor = MaterialTheme.colorScheme.primary
    val nightColor = MaterialTheme.colorScheme.tertiary
    Column(
        Modifier.fillMaxWidth().testTag("sleep-day-details"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (chosen.day == today) "Сегодня"
                else chosen.day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!chosen.hasRecords) Text("Нет записей", style = MaterialTheme.typography.bodySmall)
        }
        if (chosen.hasRecords) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatNumber("Всего", statDuration(chosen.totalMs), Modifier.weight(1f))
                StatNumber("Днём", statDuration(chosen.daytimeMs), Modifier.weight(1f), dayColor)
                StatNumber(
                    "Ночью",
                    statDuration(chosen.nighttimeMs),
                    Modifier.weight(1f),
                    nightColor,
                )
            }
            StatNumber("Бодрствование", statDuration(chosen.awakeMs), Modifier.fillMaxWidth())
            HorizontalDivider()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Дневных снов: ${chosen.naps}", style = MaterialTheme.typography.bodySmall)
                chosen.averageNapMs?.let {
                    Text(
                        "Средний дневной: ${statDuration(it)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Макс. подряд: ${statDuration(chosen.longestMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun SleepDayStatisticsDialog(
    statistics: SleepDayStatistics,
    today: LocalDate,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("sleep-day-statistics-dialog"),
        title = {
            Text(
                "Статистика сна · ${statistics.day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SleepDayDetails(statistics, today)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
