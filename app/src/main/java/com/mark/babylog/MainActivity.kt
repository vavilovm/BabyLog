package com.mark.babylog

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState);val invite=intent?.data?.takeIf{it.scheme=="babylog"&&it.host=="join"}?.getQueryParameter("code");enableEdgeToEdge(); setContent { BabyTheme { NotificationPermission();val vm:MainViewModel=viewModel(); BabyScreen(vm.state.collectAsStateWithLifecycle().value, vm,initialInvite=invite) } } }
}

@Composable private fun NotificationPermission(){val context=LocalContext.current;val scope=rememberCoroutineScope();val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)scope.launch{AppSurfaceSync.refresh(context)}};LaunchedEffect(Unit){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)launcher.launch(Manifest.permission.POST_NOTIFICATIONS)}}

private val Light = lightColorScheme(primary=Color(0xFF65558F), secondary=Color(0xFF7D5260), surfaceVariant=Color(0xFFE7E0EC))
private val Dark = darkColorScheme(primary=Color(0xFFD0BCFF), secondary=Color(0xFFEFB8C8), background=Color(0xFF141218), surface=Color(0xFF211F26))
@Composable fun BabyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Dark else Light, typography=Typography(), content=content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BabyScreen(state: UiState, vm: MainViewModel? = null, fixedNow: Long? = null,initialInvite:String?=null) {
    val context=LocalContext.current
    var now by remember { mutableLongStateOf(fixedNow ?: System.currentTimeMillis()) }
    LaunchedEffect(fixedNow) { if(fixedNow==null) while(true){ now=System.currentTimeMillis(); delay(1000) } }
    var edit by remember { mutableStateOf<BabyEvent?>(null) }
    var familyOpen by remember { mutableStateOf(initialInvite!=null) }
    val membership=vm?.membership?.collectAsStateWithLifecycle()?.value
    val pending=vm?.pendingCount?.collectAsStateWithLifecycle()?.value?:0
    val syncStatus=vm?.syncStatus?.collectAsStateWithLifecycle()?.value
    val zone=ZoneId.systemDefault(); val start=state.selectedDay.atStartOfDay(zone).toInstant().toEpochMilli(); val end=state.selectedDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val today=state.events.filter { it.startedAt in start until end }
    val active=state.active
    Scaffold(topBar={ TopAppBar(title={Text("BabyLog",fontWeight=FontWeight.Bold)},actions={if(syncStatus?.error!=null)Text("!",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold) else if(pending>0)Text("↻ $pending",color=MaterialTheme.colorScheme.primary);IconButton(onClick={familyOpen=true}){Icon(if(membership==null)Icons.Default.GroupAdd else Icons.Default.Group,"Семья")};IconButton(onClick={vm?.shareCsv(context)}){Icon(Icons.Default.FileDownload,"Экспорт CSV")}}) },bottomBar={QuickActions(active,vm)}) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { StatusCard(active,state.events,now,vm) }
            item { DayHeader(state.selectedDay,{vm?.moveDay(-1)},{vm?.moveDay(1)}) }
            item { Stats(today,state.segments,now) }
            if(today.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center){Text("Сегодня событий пока нет",color=MaterialTheme.colorScheme.onSurfaceVariant)} }
            items(today,key={it.id}) { EventRow(it,now){edit=it} }
        }
    }
    edit?.let { original -> EditDialog(original,onDismiss={edit=null},onSave={vm?.updateEvent(it);edit=null},onDelete={vm?.delete(original);edit=null}) }
    if(familyOpen&&vm!=null)FamilyDialog(membership,vm,initialInvite.orEmpty()){familyOpen=false}
}

