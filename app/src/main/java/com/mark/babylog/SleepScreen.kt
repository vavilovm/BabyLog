package com.mark.babylog

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val clockTick by rememberSleepClock()
    // A Room emission can arrive between ticks. Evaluate it with a fresh clock,
    // otherwise a just-started session looks future-dated until the next prediction refresh.
    val now = remember(events, clockTick) { System.currentTimeMillis() }
    val zone = rememberSleepZone()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val predictionTime = now / 15_000 * 15_000
    val prediction =
        remember(events, predictionTime, zone, profile) {
            SleepPredictor.calculate(events, now, zone, profile)
        }
    // Validity only changes at event boundaries, not on each clock tick.
    val validity = sessionValidityKey(events, now)
    val sessions = remember(events, validity) { SleepPredictor.validSessions(events, now) }
    val displayNow = now / 60_000 * 60_000
    val active = sessions.firstOrNull { it.endedAt == null }
    val lastWake = sessions.lastOrNull { it.endedAt != null }?.endedAt
    var days by remember { mutableIntStateOf(7) }
    var settings by remember { mutableStateOf(false) }
    var family by remember { mutableStateOf(initialInvite != null) }
    var editing by remember { mutableStateOf<BabyEvent?>(null) }
    var adding by remember { mutableStateOf(false) }
    val history = remember(events) { events.sortedByDescending { it.startedAt } }
    var chartBounds by remember { mutableStateOf<Rect?>(null) }
    var dismissChart by remember { mutableIntStateOf(0) }
    var recentlyFinished by remember { mutableStateOf<BabyEvent?>(null) }
    var finishingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(events, finishingId) {
        events
            .firstOrNull { it.remoteId == finishingId && it.endedAt != null }
            ?.let {
                recentlyFinished = it
                finishingId = null
            }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.errors.collect { snack.showSnackbar(it) } }
    LaunchedEffect(initialInvite) {
        if (initialInvite != null) {
            family = true
            onInviteConsumed()
        }
    }
    Scaffold(
        modifier = Modifier.dismissSleepChartOnOutsidePress(chartBounds) { dismissChart++ },
        snackbarHost = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentlyFinished?.let { finished ->
                    FinishedSleepPrompt(
                        finished,
                        zone = zone,
                        onEdit = {
                            editing = finished
                            recentlyFinished = null
                        },
                        onDismiss = { recentlyFinished = null },
                    )
                }
                SnackbarHost(snack)
            }
        },
        topBar = {
            SleepAppBar(
                profile,
                today,
                membership != null,
                pendingCount,
                syncStatus,
                zone,
                vm::retrySync,
                onProfile = { settings = true },
                updateAction = { UpdateButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SleepStatusCard(
                    active,
                    lastWake,
                    now,
                    zone,
                    vm::beginSleep,
                    {
                        finishingId = active?.remoteId
                        vm.finishSleep()
                    },
                    { editing = it },
                    liveDuration = true,
                )
            }
            item {
                NextSleepCard(prediction.next, active != null, displayNow, zone, prediction.current)
            }

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
            }
            item {
                SleepChart(
                    events,
                    prediction.forecast,
                    displayNow,
                    zone,
                    days,
                    onSelect = {},
                    onEdit = { editing = it },
                    current = prediction.current,
                    modifier = Modifier.onGloballyPositioned { chartBounds = it.boundsInRoot() },
                    dismissSignal = dismissChart,
                )
            }
            item { SleepChartPeriod(days, onChange = { days = it }) }
            item { SleepTotals(sessions, displayNow, zone) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { adding = true }) { Text("+ Добавить сон") }
                    TextButton(onClick = { family = true }) { Text("Семья") }
                }
            }
            item { Text("История сна", style = MaterialTheme.typography.titleLarge) }
            items(history, key = { it.remoteId }) { e ->
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
    endFirst: Boolean = false,
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
                if (!endFirst) SleepEntryTimeField("Начало", start, zone) { start = it }
                if (end != null) {
                    SleepEntryTimeField("Конец", end!!, zone) { end = it }
                } else {
                    OutlinedButton(onClick = { end = System.currentTimeMillis() }) {
                        Text("Ещё спит · указать пробуждение")
                    }
                }
                if (endFirst) SleepEntryTimeField("Начало", start, zone) { start = it }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepAppBar(
    profile: BabyProfile?,
    today: LocalDate,
    family: Boolean,
    pendingCount: Int,
    syncStatus: com.mark.babylog.sync.FamilySyncStatus,
    zone: ZoneId,
    onRetry: () -> Unit,
    onProfile: () -> Unit,
    updateAction: @Composable () -> Unit = {},
) {
    var settingsOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                "Сон малыша",
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        actions = {
            com.mark.babylog.audio.SoundscapeAction()
            IconButton(onClick = { settingsOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
            }
        },
    )
    if (settingsOpen)
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Настройки") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = {
                            settingsOpen = false
                            onProfile()
                        }
                    ) {
                        Text("Профиль малыша")
                    }
                    profile?.let { p ->
                        val age = SleepPredictor.correctedAgeDays(p, today)
                        Text(
                            "${age / 7} нед. ${age % 7} дн.${if (p.dueDate != null) " · скорректированный возраст" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                    SyncStatusDetails(family, pendingCount, syncStatus, zone, onRetry)
                    HorizontalDivider()
                    Text("Приложение", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "BabyLog ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    updateAction()
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Готово") } },
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

/** Crossing a start/end can change validity even without a new Room emission. */
internal fun sessionValidityKey(events: List<BabyEvent>, now: Long): Int =
    events.sumOf {
        (if (it.startedAt <= now) 1 else 0) +
            (if (it.endedAt != null && it.endedAt <= now) 1 else 0)
    }

@Composable
internal fun rememberSleepClock(): State<Long> =
    produceState(System.currentTimeMillis()) {
        while (true) {
            val now = System.currentTimeMillis()
            value = now
            delay(15_000 - now % 15_000)
        }
    }

@Composable
private fun rememberSleepZone(): ZoneId {
    val context = LocalContext.current
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    zone = ZoneId.systemDefault()
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return zone
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
