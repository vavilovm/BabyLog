package com.mark.babylog.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.room.Room
import com.mark.babylog.MainActivity
import com.mark.babylog.R
import com.mark.babylog.data.*

data class WidgetButton(val label:String,val command:String)
data class WidgetUi(val title:String,val status:String,val timerStartedAt:Long?=null,val buttons:List<WidgetButton>)

fun feedingWidgetUi(active:BabyEvent?,last:BabyEvent?,now:Long):WidgetUi {
    val running=active?.takeIf{it.type==EventType.FEEDING}
    val buttons=listOf(FeedingKind.LEFT to "Левая",FeedingKind.RIGHT to "Правая",FeedingKind.BOTTLE to "Бутылочка").map{(kind,label)->WidgetButton(if(running?.detail==kind.name)"■ Стоп" else label,if(running?.detail==kind.name)"STOP" else kind.name)}
    return WidgetUi("Кормление",when{running!=null->"Идёт · ${feedLabel(running.detail)}";last!=null->"${feedLabel(last.detail)} · ${elapsed(now-last.startedAt)} назад";else->"Записей пока нет"},running?.startedAt,buttons)
}

fun sleepWidgetUi(active:BabyEvent?,now:Long):WidgetUi {
    val running=active?.takeIf{it.type==EventType.SLEEP}
    val buttons=listOf(SleepPosition.BACK to "На спине",SleepPosition.LEFT to "Левый бок",SleepPosition.RIGHT to "Правый бок").map{(position,label)->WidgetButton(if(running?.detail==position.name)"■ Стоп" else label,if(running?.detail==position.name)"STOP" else position.name)}
    return WidgetUi("Сон",if(running!=null)"Спит · ${sleepLabel(running.detail)}" else "Сон не идёт",running?.startedAt,buttons)
}

private val kindKey=ActionParameters.Key<String>("kind")
private suspend fun dao(context:Context)=Room.databaseBuilder(context,BabyDatabase::class.java,"baby-log.db").build().events()
private suspend fun updateWidgets(context:Context){FeedingWidget().updateAll(context);SleepWidget().updateAll(context)}

class FeedAction:ActionCallback { override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){val d=dao(context);val command=parameters[kindKey]?:"BOTTLE";if(command=="STOP"){if(d.active()?.type==EventType.FEEDING)d.stopActive(System.currentTimeMillis())}else d.startFeeding(FeedingKind.valueOf(command),System.currentTimeMillis());updateWidgets(context)} }
class SleepAction:ActionCallback { override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){val d=dao(context);val command=parameters[kindKey]?:"BACK";if(command=="STOP"){if(d.active()?.type==EventType.SLEEP)d.stopActive(System.currentTimeMillis())}else d.startSleep(SleepPosition.valueOf(command),System.currentTimeMillis());updateWidgets(context)} }

class FeedingWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val d=dao(context);val now=System.currentTimeMillis();val ui=feedingWidgetUi(d.active(),d.lastFeed(),now);provideContent{WidgetBox(context,ui,now,FeedAction::class.java)}}}
class FeedingWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=FeedingWidget()}
class SleepWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val d=dao(context);val now=System.currentTimeMillis();val ui=sleepWidgetUi(d.active(),now);provideContent{WidgetBox(context,ui,now,SleepAction::class.java)}}}
class SleepWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=SleepWidget()}

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable private fun WidgetBox(context:Context,ui:WidgetUi,now:Long,action:Class<out ActionCallback>){Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF4EFF7))).clickable(actionStartActivity<MainActivity>()).padding(16.dp)){Text(ui.title,style=TextStyle(fontWeight=FontWeight.Bold,fontSize=20.sp));Text(ui.status,style=TextStyle(fontSize=14.sp));ui.timerStartedAt?.let{started->val remote=RemoteViews(context.packageName,R.layout.widget_chronometer).apply{setChronometer(R.id.widget_chronometer,SystemClock.elapsedRealtime()-(now-started),null,true)};AndroidRemoteViews(remote,GlanceModifier.fillMaxWidth().height(32.dp))}?:Spacer(GlanceModifier.height(8.dp));ui.buttons.forEachIndexed{index,item->if(index>0)Spacer(GlanceModifier.height(7.dp));WidgetButton(item,action,GlanceModifier.fillMaxWidth())}}}
@Composable private fun WidgetButton(item:WidgetButton,action:Class<out ActionCallback>,modifier:GlanceModifier){Button(item.label,onClick=actionRunCallback(action,actionParametersOf(kindKey to item.command)),modifier=modifier)}
private fun feedLabel(v:String)=when(v){"LEFT"->"левая грудь";"RIGHT"->"правая грудь";else->"бутылочка"}
private fun sleepLabel(v:String)=when(v){"LEFT"->"левый бок";"RIGHT"->"правый бок";"BACK"->"на спине";else->"другое"}
private fun elapsed(ms:Long):String{val total=ms.coerceAtLeast(0)/1000;val h=total/3600;val m=(total%3600)/60;val s=total%60;return if(h>0)"$h:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}" else "$m:${s.toString().padStart(2,'0')}"}
