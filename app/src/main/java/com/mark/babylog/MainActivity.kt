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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
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
    var statsOpen by remember { mutableStateOf(false) }
    var pumpingOpen by remember { mutableStateOf(false) }
    val membership=vm?.membership?.collectAsStateWithLifecycle()?.value
    val pending=vm?.pendingCount?.collectAsStateWithLifecycle()?.value?:0
    val syncStatus=vm?.syncStatus?.collectAsStateWithLifecycle()?.value
    val zone=ZoneId.systemDefault(); val start=state.selectedDay.atStartOfDay(zone).toInstant().toEpochMilli(); val end=state.selectedDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val today=state.events.filter { it.startedAt in start until end }
    val active=state.active
    Scaffold(topBar={ TopAppBar(title={Text("BabyLog",fontWeight=FontWeight.Bold)},actions={if(syncStatus?.error!=null)Text("!",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold) else if(pending>0)Text("↻ $pending",color=MaterialTheme.colorScheme.primary);IconButton(onClick={familyOpen=true}){Icon(if(membership==null)Icons.Default.GroupAdd else Icons.Default.Group,"Семья")};IconButton(onClick={vm?.shareCsv(context)}){Icon(Icons.Default.FileDownload,"Экспорт CSV")}}) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { StatusCard(active,state.events,now,vm){statsOpen=true} }
            item { DayHeader(state.selectedDay,{vm?.moveDay(-1)},{vm?.moveDay(1)}) }
            item { QuickActions(active,state.events,vm,{pumpingOpen=true}) }
            if(today.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center){Text("Сегодня событий пока нет",color=MaterialTheme.colorScheme.onSurfaceVariant)} }
            else item { Text("История",fontWeight=FontWeight.Bold,fontSize=18.sp,modifier=Modifier.padding(top=4.dp)) }
            items(today,key={it.id}) { EventRow(it,now){edit=it} }
        }
    }
    edit?.let { original -> EditDialog(original,onDismiss={edit=null},onSave={vm?.updateEvent(it);edit=null},onDelete={vm?.delete(original);edit=null}) }
    if(pumpingOpen)PumpingDialog(defaultPumpingSide(state.events),onDismiss={pumpingOpen=false}) { side, volume -> vm?.logPumping(side,volume);pumpingOpen=false }
    if(familyOpen&&vm!=null)FamilyDialog(membership,vm,initialInvite.orEmpty()){familyOpen=false}
    if(statsOpen)AlertDialog(onDismissRequest={statsOpen=false},title={Text("За ${if(state.selectedDay==LocalDate.now())"сегодня" else state.selectedDay.format(DateTimeFormatter.ofPattern("d MMMM"))}")},text={Stats(today,state.segments,now,false)},confirmButton={TextButton(onClick={statsOpen=false}){Text("Готово")}})
}

@Composable private fun FamilyDialog(membership:FamilyMembership?,vm:MainViewModel,initialCode:String,onDismiss:()->Unit){var name by remember{mutableStateOf("")};var code by remember{mutableStateOf(initialCode.ifBlank{membership?.inviteCode.orEmpty()})};var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)};val members=vm.familyMembers.collectAsStateWithLifecycle().value;AlertDialog(onDismissRequest=onDismiss,title={Text(if(membership==null)"Семейная синхронизация" else "Ваша семья")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){if(membership==null){OutlinedTextField(name,{name=it},label={Text("Ваше имя")},singleLine=true);OutlinedTextField(code,{code=it.uppercase()},label={Text("Код приглашения")},singleLine=true);Text("Оставьте код пустым, чтобы создать новую семью.",style=MaterialTheme.typography.bodySmall)}else{Text("Участники: ${members.joinToString{it.displayName}.ifBlank{membership.displayName}}");if(code.isNotBlank()){QrCode(code);Text("Код: $code",fontWeight=FontWeight.Bold)};Button(onClick={busy=true;vm.createInvite{result->busy=false;result.onSuccess{code=it}.onFailure{error=it.message}}},enabled=!busy){Text("Создать приглашение")};OutlinedButton(onClick={vm.retrySync()}){Text("Синхронизировать сейчас")}};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={if(membership==null)Button(onClick={busy=true;if(code.isBlank())vm.createFamily(name){result->busy=false;result.onSuccess{code=it}.onFailure{error=it.message}} else vm.joinFamily(code,name){result->busy=false;result.onSuccess{onDismiss()}.onFailure{error=it.message}}},enabled=!busy&&name.isNotBlank()){Text(if(code.isBlank())"Создать семью" else "Подключиться")}else TextButton(onClick=onDismiss){Text("Готово")}},dismissButton={TextButton(onClick=onDismiss){Text("Закрыть")}})}

