package com.mark.babylog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mark.babylog.data.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { BabyTheme { val vm:MainViewModel=viewModel(); BabyScreen(vm.state.collectAsStateWithLifecycle().value, vm) } } }
}

private val Light = lightColorScheme(primary=Color(0xFF65558F), secondary=Color(0xFF7D5260), surfaceVariant=Color(0xFFE7E0EC))
private val Dark = darkColorScheme(primary=Color(0xFFD0BCFF), secondary=Color(0xFFEFB8C8), background=Color(0xFF141218), surface=Color(0xFF211F26))
@Composable fun BabyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Dark else Light, typography=Typography(), content=content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BabyScreen(state: UiState, vm: MainViewModel? = null, fixedNow: Long? = null) {
    val context=LocalContext.current
    var now by remember { mutableLongStateOf(fixedNow ?: System.currentTimeMillis()) }
    LaunchedEffect(fixedNow) { if(fixedNow==null) while(true){ now=System.currentTimeMillis(); delay(1000) } }
    var edit by remember { mutableStateOf<BabyEvent?>(null) }
    val zone=ZoneId.systemDefault(); val start=state.selectedDay.atStartOfDay(zone).toInstant().toEpochMilli(); val end=state.selectedDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val today=state.events.filter { it.startedAt in start until end }
    val active=state.active
    Scaffold(topBar={ TopAppBar(title={Text("BabyLog",fontWeight=FontWeight.Bold)},actions={IconButton(onClick={vm?.shareCsv(context)}){Icon(Icons.Default.FileDownload,"Экспорт CSV")}}) },bottomBar={QuickActions(active,vm)}) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { DayHeader(state.selectedDay,{vm?.moveDay(-1)},{vm?.moveDay(1)}) }
            item { Stats(today,state.segments,now) }
            if(today.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center){Text("Сегодня событий пока нет",color=MaterialTheme.colorScheme.onSurfaceVariant)} }
            items(today,key={it.id}) { EventRow(it,now){edit=it} }
            item { StatusCard(active,state.events,now,vm) }
        }
    }
    edit?.let { original -> EditDialog(original,onDismiss={edit=null},onSave={vm?.updateEvent(it);edit=null},onDelete={vm?.delete(original);edit=null}) }
}

@Composable private fun QuickActions(active:BabyEvent?,vm:MainViewModel?){
    var more by remember{mutableStateOf(false)}
    Surface(shadowElevation=12.dp,tonalElevation=3.dp){Column(Modifier.navigationBarsPadding().padding(12.dp,10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Быстрые действия",fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="LEFT")"Стоп" else "Левая",if(active?.type==EventType.FEEDING&&active.detail=="LEFT")Icons.Default.Stop else Icons.Default.ArrowBack,Modifier.weight(1f)){if(active?.type==EventType.FEEDING&&active.detail=="LEFT")vm?.stop() else vm?.startFeeding(FeedingKind.LEFT)}
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")"Стоп" else "Правая",if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")Icons.Default.Stop else Icons.Default.ArrowForward,Modifier.weight(1f)){if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")vm?.stop() else vm?.startFeeding(FeedingKind.RIGHT)}
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")"Стоп" else "Бутылочка",if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")Icons.Default.Stop else Icons.Default.LocalDrink,Modifier.weight(1f)){if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")vm?.stop() else vm?.startFeeding(FeedingKind.BOTTLE)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={if(active?.type==EventType.SLEEP&&active.detail=="LEFT")vm?.stop() else vm?.startSleep(SleepPosition.LEFT)},Modifier.weight(1f).height(52.dp)){Icon(if(active?.type==EventType.SLEEP&&active.detail=="LEFT")Icons.Default.Stop else Icons.Default.Bedtime,null);Spacer(Modifier.width(5.dp));Text(if(active?.type==EventType.SLEEP&&active.detail=="LEFT")"Стоп" else "Сон · лево")}
            Button(onClick={if(active?.type==EventType.SLEEP&&active.detail=="RIGHT")vm?.stop() else vm?.startSleep(SleepPosition.RIGHT)},Modifier.weight(1f).height(52.dp)){Icon(if(active?.type==EventType.SLEEP&&active.detail=="RIGHT")Icons.Default.Stop else Icons.Default.Bedtime,null);Spacer(Modifier.width(5.dp));Text(if(active?.type==EventType.SLEEP&&active.detail=="RIGHT")"Стоп" else "Сон · право")}
            Box{FilledTonalIconButton(onClick={more=true},modifier=Modifier.size(52.dp)){Icon(Icons.Default.MoreHoriz,"Другие положения")};DropdownMenu(more,{more=false}){listOf(SleepPosition.BACK,SleepPosition.OTHER).forEach{p->DropdownMenuItem({Text(posName(p))},{more=false;vm?.startSleep(p)})}}}
        }
        if(active?.type==EventType.SLEEP)Column{Text("Сменить положение",style=MaterialTheme.typography.labelMedium);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){SleepPosition.entries.forEach{p->FilterChip(selected=active.detail==p.name,onClick={vm?.changePosition(p)},label={Text(shortPos(p),maxLines=1)},modifier=Modifier.weight(1f))}}}
    }}
}

