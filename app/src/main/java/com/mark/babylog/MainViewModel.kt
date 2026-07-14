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
import java.io.File
import java.text.SimpleDateFormat
import java.time.*
import java.util.*

data class UiState(val events: List<BabyEvent> = emptyList(), val segments: List<SleepSegment> = emptyList(), val selectedDay: LocalDate = LocalDate.now()) {
    val active get() = events.firstOrNull { it.type == EventType.FEEDING && it.endedAt == null }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val babyApp=app as BabyLogApp
    private val dao = babyApp.database.events()
    private val repository=babyApp.repository
    private val day = MutableStateFlow(LocalDate.now())
    val state = combine(dao.observeAll(), dao.observeSegments(), day) { e, s, d -> UiState(e, s, d) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())
    init { viewModelScope.launch { sync() } }

    fun moveDay(delta: Long) { day.value = day.value.plusDays(delta) }
    val membership=repository.membership.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val pendingCount=repository.pendingCount.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val syncStatus=babyApp.familySync.status.asStateFlow()
    val familyMembers=babyApp.familySync.members.asStateFlow()
    fun startFeeding(kind: FeedingKind) = viewModelScope.launch { repository.startFeeding(kind);sync() }
    fun startSleep(position: SleepPosition) = viewModelScope.launch { repository.startSleep(position);sync() }
    fun logPumping(side: FeedingKind, volumeMl: Int) = viewModelScope.launch { repository.logPumping(side,volumeMl);sync() }
    fun changePosition(position: SleepPosition) = viewModelScope.launch { repository.changePosition(position);sync() }
    fun stop() = viewModelScope.launch { repository.stop();sync() }
    fun updateEvent(event: BabyEvent) = viewModelScope.launch { repository.updateEvent(event);sync() }
    fun delete(event: BabyEvent) = viewModelScope.launch { repository.deleteEvent(event);sync() }
    fun createFamily(name:String,onDone:(Result<String>)->Unit)=viewModelScope.launch{onDone(runCatching{babyApp.familySync.createFamily(name)})}
    fun joinFamily(code:String,name:String,onDone:(Result<Unit>)->Unit)=viewModelScope.launch{onDone(runCatching{babyApp.familySync.joinFamily(code,name)})}
    fun createInvite(onDone:(Result<String>)->Unit)=viewModelScope.launch{onDone(runCatching{babyApp.familySync.createInvite()})}
    fun retrySync()=viewModelScope.launch{babyApp.familySync.sync()}
    private suspend fun sync(){AppSurfaceSync.refresh(getApplication());babyApp.familySync.schedule()}
    fun csv(): File {
        val f=File(getApplication<Application>().cacheDir,"babylog.csv")
        f.writeText(buildString { appendLine("type,detail,start,end,duration_minutes"); state.value.events.reversed().forEach { e -> appendLine("${e.type},${e.detail},${iso(e.startedAt)},${e.endedAt?.let(::iso).orEmpty()},${((e.endedAt?:System.currentTimeMillis())-e.startedAt)/60000}") } })
        return f
    }
    fun shareCsv(context: Context) { val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",csv());context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/csv";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Экспорт истории")) }
    private fun iso(t:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date(t))
}
