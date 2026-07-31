package com.mark.babylog

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(val events: List<BabyEvent> = emptyList(), val totalEvents: Int = events.size) {}

data class ReminderUiState(
    val reminders: List<BabyReminder> = emptyList(),
    val completions: List<ReminderCompletion> = emptyList(),
)

data class DailyStatisticsState(
    val day: LocalDate = LocalDate.now(),
    val events: List<BabyEvent> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val babyApp = app as BabyLogApp
    private val dao = babyApp.database.events()
    private val repository = babyApp.repository
    private val historyLimit = MutableStateFlow(100)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors = _errors.asSharedFlow()
    val state =
        combine(historyLimit.flatMapLatest(dao::observeRecent), dao.observeVisibleCount()) {
                e,
                total ->
                UiState(e, totalEvents = total)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())
    private val statisticsDay = MutableStateFlow(LocalDate.now())
    val statisticsState =
        statisticsDay
            .flatMapLatest { day ->
                val zone = ZoneId.systemDefault()
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                dao.observeDay(start, end)
                    .map { DailyStatisticsState(day, it, false) }
                    .onStart { emit(DailyStatisticsState(day, loading = true)) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyStatisticsState())
    private val today =
        flow {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val now = ZonedDateTime.now()
                    emit(now.toLocalDate().toEpochDay())
                    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                    delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000))
                }
            }
            .distinctUntilChanged()
    val reminderState =
        today
            .flatMapLatest { day ->
                combine(
                    babyApp.reminderRepository.reminders,
                    babyApp.reminderRepository.completions(day),
                    ::ReminderUiState,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReminderUiState())

    init {
        viewModelScope.launch { sync() }
    }

    val membership =
        repository.membership.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pendingCount =
        repository.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val syncStatus = babyApp.familySync.status.asStateFlow()
    val familyMembers = babyApp.familySync.members.asStateFlow()

    fun logFeeding(kind: FeedingKind) = launchAction {
        repository.logFeeding(kind)
        sync()
    }

    fun startSleep(position: SleepPosition) = launchAction {
        repository.startSleep(position)
        sync()
    }

    fun logPumping(side: FeedingKind, volumeMl: Int) = launchAction {
        repository.logPumping(side, volumeMl)
        sync()
    }

    fun logBottle(volumeMl: Int) = launchAction {
        repository.logBottle(volumeMl)
        sync()
    }

    fun updateEvent(event: BabyEvent) = launchAction {
        repository.updateEvent(event)
        sync()
    }

    fun delete(event: BabyEvent) = launchAction {
        repository.deleteEvent(event)
        sync()
    }

    fun loadMoreHistory() {
        historyLimit.update { current ->
            minOf(current + 100, maxOf(current, state.value.totalEvents))
        }
    }

    fun selectStatisticsDay(day: LocalDate) {
        statisticsDay.value = day.coerceAtMost(LocalDate.now())
    }

    fun saveReminder(reminder: BabyReminder) = launchAction {
        babyApp.reminderRepository.save(reminder)
    }

    fun deleteReminder(reminder: BabyReminder) = launchAction {
        babyApp.reminderRepository.delete(reminder)
    }

    fun setReminderEnabled(reminder: BabyReminder, enabled: Boolean) = launchAction {
        babyApp.reminderRepository.setEnabled(reminder, enabled)
    }

    fun completeReminder(reminder: BabyReminder, day: Long = LocalDate.now().toEpochDay()) =
        launchAction {
            babyApp.reminderRepository.complete(reminder.id, day)
        }

    fun undoReminder(reminder: BabyReminder, day: Long = LocalDate.now().toEpochDay()) =
        launchAction {
            babyApp.reminderRepository.undo(reminder.id, day)
        }

    fun createFamily(name: String, onDone: (Result<String>) -> Unit) =
        viewModelScope.launch { onDone(runCatching { babyApp.familySync.createFamily(name) }) }

    fun joinFamily(code: String, name: String, onDone: (Result<Unit>) -> Unit) =
        viewModelScope.launch { onDone(runCatching { babyApp.familySync.joinFamily(code, name) }) }

    fun createInvite(onDone: (Result<String>) -> Unit) =
        viewModelScope.launch { onDone(runCatching { babyApp.familySync.createInvite() }) }

    fun retrySync() = launchAction {
        if (!babyApp.familySync.sync()) error("Синхронизация не удалась")
    }

    private fun launchAction(block: suspend () -> Unit) =
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    _errors.emit(error.message ?: "Не удалось выполнить действие")
                }
        }

    private suspend fun sync() {
        AppSurfaceSync.refresh(getApplication())
        babyApp.familySync.schedule()
    }

    private suspend fun csv(): File {
        val f = File(getApplication<Application>().cacheDir, "babylog.csv")
        val events = dao.allForExport()
        f.writeText(
            buildString {
                appendLine("type,detail,start,end,duration_minutes")
                events.forEach { e ->
                    appendLine(
                        "${e.type},${e.detail},${iso(e.startedAt)},${e.endedAt?.let(::iso).orEmpty()},${((e.endedAt?:System.currentTimeMillis())-e.startedAt)/60000}"
                    )
                }
            }
        )
        return f
    }

    fun shareCsv(context: Context) = launchAction {
        val file = withContext(Dispatchers.IO) { csv() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Экспорт истории",
            )
        )
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
}
