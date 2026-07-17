package com.mark.babylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mark.babylog.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val FEEDING_SESSION_GAP_MS=30*60_000L

internal data class DailySummary(
    val feedingCount:Int,
    val averageFeedingIntervalMs:Long?,
    val longestFeedingIntervalMs:Long?,
    val averageFeedingDurationMs:Long?,
    val totalBreastfeedingMs:Long,
    val leftBreastfeedingMs:Long,
    val rightBreastfeedingMs:Long,
    val firstFeedingAt:Long?,
    val lastFeedingAt:Long?,
    val bottleCount:Int,
    val bottleVolumeMl:Int,
    val sleepLeftCount:Int,
    val sleepRightCount:Int,
    val pumpingCount:Int,
    val pumpingVolumeMl:Int,
    val eventCount:Int
)

private data class FeedingSession(var startedAt:Long,var endedAt:Long,var measuredDurationMs:Long)

internal fun dailySummary(events:List<BabyEvent>,day:LocalDate,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):DailySummary{
    val dayStart=day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val feedings=events.filter{it.type==EventType.FEEDING}.sortedBy{it.startedAt}
    val sessions=mutableListOf<FeedingSession>()
    var leftMs=0L
    var rightMs=0L
    feedings.forEach{event->
        val start=event.startedAt.coerceIn(dayStart,dayEnd)
        val end=(event.endedAt?:now).coerceIn(start,dayEnd)
        val measured=if(feedingKindOf(event.detail)==FeedingKind.BOTTLE)0 else end-start
        when(feedingKindOf(event.detail)){
            FeedingKind.LEFT->leftMs+=measured
            FeedingKind.RIGHT->rightMs+=measured
            FeedingKind.BOTTLE->Unit
        }
        val current=sessions.lastOrNull()
        if(current!=null&&start-current.endedAt<=FEEDING_SESSION_GAP_MS){
            current.endedAt=maxOf(current.endedAt,end)
            current.measuredDurationMs+=measured
        }else sessions+=FeedingSession(start,end,measured)
    }
    val intervals=sessions.zipWithNext{a,b->b.startedAt-a.startedAt}.filter{it>=0}
    val measuredSessions=sessions.map{it.measuredDurationMs}.filter{it>0}
    val bottles=feedings.mapNotNull{bottleVolumeMl(it.detail)}
    val sleeps=events.filter{it.type==EventType.SLEEP}.mapNotNull{runCatching{SleepPosition.valueOf(it.detail)}.getOrNull()}
    val pumping=events.filter{it.type==EventType.PUMPING}.mapNotNull{it.detail.substringAfter(':',"").toIntOrNull()}
    return DailySummary(
        feedingCount=sessions.size,
        averageFeedingIntervalMs=intervals.takeIf{it.isNotEmpty()}?.average()?.roundToInt()?.toLong(),
        longestFeedingIntervalMs=intervals.maxOrNull(),
        averageFeedingDurationMs=measuredSessions.takeIf{it.isNotEmpty()}?.average()?.roundToInt()?.toLong(),
        totalBreastfeedingMs=leftMs+rightMs,
        leftBreastfeedingMs=leftMs,
        rightBreastfeedingMs=rightMs,
        firstFeedingAt=sessions.firstOrNull()?.startedAt,
        lastFeedingAt=sessions.lastOrNull()?.startedAt,
        bottleCount=bottles.size,
        bottleVolumeMl=bottles.sum(),
        sleepLeftCount=sleeps.count{it==SleepPosition.LEFT},
        sleepRightCount=sleeps.count{it==SleepPosition.RIGHT},
        pumpingCount=pumping.size,
        pumpingVolumeMl=pumping.sum(),
        eventCount=events.size
    )
}

@Composable internal fun DailyStatisticsDialog(state:DailyStatisticsState,now:Long,onDismiss:()->Unit,onDayChange:(LocalDate)->Unit){
    val today=LocalDate.now()
    val summary=dailySummary(state.events,state.day,now)
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(
            Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),
            shape=RoundedCornerShape(28.dp),
            color=MaterialTheme.colorScheme.surface,
            tonalElevation=6.dp
        ){
            Column{
                Row(Modifier.fillMaxWidth().padding(20.dp,12.dp,8.dp,4.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Статистика",fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    IconButton(onClick=onDismiss){Icon(Icons.Default.Close,"Закрыть")}
                }
                DayPicker(state.day,today,onDayChange)
                if(state.loading){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp,10.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(summary.eventCount==0)EmptyStatistics()
                    else{
                        FeedingStatistics(summary)
                        if(summary.sleepLeftCount+summary.sleepRightCount>0)SleepStatistics(summary)
                        if(summary.pumpingCount>0)PumpingStatistics(summary)
                    }
                }
            }
        }
    }
}

@Composable private fun DayPicker(day:LocalDate,today:LocalDate,onDayChange:(LocalDate)->Unit){
    val relative=when(day){today->"Сегодня";today.minusDays(1)->"Вчера";else->null}
    val formatted=day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM",Locale("ru"))).replaceFirstChar{it.titlecase(Locale("ru"))}
    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick={onDayChange(day.minusDays(1))}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Предыдущий день")}
        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
            relative?.let{Text(it,fontWeight=FontWeight.Bold,fontSize=18.sp)}
            Text(formatted,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick={onDayChange(day.plusDays(1))},enabled=day<today){Icon(Icons.AutoMirrored.Filled.ArrowForward,"Следующий день")}
    }
}

