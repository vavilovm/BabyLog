package com.mark.babylog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import java.time.*
import kotlinx.coroutines.delay

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
    val pullRefresh = rememberPullToRefreshState()
    LaunchedEffect(pullRefresh.isRefreshing) {
        if (pullRefresh.isRefreshing) {
            try {
                vm.retrySync().join()
            } finally {
                pullRefresh.endRefresh()
            }
        }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
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
        Box(
            Modifier.fillMaxSize().padding(padding).nestedScroll(pullRefresh.nestedScrollConnection)
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (prediction.conflicts.isNotEmpty()) {
                    item {
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Нужно проверить записи сна",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Время записей противоречит друг другу. Пока конфликт не исправлен, эти записи не учитываются в прогнозе."
                                )
                                history
                                    .filter { it.remoteId in prediction.conflicts }
                                    .forEach { event ->
                                        TextButton(onClick = { editing = event }) {
                                            Text(
                                                "Проверить: ${Instant.ofEpochMilli(event.startedAt).atZone(zone).format(dateFormat)} · ${clock(event.startedAt, zone)} — ${event.endedAt?.let { clock(it, zone) } ?: "спит"}"
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
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
                    NextSleepCard(
                        prediction.next,
                        active != null,
                        displayNow,
                        zone,
                        prediction.current,
                    )
                }

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
                        modifier =
                            Modifier.onGloballyPositioned { chartBounds = it.boundsInRoot() },
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
                item {
                    val context = LocalContext.current
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "История сна",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        TextButton(onClick = { vm.shareCsv(context) }) { Text("Экспорт") }
                    }
                }
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
            }
            PullToRefreshContainer(
                state = pullRefresh,
                modifier = Modifier.align(Alignment.TopCenter),
            )
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
                    val diagnosticContext = LocalContext.current
                    TextButton(
                        onClick = { com.mark.babylog.sync.SyncDiagnostics.share(diagnosticContext) }
                    ) {
                        Text("Поделиться диагностикой")
                    }
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