@Composable private fun QrCode(value:String){val bitmap=remember(value){val matrix=com.google.zxing.MultiFormatWriter().encode("babylog://join?code=$value",com.google.zxing.BarcodeFormat.QR_CODE,420,420);android.graphics.Bitmap.createBitmap(420,420,android.graphics.Bitmap.Config.RGB_565).apply{for(y in 0 until 420)for(x in 0 until 420)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}};Image(bitmap.asImageBitmap(),"QR-код приглашения",Modifier.size(180.dp))}

@Composable private fun QuickActions(active:BabyEvent?,events:List<BabyEvent>,vm:MainViewModel?,onPumping:()->Unit){
    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=3.dp){Column(Modifier.padding(12.dp,10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Быстрые действия",fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="LEFT")"Стоп" else "L",if(active?.type==EventType.FEEDING&&active.detail=="LEFT")Icons.Default.Stop else null,Modifier.weight(1f),active?.type==EventType.FEEDING&&active.detail=="LEFT"){if(active?.type==EventType.FEEDING&&active.detail=="LEFT")vm?.stop() else vm?.startFeeding(FeedingKind.LEFT)}
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")"Стоп" else "R",if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")Icons.Default.Stop else null,Modifier.weight(1f),active?.type==EventType.FEEDING&&active.detail=="RIGHT"){if(active?.type==EventType.FEEDING&&active.detail=="RIGHT")vm?.stop() else vm?.startFeeding(FeedingKind.RIGHT)}
            ActionButton(if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")"Стоп" else "Бутылочка",if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")Icons.Default.Stop else Icons.Default.LocalDrink,Modifier.weight(1f),active?.type==EventType.FEEDING&&active.detail=="BOTTLE"){if(active?.type==EventType.FEEDING&&active.detail=="BOTTLE")vm?.stop() else vm?.startFeeding(FeedingKind.BOTTLE)}
        }
        OutlinedButton(onClick=onPumping,modifier=Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Default.WaterDrop,null);Spacer(Modifier.width(8.dp));Text("Сцеживание") }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            SleepButton(SleepPosition.LEFT,Modifier.weight(1f)){vm?.startSleep(SleepPosition.LEFT)}
            SleepButton(SleepPosition.RIGHT,Modifier.weight(1f)){vm?.startSleep(SleepPosition.RIGHT)}
        }
    }}
}