@Composable private fun StatusCard(active:BabyEvent?, events:List<BabyEvent>, now:Long, vm:MainViewModel?) {
    val lastFeed=events.firstOrNull{it.type==EventType.FEEDING}
    Card(colors=CardDefaults.cardColors(containerColor=if(active!=null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(if(active?.type==EventType.SLEEP) Icons.Default.Bedtime else Icons.Default.ChildCare,null,modifier=Modifier.size(32.dp))
        Text(when { active?.type==EventType.SLEEP -> "Спит ${duration(now-active.startedAt)}"; active?.type==EventType.FEEDING -> "Кормление · ${feedName(active.detail)}"; lastFeed!=null -> "Последнее кормление ${ago(now-lastFeed.startedAt)}"; else -> "Всё спокойно" },fontSize=25.sp,fontWeight=FontWeight.Bold)
        if(active!=null){ Text("Начато в ${clock(active.startedAt)} · ${duration(now-active.startedAt)}"); Button(onClick={vm?.stop()},modifier=Modifier.fillMaxWidth().height(52.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text("Остановить")}}
        else if(lastFeed!=null) Text("${feedName(lastFeed.detail)} использовалась последней",color=MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable private fun ActionButton(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,onClick:()->Unit){FilledTonalButton(onClick=onClick,modifier=modifier.height(64.dp),contentPadding=PaddingValues(6.dp)){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null);Text(text,fontSize=12.sp)}}}
@Composable private fun DayHeader(day:LocalDate,prev:()->Unit,next:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){IconButton(prev){Icon(Icons.Default.ChevronLeft,"Предыдущий день")};Text(if(day==LocalDate.now())"Сегодня" else day.format(DateTimeFormatter.ofPattern("d MMMM")),fontWeight=FontWeight.Bold,fontSize=19.sp);IconButton(next,enabled=day<LocalDate.now()){Icon(Icons.Default.ChevronRight,"Следующий день")}}}
@Composable private fun Stats(events:List<BabyEvent>,segments:List<SleepSegment>,now:Long){
    val feeds=events.count{it.type==EventType.FEEDING};val sleepEvents=events.filter{it.type==EventType.SLEEP};val ids=sleepEvents.map{it.id}.toSet();val sleep=sleepEvents.sumOf{(it.endedAt?:now)-it.startedAt}
    fun side(p:SleepPosition)=segments.filter{it.eventId in ids&&it.position==p}.sumOf{(it.endedAt?:now)-it.startedAt}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniStat("Кормлений","$feeds",Modifier.weight(1f));MiniStat("Сна",duration(sleep),Modifier.weight(1f));MiniStat("Событий","${events.size}",Modifier.weight(1f))};if(sleepEvents.isNotEmpty())Text("Сон по положениям: лево ${duration(side(SleepPosition.LEFT))} · право ${duration(side(SleepPosition.RIGHT))} · спина ${duration(side(SleepPosition.BACK))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
@Composable private fun MiniStat(label:String,value:String,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(12.dp)){Text(value,fontWeight=FontWeight.Bold,fontSize=18.sp);Text(label,fontSize=12.sp)}}}
@Composable private fun EventRow(e:BabyEvent,now:Long,edit:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){Column(horizontalAlignment=Alignment.CenterHorizontally){Surface(shape=CircleShape,color=if(e.type==EventType.SLEEP)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer){Icon(if(e.type==EventType.SLEEP)Icons.Default.Bedtime else Icons.Default.Restaurant,null,Modifier.padding(9.dp))}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(if(e.type==EventType.SLEEP)"Сон · ${posName(SleepPosition.valueOf(e.detail))}" else "Кормление · ${feedName(e.detail)}",fontWeight=FontWeight.SemiBold);Text("${clock(e.startedAt)} – ${e.endedAt?.let(::clock)?:"сейчас"} · ${duration((e.endedAt?:now)-e.startedAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(edit){Icon(Icons.Default.Edit,"Изменить")}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditDialog(e:BabyEvent,onDismiss:()->Unit,onSave:(BabyEvent)->Unit,onDelete:()->Unit){
    var start by remember { mutableLongStateOf(e.startedAt) }; var end by remember { mutableStateOf(e.endedAt) }; var detail by remember { mutableStateOf(e.detail) }
    var picking by remember { mutableStateOf<String?>(null) }
    val options=if(e.type==EventType.FEEDING) FeedingKind.entries.map{it.name to feedName(it.name)} else SleepPosition.entries.map{it.name to posName(it)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить событие")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Время",fontWeight=FontWeight.SemiBold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={picking="start"},modifier=Modifier.weight(1f)){Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(5.dp));Text("Начало ${clock(start)}")};OutlinedButton(onClick={picking="end"},modifier=Modifier.weight(1f)){Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(5.dp));Text("Конец ${end?.let(::clock)?:"сейчас"}")}}
        Text("Тип / положение",fontWeight=FontWeight.SemiBold);options.forEach{(key,label)->FilterChip(selected=detail==key,onClick={detail=key},label={Text(label)})}
    }},confirmButton={TextButton(onClick={onSave(e.copy(startedAt=start,endedAt=end?.coerceAtLeast(start),detail=detail))}){Text("Сохранить")}},dismissButton={Row{TextButton(onClick=onDelete){Text("Удалить",color=MaterialTheme.colorScheme.error)};TextButton(onClick=onDismiss){Text("Отмена")}}})
    picking?.let{target->val base=if(target=="start")start else end?:System.currentTimeMillis();val cal=remember(target){java.util.Calendar.getInstance().apply{timeInMillis=base}};val picker=rememberTimePickerState(cal.get(java.util.Calendar.HOUR_OF_DAY),cal.get(java.util.Calendar.MINUTE),true);AlertDialog(onDismissRequest={picking=null},title={Text(if(target=="start")"Время начала" else "Время окончания")},text={TimePicker(picker)},confirmButton={TextButton(onClick={val chosen=withTime(base,picker.hour,picker.minute);if(target=="start")start=chosen else end=chosen;picking=null}){Text("Готово")}},dismissButton={TextButton(onClick={picking=null}){Text("Отмена")}})}
}
private fun duration(ms:Long):String { val total=ms.coerceAtLeast(0)/1000;val h=total/3600;val m=(total%3600)/60;val s=total%60;return if(h>0)"$h ч ${m.toString().padStart(2,'0')} мин ${s.toString().padStart(2,'0')} сек" else "$m мин ${s.toString().padStart(2,'0')} сек" }
private fun ago(ms:Long)="${duration(ms)} назад"
private fun clock(ms:Long)=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(ms))
private fun withTime(epoch:Long,hour:Int,minute:Int)=java.util.Calendar.getInstance().apply{timeInMillis=epoch;set(java.util.Calendar.HOUR_OF_DAY,hour);set(java.util.Calendar.MINUTE,minute);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
private fun feedName(s:String)=when(s){"LEFT"->"левая грудь";"RIGHT"->"правая грудь";else->"бутылочка"}
private fun posName(p:SleepPosition)=when(p){SleepPosition.LEFT->"Левый бок";SleepPosition.RIGHT->"Правый бок";SleepPosition.BACK->"На спине";SleepPosition.OTHER->"Другое"}
private fun shortPos(p:SleepPosition)=when(p){SleepPosition.LEFT->"Лево";SleepPosition.RIGHT->"Право";SleepPosition.BACK->"Спина";SleepPosition.OTHER->"Другое"}
