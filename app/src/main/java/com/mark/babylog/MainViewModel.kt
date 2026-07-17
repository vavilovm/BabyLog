package com.mark.babylog

import android.app.Application
import android.content.Intent
import android.content.Context
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

data class UiState(val events: List<BabyEvent> = emptyList(), val segments: List<SleepSegment> = emptyList(),val totalEvents:Int=events.size) {
    val active get() = events.firstOrNull { it.type == EventType.FEEDING && it.endedAt == null }
}
data class ReminderUiState(val reminders:List<BabyReminder> = emptyList(),val completions:List<ReminderCompletion> = emptyList())
data class DailyStatisticsState(val day:LocalDate=LocalDate.now(),val events:List<BabyEvent> = emptyList(),val loading:Boolean=true)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val babyApp=app as BabyLogApp
    private val dao = babyApp.database.events()
    private val repository=babyApp.repository
    private val historyLimit=MutableStateFlow(100)
    val state = combine(historyLimit.flatMapLatest(dao::observeRecent),dao.observeVisibleCount()) { e, total -> UiState(e,totalEvents=total) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())
    private val statisticsDay=MutableStateFlow(LocalDate.now())
    val statisticsState=statisticsDay.flatMapLatest { day ->
        val zone=ZoneId.systemDefault()
        val start=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.observeDay(start,end).map{DailyStatisticsState(day,it,false)}.onStart{emit(DailyStatisticsState(day,loading=true))}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),DailyStatisticsState())
    val reminderState=combine(babyApp.reminderRepository.reminders,babyApp.reminderRepository.completions,::ReminderUiState).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),ReminderUiState())
    init { viewModelScope.launch { sync() } }

    val membership=repository.membership.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val pendingCount=repository.pendingCount.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val syncStatus=babyApp.familySync.status.asStateFlow()
    val familyMembers=babyApp.familySync.members.asStateFlow()
    fun startFeeding(kind: FeedingKind) = viewModelScope.launch { repository.startFeeding(kind);sync() }
    fun startSleep(position: SleepPosition) = viewModelScope.launch { repository.startSleep(position);sync() }
    fun logPumping(side: FeedingKind, volumeMl: Int) = viewModelScope.launch { repository.logPumping(side,volumeMl);sync() }
    fun logBottle(volumeMl: Int) = viewModelScope.launch { repository.logBottle(volumeMl);sync() }
    fun changePosition(position: SleepPosition) = viewModelScope.launch { repository.changePosition(position);sync() }
    fun stop() = viewModelScope.launch { repository.stop();sync() }
    fun stopBottle(volumeMl: Int) = viewModelScope.launch { repository.stopBottle(volumeMl);sync() }
    fun updateEvent(event: BabyEvent) = viewModelScope.launch { repository.updateEvent(event);sync() }
    fun delete(event: BabyEvent) = viewModelScope.launch { repository.deleteEvent(event);sync() }
    fun loadMoreHistory(){historyLimit.update{current->minOf(current+100,maxOf(current,state.value.totalEvents))}}
    fun selectStatisticsDay(day:LocalDate){statisticsDay.value=day.coerceAtMost(LocalDate.now())}
    fun saveReminder(reminder:BabyReminder)=viewModelScope.launch{babyApp.reminderRepository.save(reminder)}
    fun deleteReminder(reminder:BabyReminder)=viewModelScope.launch{babyApp.reminderRepository.delete(reminder)}
    fun setReminderEnabled(reminder:BabyReminder,enabled:Boolean)=viewModelScope.launch{babyApp.reminderRepository.setEnabled(reminder,enabled)}
    fun completeReminder(reminder:BabyReminder,day:Long=LocalDate.now().toEpochDay())=viewModelScope.launch{babyApp.reminderRepository.complete(reminder.id,day)}
    fun undoReminder(reminder:BabyReminder,day:Long=LocalDate.now().toEpochDay())=viewModelScope.launch{babyApp.reminderRepository.undo(reminder.id,day)}
    fun createFamily(name:String,onDone:(Result<String>)->Unit)=viewModelScope.launch{onDone(runCatching{babyApp.familySync.createFamily(name)})}
    fun joinFamily(code:String,name:String,onDone:(Result<Unit>)->Unit)=viewModelScope.launch{onDone(runCatching{babyApp.familySync.joinFamily(code,name)})}
    fun createInvite(onDone:(Result<String>)->Unit)=viewModelScope.launch{onDone(runCatching{babyApp.familySync.createInvite()})}
    fun retrySync()=viewModelScope.launch{babyApp.familySync.sync()}
    private suspend fun sync(){AppSurfaceSync.refresh(getApplication());babyApp.familySync.schedule()}
    private suspend fun csv(): File {
        val f=File(getApplication<Application>().cacheDir,"babylog.csv")
        val events=dao.allForExport()
        f.writeText(buildString { appendLine("type,detail,start,end,duration_minutes"); events.forEach { e -> appendLine("${e.type},${e.detail},${iso(e.startedAt)},${e.endedAt?.let(::iso).orEmpty()},${((e.endedAt?:System.currentTimeMillis())-e.startedAt)/60000}") } })
        return f
    }
    fun shareCsv(context: Context)=viewModelScope.launch { val file=withContext(Dispatchers.IO){csv()};val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/csv";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Экспорт истории")) }
    private fun iso(t:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date(t))
}