@Composable internal fun PumpingDialog(defaultSide:FeedingKind,onDismiss:()->Unit,onSave:(FeedingKind,Int)->Unit){
    var side by remember { mutableStateOf(defaultSide) }
    var volume by remember { mutableStateOf("") }
    val focusRequester=remember { FocusRequester() }
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus();keyboard?.show() }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Сцеживание")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("Укажите объём сцеженного молока. Время завершения будет записано как сейчас.",style=MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value=volume,onValueChange={volume=it.filter(Char::isDigit)},label={Text("Объём, мл")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().focusRequester(focusRequester))
        Text("Из какой груди?",fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=side==FeedingKind.LEFT,onClick={side=FeedingKind.LEFT},label={Text("Левая")});FilterChip(selected=side==FeedingKind.RIGHT,onClick={side=FeedingKind.RIGHT},label={Text("Правая")})}
    }},confirmButton={TextButton(onClick={onSave(side,volume.toInt())},enabled=volume.toIntOrNull()?.let{it>0}==true){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

internal fun defaultPumpingSide(events:List<BabyEvent>):FeedingKind=when(events.firstOrNull{it.type==EventType.FEEDING}?.detail){
    FeedingKind.LEFT.name->FeedingKind.RIGHT
    FeedingKind.RIGHT.name->FeedingKind.LEFT
    else->FeedingKind.LEFT
}

@Composable private fun SleepButton(position:SleepPosition,modifier:Modifier,onClick:()->Unit){val left=position==SleepPosition.LEFT;Button(onClick=onClick,modifier=modifier.height(58.dp),contentPadding=PaddingValues(2.dp)){Image(painterResource(if(left)R.drawable.duck_sleep_left else R.drawable.duck_sleep_right),if(left)"Отметить сон на левом боку" else "Отметить сон на правом боку",Modifier.fillMaxSize())}}

@Composable private fun StatusCard(active:BabyEvent?, events:List<BabyEvent>, now:Long, vm:MainViewModel?,onStats:()->Unit) {
    val lastFeed=events.firstOrNull{it.type==EventType.FEEDING}
    val shown=active?:lastFeed
    Card(colors=CardDefaults.cardColors(containerColor=if(active!=null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth().clickable(onClick=onStats)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){
            if(shown!=null)EventMarker(shown,Modifier.size(42.dp)) else Surface(Modifier.size(42.dp),CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.ChildCare,null)}}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){
                Text(when{active?.type==EventType.SLEEP->"Сон · ${posName(SleepPosition.valueOf(active.detail))}";active?.type==EventType.FEEDING->"Кормление · ${feedName(active.detail)}";lastFeed!=null->"Последнее кормление";else->"Всё спокойно"},fontSize=21.sp,fontWeight=FontWeight.Bold,maxLines=1)
                if(active!=null)Text("${clock(active.startedAt)} · ${durationLive(now-active.startedAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                else if(lastFeed!=null)Text("${feedName(lastFeed.detail)} · ${agoCompact(now-(lastFeed.endedAt?:lastFeed.startedAt))}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(active!=null)Button(onClick={vm?.stop()},modifier=Modifier.fillMaxWidth().height(44.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text("Остановить")}
    } }
}

@Composable private fun ActionButton(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector?,modifier:Modifier,isStop:Boolean=false,onClick:()->Unit){FilledTonalButton(onClick=onClick,modifier=modifier.height(64.dp),contentPadding=PaddingValues(6.dp),colors=if(isStop)ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError) else ButtonDefaults.filledTonalButtonColors()){Column(horizontalAlignment=Alignment.CenterHorizontally){if(icon!=null)Icon(icon,null);Text(text,fontSize=if(icon==null)20.sp else 12.sp,fontWeight=if(icon==null)FontWeight.Bold else FontWeight.Normal)}}}
@Composable private fun DayHeader(day:LocalDate,prev:()->Unit,next:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){IconButton(prev){Icon(Icons.Default.ChevronLeft,"Предыдущий день")};Text(if(day==LocalDate.now())"Сегодня" else day.format(DateTimeFormatter.ofPattern("d MMMM")),fontWeight=FontWeight.Bold,fontSize=19.sp);IconButton(next,enabled=day<LocalDate.now()){Icon(Icons.Default.ChevronRight,"Следующий день")}}}
@Composable private fun Stats(events:List<BabyEvent>,segments:List<SleepSegment>,now:Long,showTitle:Boolean=true){
    fun feed(kind:FeedingKind)=events.filter{it.type==EventType.FEEDING&&it.detail==kind.name}.sumOf{(it.endedAt?:now)-it.startedAt}
    fun side(p:SleepPosition)=events.count{it.type==EventType.SLEEP&&it.detail==p.name}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){if(showTitle)Text("За день",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniStat("L","Грудь",durationCompact(feed(FeedingKind.LEFT)),Modifier.weight(1f));MiniStat("R","Грудь",durationCompact(feed(FeedingKind.RIGHT)),Modifier.weight(1f))};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){SleepStat(SleepPosition.LEFT,side(SleepPosition.LEFT),Modifier.weight(1f));SleepStat(SleepPosition.RIGHT,side(SleepPosition.RIGHT),Modifier.weight(1f))}}
}
@Composable private fun MiniStat(side:String,label:String,value:String,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(30.dp),CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Text(side,fontWeight=FontWeight.Bold)}};Spacer(Modifier.width(8.dp));Column{Text(value,fontWeight=FontWeight.Bold,fontSize=17.sp,maxLines=1);Text(label,fontSize=12.sp)}}}}
@Composable private fun SleepStat(position:SleepPosition,count:Int,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){SleepSideImage(position,Modifier.size(36.dp));Spacer(Modifier.width(8.dp));Column{Text("$count раз",fontWeight=FontWeight.Bold,fontSize=17.sp);Text("Сон",fontSize=12.sp)}}}}
@Composable private fun SleepSideImage(position:SleepPosition,modifier:Modifier){Image(painterResource(if(position==SleepPosition.LEFT)R.drawable.duck_sleep_left else R.drawable.duck_sleep_right),if(position==SleepPosition.LEFT)"Левый бок" else "Правый бок",modifier)}
@Composable private fun EventMarker(e:BabyEvent,modifier:Modifier=Modifier){if(e.type==EventType.SLEEP){SleepSideImage(runCatching{SleepPosition.valueOf(e.detail)}.getOrDefault(SleepPosition.LEFT),modifier);return};Surface(modifier,shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){when(e.type){EventType.PUMPING->Icon(Icons.Default.WaterDrop,"Сцеживание",Modifier.size(22.dp));else->when(e.detail){"LEFT"->Text("L",fontWeight=FontWeight.Bold,fontSize=17.sp);"RIGHT"->Text("R",fontWeight=FontWeight.Bold,fontSize=17.sp);else->Icon(Icons.Default.LocalDrink,"Бутылочка",Modifier.size(22.dp))}}}}}
@Composable private fun EventRow(e:BabyEvent,now:Long,edit:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){EventMarker(e,Modifier.size(38.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(eventTitle(e)+(e.authorName?.let{" · $it"}?:""),fontWeight=FontWeight.SemiBold);Text(if(e.type==EventType.SLEEP)clock(e.startedAt) else if(e.type==EventType.PUMPING)clock(e.endedAt?:e.startedAt) else "${clock(e.startedAt)} – ${e.endedAt?.let(::clock)?:"сейчас"} · ${if(e.endedAt==null)durationLive(now-e.startedAt) else durationCompact(e.endedAt-e.startedAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(edit){Icon(Icons.Default.Edit,"Изменить")}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditDialog(e:BabyEvent,onDismiss:()->Unit,onSave:(BabyEvent)->Unit,onDelete:()->Unit){
    var start by remember { mutableLongStateOf(e.startedAt) }; var end by remember { mutableStateOf(e.endedAt) }; var detail by remember { mutableStateOf(e.detail) }
    var picking by remember { mutableStateOf<String?>(null) }
    val options=if(e.type==EventType.FEEDING) FeedingKind.entries.map{it.name to feedName(it.name)} else listOf(SleepPosition.LEFT,SleepPosition.RIGHT).map{it.name to posName(it)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить событие")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Время",fontWeight=FontWeight.SemiBold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={picking="start"},modifier=Modifier.weight(1f)){Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(5.dp));Text(if(e.type==EventType.SLEEP)clock(start) else "Начало ${clock(start)}")};if(e.type==EventType.FEEDING)OutlinedButton(onClick={picking="end"},modifier=Modifier.weight(1f)){Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(5.dp));Text("Конец ${end?.let(::clock)?:"сейчас"}")}}
        Text("Тип / положение",fontWeight=FontWeight.SemiBold);options.forEach{(key,label)->FilterChip(selected=detail==key,onClick={detail=key},label={if(e.type==EventType.SLEEP)SleepSideImage(SleepPosition.valueOf(key),Modifier.size(40.dp)) else Text(label)})}
    }},confirmButton={TextButton(onClick={onSave(e.copy(startedAt=start,endedAt=if(e.type==EventType.SLEEP)start else end?.coerceAtLeast(start),detail=detail))}){Text("Сохранить")}},dismissButton={Row{TextButton(onClick=onDelete){Text("Удалить",color=MaterialTheme.colorScheme.error)};TextButton(onClick=onDismiss){Text("Отмена")}}})
    picking?.let{target->val base=if(target=="start")start else end?:System.currentTimeMillis();val cal=remember(target){java.util.Calendar.getInstance().apply{timeInMillis=base}};val picker=rememberTimePickerState(cal.get(java.util.Calendar.HOUR_OF_DAY),cal.get(java.util.Calendar.MINUTE),true);AlertDialog(onDismissRequest={picking=null},title={Text(if(target=="start")"Время начала" else "Время окончания")},text={TimePicker(picker)},confirmButton={TextButton(onClick={val chosen=withTime(base,picker.hour,picker.minute);if(target=="start")start=chosen else end=chosen;picking=null}){Text("Готово")}},dismissButton={TextButton(onClick={picking=null}){Text("Отмена")}})}
}
private fun durationCompact(ms:Long):String {val totalMinutes=ms.coerceAtLeast(0)/60000;val h=totalMinutes/60;val m=totalMinutes%60;return if(h>0)"$h ч ${m.toString().padStart(2,'0')} мин" else "$m мин"}
private fun durationLive(ms:Long):String {val total=ms.coerceAtLeast(0)/1000;val h=total/3600;val m=(total%3600)/60;val s=total%60;return if(h>0)"$h:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}" else "$m:${s.toString().padStart(2,'0')}"}
private fun agoCompact(ms:Long)="${durationCompact(ms)} назад"
private fun clock(ms:Long)=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(ms))
private fun withTime(epoch:Long,hour:Int,minute:Int)=java.util.Calendar.getInstance().apply{timeInMillis=epoch;set(java.util.Calendar.HOUR_OF_DAY,hour);set(java.util.Calendar.MINUTE,minute);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)}.timeInMillis
private fun feedName(s:String)=when(s){"LEFT"->"L";"RIGHT"->"R";else->"бутылочка"}
private fun eventTitle(e:BabyEvent)=when(e.type){EventType.SLEEP->"Сон";EventType.FEEDING->"Кормление · ${feedName(e.detail)}";EventType.PUMPING->{val (side,volume)=e.detail.split(":",limit=2).let{it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty()};"Сцеживание · ${if(side=="LEFT")"левая" else "правая"} · $volume мл"}}
private fun posName(p:SleepPosition)=when(p){SleepPosition.LEFT->"Голова слева";SleepPosition.RIGHT->"Голова справа";SleepPosition.BACK, SleepPosition.OTHER->"Другое"}
private fun shortPos(p:SleepPosition)=when(p){SleepPosition.LEFT->"Лево";SleepPosition.RIGHT->"Право";SleepPosition.BACK, SleepPosition.OTHER->"Другое"}
