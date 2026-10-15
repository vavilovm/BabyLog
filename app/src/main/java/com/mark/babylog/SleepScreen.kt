package com.mark.babylog

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun clock(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).format(clockFormat)

private fun duration(ms: Long): String {
    val m = (ms.coerceAtLeast(0) / 60_000)
    return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    vm: MainViewModel,
    initialInvite: String? = null,
    onInviteConsumed: () -> Unit = {},
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val profileLoaded by vm.profileLoaded.collectAsStateWithLifecycle()
    val events by vm.sleepEvents.collectAsStateWithLifecycle()
    val membership by vm.membership.collectAsStateWithLifecycle()
    val reminders by vm.reminderState.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val prediction =
        remember(events, now, zone, profile) {
            SleepPredictor.calculate(events, now, zone, profile)
        }
    val sessions = remember(events, now) { SleepPredictor.validSessions(events, now) }
    val active = sessions.firstOrNull { it.endedAt == null }
    val lastWake = sessions.lastOrNull { it.endedAt != null }?.endedAt
    var days by remember { mutableIntStateOf(7) }
    var settings by remember { mutableStateOf(false) }
    var family by remember { mutableStateOf(initialInvite != null) }
    var reminderSettings by remember { mutableStateOf(false) }
    var reminderEditor by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<BabyReminder?>(null) }
    var editing by remember { mutableStateOf<BabyEvent?>(null) }
    var adding by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.errors.collect { snack.showSnackbar(it) } }
    LaunchedEffect(initialInvite) {
        if (initialInvite != null) {
            family = true
            onInviteConsumed()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Сон малыша", fontWeight = FontWeight.Bold)
                        profile?.let { p ->
                            val age = SleepPredictor.correctedAgeDays(p, today)
                            Text(
                                "${age / 7} нед. ${age % 7} дн.${if (p.dueDate != null) " · скорректированный возраст" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                actions = { TextButton(onClick = { settings = true }) { Text("Профиль") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SleepStatusCard(
                    active,
                    lastWake,
                    now,
                    zone,
                    vm::beginSleep,
                    vm::finishSleep,
                    { editing = it },
                )
            }
            item { NextSleepCard(prediction.next, active != null, now, zone) }

            if (prediction.conflicts.isNotEmpty())
                item {
                    Text(
                        "Есть пересекающиеся или незавершённые записи. Исправьте их в истории — они не участвуют в прогнозе.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            item {
                Text("Ритм сна", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 14, 30).forEach { count ->
                        FilterChip(
                            selected = days == count,
                            onClick = { days = count },
                            label = { Text("$count дней") },
                        )
                    }
                }
                Text(
                    "Сплошной цвет — записанный сон · светлый — прогноз. Нажмите на сон, чтобы изменить.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                SleepChart(
                    events,
                    prediction.forecast,
                    now,
                    zone,
                    days,
                    onSelect = { editing = it },
                )
            }
            item { SleepTotals(sessions, now, zone) }
            if (prediction.forecast.isNotEmpty())
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Ориентир на сегодня",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            prediction.forecast.forEach { f ->
                                Text(
                                    "${if (f.end - f.start >= 3 * 3_600_000) "Ночной сон" else "Сон"}: ${clock(f.start, zone)} – ${clock(f.end, zone)} · ±${f.uncertaintyMinutes} мин"
                                )
                            }
                            Text(
                                "Дальние оценки менее точны. План обновится после фактического пробуждения.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { adding = true }) { Text("+ Добавить сон") }
                    TextButton(onClick = { family = true }) { Text("Семья") }
                    TextButton(onClick = { reminderSettings = true }) { Text("Напоминания") }
                }
                syncStatus.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retrySync) { Text("Повторить синхронизацию") }
                }
                ReminderPanel(
                    reminders,
                    onSettings = { reminderSettings = true },
                    onComplete = { vm.completeReminder(it) },
                    onUndo = { vm.undoReminder(it) },
                )
            }
            item { Text("История сна", style = MaterialTheme.typography.titleLarge) }
            items(events.sortedByDescending { it.startedAt }, key = { it.remoteId }) { e ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { editing = e }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            Instant.ofEpochMilli(e.startedAt).atZone(zone).format(dateFormat),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "${clock(e.startedAt, zone)} — ${e.endedAt?.let { clock(it, zone) } ?: "спит"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (e.sleepRecordVersion == 0)
                                "Старая отметка · длительность неизвестна"
                            else duration((e.endedAt ?: now) - e.startedAt)
                        )
                        if (e.remoteId in prediction.conflicts)
                            Text(
                                "Проверьте время: конфликт записей",
                                color = MaterialTheme.colorScheme.error,
                            )
                    }
                }
            }
            item {
                val context = LocalContext.current
                TextButton(onClick = { vm.shareCsv(context) }) { Text("Экспорт журнала") }
                Text(
                    "О прогнозах: личная статистика сна и времени бодрствования. Точность проверяется только на более поздних записях, без подсказок из будущего.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (settings || (profileLoaded && profile == null))
        ProfileDialog(
            profile,
            onDismiss = { settings = false },
            onSave = { birth, due ->
                vm.saveProfile(birth, due)
                settings = false
            },
        )
    if (family)
        FamilyDialog(membership, vm, initialInvite.orEmpty(), onDismiss = { family = false })
    if (reminderSettings)
        ReminderSettingsDialog(
            reminders.reminders,
            { reminderSettings = false },
            {
                editingReminder = null
                reminderEditor = true
            },
            {
                editingReminder = it
                reminderEditor = true
            },
            vm::setReminderEnabled,
            vm::deleteReminder,
        )
    if (reminderEditor)
        ReminderEditDialog(editingReminder, { reminderEditor = false }) {
            vm.saveReminder(it)
            reminderEditor = false
        }
    if (adding || editing != null)
        SleepEntryDialog(
            editing,
            zone,
            {
                adding = false
                editing = null
            },
            onSave = { start, end ->
                val current = editing
                if (current == null) vm.recordSleep(start, end!!)
                else
                    vm.updateEvent(
                        current.copy(startedAt = start, endedAt = end, sleepRecordVersion = 1)
                    )
                adding = false
                editing = null
            },
            onDelete = {
                editing?.let(vm::delete)
                editing = null
            },
        )
}

@Composable
internal fun ProfileDialog(
    profile: BabyProfile?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    var birthday by remember(profile) { mutableStateOf(profile?.birthday?.let(LocalDate::parse)) }
    var due by remember(profile) { mutableStateOf(profile?.dueDate?.let(LocalDate::parse)) }
    fun pick(value: LocalDate?, maximum: LocalDate?, set: (LocalDate) -> Unit) {
        val initial = value ?: LocalDate.now()
        DatePickerDialog(
                context,
                { _, year, month, day -> set(LocalDate.of(year, month + 1, day)) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            )
            .apply {
                maximum?.let {
                    datePicker.maxDate =
                        it.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
            .show()
    }
    AlertDialog(
        onDismissRequest = { if (profile != null) onDismiss() },
        title = { Text("Когда родилась малышка?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Возраст помогает учитывать, как меняется сон. Дата будет общей для вашей семьи."
                )
                OutlinedButton(onClick = { pick(birthday, LocalDate.now()) { birthday = it } }) {
                    Text(birthday?.format(dateFormat) ?: "Выбрать дату рождения")
                }
                Text(
                    "Если родилась раньше срока, можно указать предполагаемую дату родов.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { pick(due, null) { due = it } }) {
                    Text(due?.format(dateFormat) ?: "Предполагаемая дата родов (необязательно)")
                }
                if (due != null) TextButton(onClick = { due = null }) { Text("Убрать дату родов") }
                if (birthday != null && due != null && due!! < birthday!!)
                    Text(
                        "Дата родов должна быть не раньше рождения",
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            Button(
                enabled = birthday != null && (due == null || due!! >= birthday!!),
                onClick = { onSave(birthday.toString(), due?.toString()) },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { if (profile != null) TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SleepEntryDialog(
    event: BabyEvent?,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Long, Long?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    var start by remember { mutableLongStateOf(event?.startedAt ?: now - 3_600_000) }
    var end by remember {
        mutableStateOf(if (event == null || event.sleepRecordVersion == 0) now else event.endedAt)
    }
    fun pick(time: Long, set: (Long) -> Unit) {
        val z = Instant.ofEpochMilli(time).atZone(zone)
        DatePickerDialog(
                context,
                { _, y, m, d ->
                    TimePickerDialog(
                            context,
                            { _, h, min ->
                                set(
                                    LocalDate.of(y, m + 1, d)
                                        .atTime(h, min)
                                        .atZone(zone)
                                        .toInstant()
                                        .toEpochMilli()
                                )
                            },
                            z.hour,
                            z.minute,
                            true,
                        )
                        .show()
                },
                z.year,
                z.monthValue - 1,
                z.dayOfMonth,
            )
            .apply { datePicker.maxDate = now }
            .show()
    }
    val valid = start <= now && (end == null || (end!! > start && end!! <= now))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Добавить сон" else "Изменить сон") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Укажите фактическое время засыпания и пробуждения.")
                OutlinedButton(onClick = { pick(start) { start = it } }) {
                    Text(
                        "Начало: ${Instant.ofEpochMilli(start).atZone(zone).format(dateFormat)} ${clock(start, zone)}"
                    )
                }
                OutlinedButton(onClick = { pick(end ?: now) { end = it } }) {
                    Text(
                        end?.let {
                            "Конец: ${Instant.ofEpochMilli(it).atZone(zone).format(dateFormat)} ${clock(it, zone)}"
                        } ?: "Ещё спит · указать пробуждение"
                    )
                }
                if (!valid)
                    Text(
                        "Пробуждение должно быть после начала сна, не в будущем.",
                        color = MaterialTheme.colorScheme.error,
                    )
                if (event != null)
                    TextButton(onClick = onDelete) {
                        Text("Удалить запись", color = MaterialTheme.colorScheme.error)
                    }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(start, end) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun SleepChart(
    events: List<BabyEvent>,
    forecast: List<ForecastSleep>,
    now: Long,
    zone: ZoneId,
    days: Int,
    onSelect: (BabyEvent) -> Unit,
) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val colour = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 54.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
        repeat(days) { index ->
            val day = today.minusDays((days - index - 1).toLong())
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // Wall-clock coordinates align the same hour across days, even on DST transitions.
            fun fraction(t: Long): Float {
                if (t <= start) return 0f
                if (t >= end) return 1f
                val local = Instant.ofEpochMilli(t).atZone(zone).toLocalTime()
                return local.toSecondOfDay() / 86400f
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.format(DateTimeFormatter.ofPattern("dd.MM")),
                    Modifier.width(54.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
                BoxWithConstraints(
                    Modifier.weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val width = maxWidth
                    listOf(0.25f, 0.5f, 0.75f).forEach { tick ->
                        Box(
                            Modifier.offset(x = width * tick)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        )
                    }
                    events
                        .filter {
                            it.sleepRecordVersion == 1 &&
                                it.startedAt < end &&
                                (it.endedAt ?: now) > start
                        }
                        .forEach { e ->
                            val left = fraction(e.startedAt)
                            val right = fraction(e.endedAt ?: now)
                            // A repeated DST hour can map backwards; retain a visible inspectable
                            // block.
                            Box(
                                Modifier.offset(x = width * left)
                                    .width(
                                        (width * (right - left).coerceAtLeast(0.005f))
                                            .coerceAtLeast(4.dp)
                                    )
                                    .fillMaxHeight()
                                    .padding(vertical = 5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (e.syncState == SyncState.CONFLICT)
                                            MaterialTheme.colorScheme.error
                                        else colour
                                    )
                                    .semantics {
                                        contentDescription =
                                            "${day.format(dateFormat)}: сон ${clock(e.startedAt, zone)} — ${e.endedAt?.let { clock(it, zone) } ?: "сейчас"}"
                                    }
                                    .clickable { onSelect(e) }
                            )
                        }
                    forecast
                        .filter { it.start < end && it.end > start }
                        .forEach { f ->
                            val left = fraction(f.start)
                            val right = fraction(f.end)
                            Box(
                                Modifier.offset(x = width * left)
                                    .width(width * (right - left).coerceAtLeast(0.005f))
                                    .fillMaxHeight()
                                    .padding(vertical = 10.dp)
                                    .background(colour.copy(alpha = 0.25f))
                                    .semantics {
                                        contentDescription =
                                            "Прогноз сна ${clock(f.start, zone)} — ${clock(f.end, zone)}"
                                    }
                            )
                        }
                    if (day == today)
                        Box(
                            Modifier.offset(x = width * fraction(now))
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.tertiary)
                        )
                }
            }
        }
    }
}

@Composable
internal fun SleepTotals(sessions: List<BabyEvent>, now: Long, zone: ZoneId) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun total(day: LocalDate): Long {
        val a = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val b = minOf(now, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        return sessions.sumOf {
            (minOf(it.endedAt ?: now, b) - maxOf(it.startedAt, a)).coerceAtLeast(0)
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Сегодня записано: ${duration(total(today))}",
                style = MaterialTheme.typography.titleMedium,
            )
            val naps =
                sessions.count {
                    Instant.ofEpochMilli(it.startedAt).atZone(zone).let { z ->
                        z.toLocalDate() == today && z.hour in 6..17
                    }
                }
            Text(
                "Дневных снов: $naps · самый долгий за сутки: ${duration(sessions.filter { (it.endedAt ?: now) > now - 86_400_000 }.maxOfOrNull { minOf(it.endedAt ?: now, now) - maxOf(it.startedAt, now - 86_400_000) } ?: 0)}"
            )
            Text("Длительность по дням", style = MaterialTheme.typography.titleSmall)
            repeat(7) { i ->
                val day = today.minusDays((6 - i).toLong())
                val ms = total(day)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        day.format(DateTimeFormatter.ofPattern("dd.MM")),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    LinearProgressIndicator(
                        progress = { (ms.toFloat() / 86_400_000).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(duration(ms), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "Показано только записанное время. Сегодня день ещё не завершён; пропущенные записи уменьшают итог.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun SleepStatusCard(
    active: BabyEvent?,
    lastWake: Long?,
    now: Long,
    zone: ZoneId,
    onStart: () -> Unit,
    onWake: () -> Unit,
    onEdit: (BabyEvent) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (active != null) "Сейчас спит" else "Сейчас бодрствует",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (active != null) duration(now - active.startedAt)
                else lastWake?.let { duration(now - it) } ?: "Начнём наблюдать её ритм",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = { if (active == null) onStart() else onWake() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            ) {
                Text(
                    if (active == null) "Начать сон" else "Проснулась",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            active?.let {
                TextButton(onClick = { onEdit(it) }) {
                    Text("Сон с ${clock(it.startedAt, zone)} · изменить")
                }
            }
        }
    }
}

@Composable
internal fun NextSleepCard(estimate: SleepEstimate?, sleeping: Boolean, now: Long, zone: ZoneId) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Следующий сон", style = MaterialTheme.typography.titleMedium)
            val next = estimate
            if (sleeping) Text("Вернёмся к прогнозу после пробуждения")
            else if (next == null) {
                Text("Изучаем её ритм", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Записывайте начало и конец сна. Для первого прогноза нужен завершённый период бодрствования между двумя снами. После долгого перерыва проверьте пропущенные записи."
                )
            } else {
                Text(
                    "${clock(next.earliest, zone)} – ${clock(next.latest, zone)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Ориентир: ${clock((next.expected / 300_000) * 300_000, zone)} · ${if (next.preliminary) "предварительная оценка" else "вероятный интервал ≈80%"}"
                )
                if (now > next.latest)
                    Text(
                        "Ожидаемое окно уже прошло. Возможно, сон ещё не записан?",
                        color = MaterialTheme.colorScheme.error,
                    )
                Text(
                    "${next.model} · наблюдений: ${next.samples}",
                    style = MaterialTheme.typography.bodySmall,
                )
                next.medianErrorMinutes?.let {
                    Text(
                        "Медианная ошибка прошлых прогнозов: ${it.toInt()} мин",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                next.coverage?.let {
                    Text(
                        "В прошлые интервалы попало ${(it * 100).toInt()}% наблюдений",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "Это ориентир, а не расписание. Признаки сонливости важнее часов; в первые месяцы ритм особенно изменчив.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
