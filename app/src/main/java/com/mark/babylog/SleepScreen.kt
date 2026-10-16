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
import androidx.compose.foundation.verticalScroll
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

private fun clock(t: Long, zone: ZoneId): String {
    val local = Instant.ofEpochMilli(t).atZone(zone)
    val repeated = zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
    return local.format(clockFormat) + if (repeated) " UTC${local.offset}" else ""
}

private fun graphClock(time: Long, zone: ZoneId, showOffset: Boolean): String =
    Instant.ofEpochMilli(time).atZone(zone).let { local ->
        if (showOffset) local.format(clockFormat) + " UTC${local.offset}" else clock(time, zone)
    }

private fun wakeClock(time: Long, now: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return clock(time, zone) +
        when (date) {
            today -> ""
            today.plusDays(1) -> " завтра"
            else -> " ${date.format(DateTimeFormatter.ofPattern("dd.MM"))}"
        }
}

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
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A Room emission can arrive between ticks. Evaluate it with a fresh clock,
    // otherwise a just-started session looks future-dated until the next prediction refresh.
    val observedAt = remember(events) { System.currentTimeMillis() }
    val now = maxOf(clockTick, observedAt)
    LaunchedEffect(Unit) {
        while (true) {
            clockTick = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val predictionTime = now / 15_000 * 15_000
    val prediction =
        remember(events, predictionTime, zone, profile) {
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
            item { NextSleepCard(prediction.next, active != null, now, zone, prediction.current) }

            if (prediction.conflicts.isNotEmpty())
                item { Text("Проверьте время в истории", color = MaterialTheme.colorScheme.error) }
            item {
                Text("Ритм сна", style = MaterialTheme.typography.titleLarge)
                Text("Местное время · ${zone.id}", style = MaterialTheme.typography.labelSmall)
                profile
                    ?.rhythmZoneId
                    ?.takeIf { it != zone.id }
                    ?.let {
                        Text(
                            "Ритм по домашнему поясу · $it",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 14, 30).forEach { count ->
                        FilterChip(
                            selected = days == count,
                            onClick = { days = count },
                            label = { Text("$count дней") },
                        )
                    }
                }
            }
            item {
                SleepChart(
                    events,
                    prediction.forecast,
                    now,
                    zone,
                    days,
                    onSelect = { editing = it },
                    current = prediction.current,
                )
            }
            item { SleepTotals(sessions, now, zone) }
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
            }
        }
    }
    if (settings || (profileLoaded && profile == null))
        ProfileDialog(
            profile,
            onDismiss = { settings = false },
            onSave = { birth, due, rhythmZone ->
                vm.saveProfile(birth, due, rhythmZone)
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
    onSave: (String, String?, String) -> Unit,
) {
    val context = LocalContext.current
    var birthday by remember(profile) { mutableStateOf(profile?.birthday?.let(LocalDate::parse)) }
    var due by remember(profile) { mutableStateOf(profile?.dueDate?.let(LocalDate::parse)) }
    var rhythmZone by
        remember(profile) { mutableStateOf(profile?.rhythmZoneId ?: ZoneId.systemDefault().id) }
    val validZone = remember(rhythmZone) { runCatching { ZoneId.of(rhythmZone) }.isSuccess }
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
                OutlinedTextField(
                    value = rhythmZone,
                    onValueChange = { rhythmZone = it.trim() },
                    label = { Text("Домашний часовой пояс") },
                    placeholder = { Text("Europe/Amsterdam") },
                    isError = !validZone,
                    singleLine = true,
                )
                Text(
                    "В поездке оставьте домашний пояс: ритм сна меняется постепенно. Время на графике — местное.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                enabled = birthday != null && (due == null || due!! >= birthday!!) && validZone,
                onClick = { onSave(birthday.toString(), due?.toString(), rhythmZone) },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { if (profile != null) TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun SleepEntryDialog(
    event: BabyEvent?,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Long, Long?) -> Unit,
    onDelete: () -> Unit,
) {
    val now = System.currentTimeMillis()
    var start by remember { mutableLongStateOf(event?.startedAt ?: now - 3_600_000) }
    var end by remember {
        mutableStateOf(if (event == null || event.sleepRecordVersion == 0) now else event.endedAt)
    }
    val valid = start <= now && (end == null || (end!! > start && end!! <= now))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Добавить сон" else "Изменить сон") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Укажите фактическое время засыпания и пробуждения.")
                SleepEntryTimeField("Начало", start, zone) { start = it }
                if (end != null) {
                    SleepEntryTimeField("Конец", end!!, zone) { end = it }
                } else {
                    OutlinedButton(onClick = { end = System.currentTimeMillis() }) {
                        Text("Ещё спит · указать пробуждение")
                    }
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
        sleepChartDays(forecast, now, zone, days, current).forEach { day ->
            val axis = SleepDayAxis(day, zone)
            val start = axis.start
            val end = axis.end
            fun fraction(t: Long): Float = axis.fraction(t)
            if (axis.changesOffset) {
                val from = Instant.ofEpochMilli(start).atZone(zone).offset
                val to = Instant.ofEpochMilli(end - 1).atZone(zone).offset
                Text(
                    "${day.format(DateTimeFormatter.ofPattern("dd.MM"))} · ${duration(axis.duration)} · UTC$from → UTC$to",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 54.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { tick ->
                        Text(
                            if (tick == 1f) "24:00" else clock(axis.timeAt(tick), zone),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
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
                                            "${day.format(dateFormat)}: сон ${graphClock(e.startedAt, zone, axis.changesOffset)} — ${e.endedAt?.let { graphClock(it, zone, axis.changesOffset) } ?: "сейчас"}"
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
                                            "Прогноз сна ${graphClock(f.start, zone, axis.changesOffset)} — ${graphClock(f.end, zone, axis.changesOffset)}"
                                    }
                            )
                        }
                    current
                        ?.expectedWake
                        ?.takeIf { it >= start && it < end }
                        ?.let { time ->
                            Box(
                                Modifier.offset(x = (width - 3.dp) * fraction(time))
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(colour)
                                    .semantics {
                                        contentDescription = "Пробуждение ${clock(time, zone)}"
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
            current
                ?.expectedWake
                ?.takeIf { it >= start && it < end }
                ?.let { time ->
                    Text(
                        "● ${clock(time / 300_000 * 300_000, zone)}",
                        modifier = Modifier.fillMaxWidth().padding(start = 54.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colour,
                    )
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Сон" to 1f, "Прогноз" to 0.25f).forEach { (label, alpha) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colour.copy(alpha = alpha))
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (current != null)
                Text("● Пробуждение", style = MaterialTheme.typography.labelSmall, color = colour)
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
            SleepNumber("Сон сегодня", duration(total(today)))
            val naps =
                sessions.count {
                    Instant.ofEpochMilli(it.startedAt).atZone(zone).let { z ->
                        z.toLocalDate() == today && z.hour in 6..17
                    }
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SleepNumber("Дневных снов", "$naps", Modifier.weight(1f))
                SleepNumber(
                    "Самый долгий",
                    duration(
                        sessions
                            .filter { (it.endedAt ?: now) > now - 86_400_000 }
                            .maxOfOrNull {
                                minOf(it.endedAt ?: now, now) -
                                    maxOf(it.startedAt, now - 86_400_000)
                            } ?: 0
                    ),
                    Modifier.weight(1f),
                )
            }
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
                        progress = {
                            (ms.toFloat() / SleepDayAxis(day, zone).duration).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(duration(ms), style = MaterialTheme.typography.labelSmall)
                }
            }
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
                if (active != null) sleepStatusDuration(now - active.startedAt)
                else lastWake?.let { sleepStatusDuration(now - it) } ?: "Начнём наблюдать её ритм",
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
internal fun NextSleepCard(
    estimate: SleepEstimate?,
    sleeping: Boolean,
    now: Long,
    zone: ZoneId,
    current: CurrentSleepEstimate? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (sleeping) "Пробуждение" else "Следующий сон",
                style = MaterialTheme.typography.titleMedium,
            )
            val expected = if (sleeping) current?.expectedWake else estimate?.expected
            val earliest = if (sleeping) current?.earliestWake else estimate?.earliest
            val latest = if (sleeping) current?.latestWake else estimate?.latest
            if (expected == null || earliest == null || latest == null) {
                Text("— : —", style = MaterialTheme.typography.displaySmall)
                Text("Мало записей", style = MaterialTheme.typography.labelMedium)
            } else {
                Text(
                    wakeClock(expected / 300_000 * 300_000, now, zone),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                PredictionRange(earliest, expected, latest, now, zone)
                if (sleeping && current != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        SleepNumber(
                            "Всего",
                            duration(expected - current.startedAt),
                            Modifier.weight(1f),
                        )
                        SleepNumber(
                            if (now <= expected) "Осталось" else "Сверх прогноза",
                            duration(kotlin.math.abs(expected - now)),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepNumber(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PredictionRange(earliest: Long, expected: Long, latest: Long, now: Long, zone: ZoneId) {
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
            Text(wakeClock(earliest, now, zone), style = MaterialTheme.typography.labelLarge)
            Text(wakeClock(latest, now, zone), style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal fun sleepStatusDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return if (seconds < 3_600) "${seconds / 60} мин ${seconds % 60} с" else duration(ms)
}

@Composable
private fun SleepEntryTimeField(label: String, time: Long, zone: ZoneId, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val selected = Instant.ofEpochMilli(time).atZone(zone)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                onChange(
                                    resolveSleepLocalTime(
                                        selected.toLocalDate().atTime(hour, minute),
                                        zone,
                                        selected.offset,
                                    )
                                )
                            },
                            selected.hour,
                            selected.minute,
                            true,
                        )
                        .show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(clock(time, zone), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                onChange(
                                    resolveSleepLocalTime(
                                        LocalDate.of(year, month + 1, day)
                                            .atTime(selected.toLocalTime()),
                                        zone,
                                        selected.offset,
                                    )
                                )
                            },
                            selected.year,
                            selected.monthValue - 1,
                            selected.dayOfMonth,
                        )
                        .apply { datePicker.maxDate = System.currentTimeMillis() }
                        .show()
                }
            ) {
                Text(selected.format(dateFormat))
            }
        }
        val offsets = zone.rules.getValidOffsets(selected.toLocalDateTime())
        if (offsets.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offsets.forEach { offset ->
                    FilterChip(
                        selected = offset == selected.offset,
                        onClick = {
                            onChange(
                                resolveSleepLocalTime(selected.toLocalDateTime(), zone, offset)
                            )
                        },
                        label = { Text("UTC$offset") },
                    )
                }
            }
        }
        MinuteShortcuts { onChange(moveMinutes(time, it).coerceAtLeast(0)) }
    }
}