@Composable private fun EmptyStatistics(){
    Column(Modifier.fillMaxWidth().padding(vertical=54.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
        Surface(Modifier.size(72.dp),CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Insights,null,Modifier.size(36.dp))}}
        Text("За этот день записей нет",fontWeight=FontWeight.Bold,fontSize=18.sp)
        Text("Перейдите на другой день стрелками выше",color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
    }
}

@Composable private fun FeedingStatistics(summary:DailySummary){
    StatisticSection(icon={Icon(Icons.Default.Restaurant,null)},title="Кормления"){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Metric("${summary.feedingCount}",plural(summary.feedingCount,"кормление","кормления","кормлений"),Modifier.weight(1f))
            Metric(summary.averageFeedingIntervalMs?.let{durationShort(it)}?:"—","в среднем каждые",Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Metric(summary.averageFeedingDurationMs?.let{durationShort(it)}?:"—","в среднем у груди",Modifier.weight(1f))
            Metric(durationShort(summary.totalBreastfeedingMs),"всего у груди",Modifier.weight(1f))
        }
        if(summary.totalBreastfeedingMs>0)BreastBalance(summary)
        if(summary.bottleCount>0){
            HorizontalDivider()
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.LocalDrink,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text("Из бутылочки",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text("${summary.bottleVolumeMl} мл · ${summary.bottleCount} ${plural(summary.bottleCount,"раз","раза","раз")}",fontWeight=FontWeight.Bold)}
        }
        if(summary.firstFeedingAt!=null){
            HorizontalDivider()
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Schedule,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text("Ритм дня",fontWeight=FontWeight.SemiBold);Text("Первое ${statClock(summary.firstFeedingAt)} · последнее ${statClock(summary.lastFeedingAt!!)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};summary.longestFeedingIntervalMs?.let{Column(horizontalAlignment=Alignment.End){Text(durationShort(it),fontWeight=FontWeight.Bold);Text("макс. пауза",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        }
        Text("Смена груди в течение 30 минут считается одним кормлением.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BreastBalance(summary:DailySummary){
    val leftShare=summary.leftBreastfeedingMs.toFloat()/summary.totalBreastfeedingMs
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row{Text("Баланс груди",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text("L ${durationShort(summary.leftBreastfeedingMs)}  ·  R ${durationShort(summary.rightBreastfeedingMs)}",style=MaterialTheme.typography.bodySmall)}
        LinearProgressIndicator(progress={leftShare},modifier=Modifier.fillMaxWidth().height(8.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.secondaryContainer)
    }
}

@Composable private fun SleepStatistics(summary:DailySummary){
    val total=summary.sleepLeftCount+summary.sleepRightCount
    val favorite=when{summary.sleepLeftCount>summary.sleepRightCount->"Чаще на левом боку";summary.sleepRightCount>summary.sleepLeftCount->"Чаще на правом боку";else->"Поровну на обоих боках"}
    StatisticSection(icon={SleepSideImage(if(summary.sleepLeftCount>=summary.sleepRightCount)SleepPosition.LEFT else SleepPosition.RIGHT,Modifier.size(34.dp))},title="Положение во сне"){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            SideMetric(SleepPosition.LEFT,summary.sleepLeftCount,Modifier.weight(1f))
            SideMetric(SleepPosition.RIGHT,summary.sleepRightCount,Modifier.weight(1f))
        }
        Text("$favorite · всего $total ${plural(total,"отметка","отметки","отметок")}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PumpingStatistics(summary:DailySummary){
    StatisticSection(icon={Icon(Icons.Default.WaterDrop,null)},title="Сцеживание"){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Metric("${summary.pumpingVolumeMl} мл","всего",Modifier.weight(1f))
            Metric("${summary.pumpingVolumeMl/summary.pumpingCount} мл","в среднем",Modifier.weight(1f))
            Metric("${summary.pumpingCount}",plural(summary.pumpingCount,"раз","раза","раз"),Modifier.weight(1f))
        }
    }
}

@Composable private fun StatisticSection(icon:@Composable ()->Unit,title:String,content:@Composable ColumnScope.()->Unit){
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)),shape=RoundedCornerShape(20.dp)){
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(40.dp),CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){icon()}};Spacer(Modifier.width(10.dp));Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold)}
            content()
        }
    }
}

@Composable private fun Metric(value:String,label:String,modifier:Modifier){
    Surface(modifier.heightIn(min=72.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.Center){Text(value,fontSize=19.sp,fontWeight=FontWeight.Bold,maxLines=1);Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}}
}

@Composable private fun SideMetric(position:SleepPosition,count:Int,modifier:Modifier){
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){SleepSideImage(position,Modifier.size(42.dp));Spacer(Modifier.width(8.dp));Column{Text("$count",fontSize=20.sp,fontWeight=FontWeight.Bold);Text(if(position==SleepPosition.LEFT)"левый бок" else "правый бок",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
}

private fun durationShort(ms:Long):String{
    val minutes=(ms.coerceAtLeast(0)/60_000).toInt()
    val hours=minutes/60
    return when{hours>0&&minutes%60>0->"$hours ч ${minutes%60} мин";hours>0->"$hours ч";else->"$minutes мин"}
}
private fun statClock(ms:Long)=java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(java.util.Date(ms))
private fun plural(value:Int,one:String,few:String,many:String):String{val n=value%100;return when{n in 11..14->many;value%10==1->one;value%10 in 2..4->few;else->many}}