@Composable private fun FamilyDialog(membership:FamilyMembership?,vm:MainViewModel,initialCode:String,onDismiss:()->Unit){var name by remember{mutableStateOf("")};var code by remember{mutableStateOf(initialCode.ifBlank{membership?.inviteCode.orEmpty()})};var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)};val members=vm.familyMembers.collectAsStateWithLifecycle().value;AlertDialog(onDismissRequest=onDismiss,title={Text(if(membership==null)"Семейная синхронизация" else "Ваша семья")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){if(membership==null){OutlinedTextField(name,{name=it},label={Text("Ваше имя")},singleLine=true);OutlinedTextField(code,{code=it.uppercase()},label={Text("Код приглашения")},singleLine=true);Text("Оставьте код пустым, чтобы создать новую семью.",style=MaterialTheme.typography.bodySmall)}else{Text("Участники: ${members.joinToString{it.displayName}.ifBlank{membership.displayName}}");if(code.isNotBlank()){QrCode(code);Text("Код: $code",fontWeight=FontWeight.Bold)};Button(onClick={busy=true;vm.createInvite{result->busy=false;result.onSuccess{code=it}.onFailure{error=it.message}}},enabled=!busy){Text("Создать приглашение")};OutlinedButton(onClick={vm.retrySync()}){Text("Синхронизировать сейчас")}};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={if(membership==null)Button(onClick={busy=true;if(code.isBlank())vm.createFamily(name){result->busy=false;result.onSuccess{code=it}.onFailure{error=it.message}} else vm.joinFamily(code,name){result->busy=false;result.onSuccess{onDismiss()}.onFailure{error=it.message}}},enabled=!busy&&name.isNotBlank()){Text(if(code.isBlank())"Создать семью" else "Подключиться")}else TextButton(onClick=onDismiss){Text("Готово")}},dismissButton={TextButton(onClick=onDismiss){Text("Закрыть")}})}

@Composable private fun QrCode(value:String){val bitmap=remember(value){val matrix=com.google.zxing.MultiFormatWriter().encode("babylog://join?code=$value",com.google.zxing.BarcodeFormat.QR_CODE,420,420);android.graphics.Bitmap.createBitmap(420,420,android.graphics.Bitmap.Config.RGB_565).apply{for(y in 0 until 420)for(x in 0 until 420)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}};Image(bitmap.asImageBitmap(),"QR-код приглашения",Modifier.size(180.dp))}

@Composable private fun QuickActions(active:BabyEvent?,vm:MainViewModel?){
    Surface(shadowElevation=12.dp,tonalElevation=3.dp){Column(Modifier.navigationBarsPadding().padding(12.dp,10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Быстрые действия",fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="LEFT")"Стоп" else "Левая",if(active?.type==EventType.FEEDING&&active.detail=="LEFT")Icons.Default.Stop else Icons.Default.ArrowBack,Modifier.weight(1f),active?.type==EventType.FEEDING&&active.detail=="LEFT"){if(active?.type==EventType.FEEDING&&active.detail=="LEFT")vm?.stop() else vm?.startFeeding(FeedingKind.LEFT)}
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")"Стоп" else "Правая",if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")Icons.Default.Stop else Icons.Default.ArrowForward,Modifier.weight(1f),active?.type==EventType.FEEDING&&active.detail=="RIGHT"){if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")vm?.stop() else vm?.startFeeding(FeedingKind.RIGHT)}
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")"Стоп" else "Бутылочка",if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")Icons.Default.Stop else Icons.Default.LocalDrink,Modifier.weight(1f),active?.type==EventType.FEEDING&&active.detail=="BOTTLE"){if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")vm?.stop() else vm?.startFeeding(FeedingKind.BOTTLE)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val stopLeft=active?.type==EventType.SLEEP&&active.detail=="LEFT";Button(onClick={if(stopLeft)vm?.stop() else vm?.startSleep(SleepPosition.LEFT)},Modifier.weight(1f).height(52.dp),colors=if(stopLeft)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()){Icon(if(stopLeft)Icons.Default.Stop else Icons.Default.Bedtime,null);Spacer(Modifier.width(5.dp));Text(if(stopLeft)"Стоп" else "Лево")}
            val stopRight=active?.type==EventType.SLEEP&&active.detail=="RIGHT";Button(onClick={if(stopRight)vm?.stop() else vm?.startSleep(SleepPosition.RIGHT)},Modifier.weight(1f).height(52.dp),colors=if(stopRight)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()){Icon(if(stopRight)Icons.Default.Stop else Icons.Default.Bedtime,null);Spacer(Modifier.width(5.dp));Text(if(stopRight)"Стоп" else "Право")}
        }
        if(active?.type==EventType.SLEEP)Column{Text("Положение головы",style=MaterialTheme.typography.labelMedium);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(SleepPosition.LEFT,SleepPosition.RIGHT).forEach{p->FilterChip(selected=active.detail==p.name,onClick={vm?.changePosition(p)},label={Text(shortPos(p),maxLines=1)},modifier=Modifier.weight(1f))}}}
    }}
}

