package com.mark.babylog

import android.app.Application
import android.content.Intent
import android.content.Context
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mark.babylog.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.*
import java.util.*

data class UiState(val events: List<BabyEvent> = emptyList(), val segments: List<SleepSegment> = emptyList(), val selectedDay: LocalDate = LocalDate.now()) {
    val active get() = events.firstOrNull { it.endedAt == null }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = (app as BabyLogApp).database.events()
    private val day = MutableStateFlow(LocalDate.now())
    val state = combine(dao.observeAll(), dao.observeSegments(), day) { e, s, d -> UiState(e, s, d) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun moveDay(delta: Long) { day.value = day.value.plusDays(delta) }
    fun startFeeding(kind: FeedingKind) = viewModelScope.launch { dao.startFeeding(kind, System.currentTimeMillis()) }
    fun startSleep(position: SleepPosition) = viewModelScope.launch { dao.startSleep(position, System.currentTimeMillis()) }
    fun changePosition(position: SleepPosition) = viewModelScope.launch { val a=dao.active() ?: return@launch; if(a.type!=EventType.SLEEP)return@launch; val now=System.currentTimeMillis(); dao.finishSegment(a.id,now); dao.insert(SleepSegment(eventId=a.id,position=position,startedAt=now)); dao.update(a.copy(detail=position.name)) }
    fun stop() = viewModelScope.launch { dao.stopActive(System.currentTimeMillis()) }
    fun updateEvent(event: BabyEvent) = viewModelScope.launch { dao.update(event) }
    fun delete(event: BabyEvent) = viewModelScope.launch { dao.delete(event) }
    fun csv(): File {
        val f=File(getApplication<Application>().cacheDir,"babylog.csv")
        f.writeText(buildString { appendLine("type,detail,start,end,duration_minutes"); state.value.events.reversed().forEach { e -> appendLine("${e.type},${e.detail},${iso(e.startedAt)},${e.endedAt?.let(::iso).orEmpty()},${((e.endedAt?:System.currentTimeMillis())-e.startedAt)/60000}") } })
        return f
    }
    fun shareCsv(context: Context) { val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",csv());context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/csv";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Экспорт истории")) }
    private fun iso(t:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date(t))
}