@Composable private fun StatusCard(active:BabyEvent?, events:List<BabyEvent>, now:Long, vm:MainViewModel?) {
    val lastFeed=events.firstOrNull{it.type==EventType.FEEDING}
    val shown=active?:lastFeed
    Card(colors=CardDefaults.cardColors(containerColor=if(active!=null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){
            if(shown!=null)EventMarker(shown,Modifier.size(42.dp)) else Surface(Modifier.size(42.dp),CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.ChildCare,null)}}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){
                Text(when{active?.type==EventType.SLEEP->"Сон · ${posName(SleepPosition.valueOf(active.detail))}";active?.type==EventType.FEEDING->"Кормление · ${feedName(active.detail)}";lastFeed!=null->"Последнее кормление";else->"Всё спокойно"},fontSize=21.sp,fontWeight=FontWeight.Bold,maxLines=1)
                if(active!=null)Text("${clock(active.startedAt)} · ${durationCompact(now-active.startedAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                else if(lastFeed!=null)Text("${feedName(lastFeed.detail)} · ${agoCompact(now-lastFeed.startedAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(active!=null)Button(onClick={vm?.stop()},modifier=Modifier.fillMaxWidth().height(44.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text("Остановить")}
    } }
}

@Composable private fun ActionButton(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,isStop:Boolean=false,onClick:()->Unit){FilledTonalButton(onClick=onClick,modifier=modifier.height(64.dp),contentPadding=PaddingValues(6.dp),colors=if(isStop)ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError) else ButtonDefaults.filledTonalButtonColors()){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null);Text(text,fontSize=12.sp)}}}
@Composable private fun DayHeader(day:LocalDate,prev:()->Unit,next:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){IconButton(prev){Icon(Icons.Default.ChevronLeft,"Предыдущий день")};Text(if(day==LocalDate.now())"Сегодня" else day.format(DateTimeFormatter.ofPattern("d MMMM")),fontWeight=FontWeight.Bold,fontSize=19.sp);IconButton(next,enabled=day<LocalDate.now()){Icon(Icons.Default.ChevronRight,"Следующий день")}}}
@Composable private fun Stats(events:List<BabyEvent>,segments:List<SleepSegment>,now:Long){
    val sleepEvents=events.filter{it.type==EventType.SLEEP};val ids=sleepEvents.map{it.id}.toSet()
    fun feed(kind:FeedingKind)=events.filter{it.type==EventType.FEEDING&&it.detail==kind.name}.sumOf{(it.endedAt?:now)-it.startedAt}
    fun side(p:SleepPosition)=segments.filter{it.eventId in ids&&it.position==p}.sumOf{(it.endedAt?:now)-it.startedAt}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("За день",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniStat("L","Грудь",durationCompact(feed(FeedingKind.LEFT)),Modifier.weight(1f));MiniStat("R","Грудь",durationCompact(feed(FeedingKind.RIGHT)),Modifier.weight(1f))};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniStat("L","Сон",durationCompact(side(SleepPosition.LEFT)),Modifier.weight(1f));MiniStat("R","Сон",durationCompact(side(SleepPosition.RIGHT)),Modifier.weight(1f))}}
}
@Composable private fun MiniStat(side:String,label:String,value:String,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(30.dp),CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Text(side,fontWeight=FontWeight.Bold)}};Spacer(Modifier.width(8.dp));Column{Text(value,fontWeight=FontWeight.Bold,fontSize=17.sp,maxLines=1);Text(label,fontSize=12.sp)}}}}
@Composable private fun EventMarker(e:BabyEvent,modifier:Modifier=Modifier){val color=if(e.type==EventType.SLEEP)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer;Surface(modifier,shape=CircleShape,color=color){Box(contentAlignment=Alignment.Center){when(e.detail){"LEFT"->Text("L",fontWeight=FontWeight.Bold,fontSize=17.sp);"RIGHT"->Text("R",fontWeight=FontWeight.Bold,fontSize=17.sp);"BOTTLE"->Icon(Icons.Default.LocalDrink,"Бутылочка",Modifier.size(22.dp));else->Icon(Icons.Default.Bedtime,"Сон",Modifier.size(22.dp))}}}}
@Composable private fun EventRow(e:BabyEvent,now:Long,edit:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){EventMarker(e,Modifier.size(38.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text((if(e.type==EventType.SLEEP)"Сон · ${posName(SleepPosition.valueOf(e.detail))}" else "Кормление · ${feedName(e.detail)}")+(e.authorName?.let{" · $it"}?:""),fontWeight=FontWeight.SemiBold);Text("${clock(e.startedAt)} – ${e.endedAt?.let(::clock)?:"сейчас"} · ${durationCompact((e.endedAt?:now)-e.startedAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(edit){Icon(Icons.Default.Edit,"Изменить")}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditDialog(e:BabyEvent,onDismiss:()->Unit,onSave:(BabyEvent)->Unit,onDelete:()->Unit){
    var start by remember { mutableLongStateOf(e.startedAt) }; var end by remember { mutableStateOf(e.endedAt) }; var detail by remember { mutableStateOf(e.detail) }
    var picking by remember { mutableStateOf<String?>(null) }
    val options=if(e.type==EventType.FEEDING) FeedingKind.entries.map{it.name to feedName(it.name)} else listOf(SleepPosition.LEFT,SleepPosition.RIGHT).map{it.name to posName(it)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить событие")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Время",fontWeight=FontWeight.SemiBold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={picking="start"},modifier=Modifier.weight(1f)){Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(5.dp));Text("Начало ${clock(start)}")};OutlinedButton(onClick={picking="end"},modifier=Modifier.weight(1f)){Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(5.dp));Text("Конец ${end?.let(::clock)?:"сейчас"}")}}
        Text("Тип / положение",fontWeight=FontWeight.SemiBold);options.forEach{(key,label)->FilterChip(selected=detail==key,onClick={detail=key},label={Text(label)})}
    }},confirmButton={TextButton(onClick={onSave(e.copy(startedAt=start,endedAt=end?.coerceAtLeast(start),detail=detail))}){Text("Сохранить")}},dismissButton={Row{TextButton(onClick=onDelete){Text("Удалить",color=MaterialTheme.colorScheme.error)};TextButton(onClick=onDismiss){Text("Отмена")}}})
    picking?.let{target->val base=if(target=="start")start else end?:System.currentTimeMillis();val cal=remember(target){java.util.Calendar.getInstance().apply{timeInMillis=base}};val picker=rememberTimePickerState(cal.get(java.util.Calendar.HOUR_OF_DAY),cal.get(java.util.Calendar.MINUTE),true);AlertDialog(onDismissRequest={picking=null},title={Text(if(target=="start")"Время начала" else "Время окончания")},text={TimePicker(picker)},confirmButton={TextButton(onClick={val chosen=withTime(base,picker.hour,picker.minute);if(target=="start")start=chosen else end=chosen;picking=null}){Text("Готово")}},dismissButton={TextButton(onClick={picking=null}){Text("Отмена")}})}
}
private fun durationCompact(ms:Long):String {val totalMinutes=ms.coerceAtLeast(0)/60000;val h=totalMinutes/60;val m=totalMinutes%60;return if(h>0)"$h ч ${m.toString().padStart(2,'0')} мин" else "$m мин"}
private fun agoCompact(ms:Long)="${durationCompact(ms)} назад"
private fun clock(ms:Long)=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(ms))
private fun withTime(epoch:Long,hour:Int,minute:Int)=java.util.Calendar.getInstance().apply{timeInMillis=epoch;set(java.util.Calendar.HOUR_OF_DAY,hour);set(java.util.Calendar.MINUTE,minute);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
private fun feedName(s:String)=when(s){"LEFT"->"левая грудь";"RIGHT"->"правая грудь";else->"бутылочка"}
private fun posName(p:SleepPosition)=when(p){SleepPosition.LEFT->"Голова слева";SleepPosition.RIGHT->"Голова справа";SleepPosition.BACK, SleepPosition.OTHER->"Другое"}
private fun shortPos(p:SleepPosition)=when(p){SleepPosition.LEFT->"Лево";SleepPosition.RIGHT->"Право";SleepPosition.BACK, SleepPosition.OTHER->"Другое"}
