package com.mark.babylog.widget

import android.content.Context
import android.content.Intent
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
import com.mark.babylog.BabyLogApp
import com.mark.babylog.MainActivity
import com.mark.babylog.R
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync

data class WidgetButton(val label:String,val command:String,val imageRes:Int?=null)
data class WidgetUi(val title:String,val status:String,val timerStartedAt:Long?=null,val buttons:List<WidgetButton>)

fun feedingWidgetUi(active:BabyEvent?,last:BabyEvent?,now:Long):WidgetUi {
    val running=active?.takeIf{it.type==EventType.FEEDING&&feedingKindOf(it.detail)!=FeedingKind.BOTTLE}
    val buttons=listOf(FeedingKind.LEFT to "L",FeedingKind.RIGHT to "R",FeedingKind.BOTTLE to "Бутылочка").map{(kind,label)->val isRunning=kind!=FeedingKind.BOTTLE&&running?.detail==kind.name;WidgetButton(if(isRunning)"■ Стоп" else label,if(isRunning)"STOP" else kind.name)}
    return WidgetUi("Кормление",when{running!=null->"Идёт · ${feedLabel(running.detail)}";last!=null->"${feedLabel(last.detail)} · ${elapsed(now-(last.endedAt?:last.startedAt))} назад";else->"Записей пока нет"},running?.startedAt,buttons)
}

fun sleepWidgetUi(last:BabyEvent?,now:Long):WidgetUi {
    val buttons=listOf(WidgetButton("","LEFT",R.drawable.duck_sleep_left),WidgetButton("","RIGHT",R.drawable.duck_sleep_right))
    return WidgetUi("Сон",if(last!=null)"Отмечено · ${elapsed(now-last.startedAt)} назад" else "Записей пока нет",buttons=buttons)
}

private val kindKey=ActionParameters.Key<String>("kind")
private val surfaceKey=ActionParameters.Key<String>("surface")
private fun dao(context:Context)=(context.applicationContext as BabyLogApp).database.events()

class FeedAction:ActionCallback { override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){val command=parameters[kindKey]?:"BOTTLE";if(command=="BOTTLE"){context.startActivity(MainActivity.bottleIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP));return};val app=context.applicationContext as BabyLogApp;if(command=="STOP")app.repository.stop() else app.repository.startFeeding(FeedingKind.valueOf(command));app.familySync.schedule();val widget=when(parameters[surfaceKey]){"horizontal"->FeedingHorizontalWidget();"mini"->FeedingMiniWidget();else->FeedingWidget()};AppSurfaceSync.refreshFromWidget(context,glanceId,widget)} }
class SleepAction:ActionCallback { override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){val app=context.applicationContext as BabyLogApp;val command=parameters[kindKey]?:"LEFT";app.repository.startSleep(SleepPosition.valueOf(command));app.familySync.schedule();AppSurfaceSync.refreshFromWidget(context,glanceId,SleepWidget())} }

class FeedingWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val d=dao(context);val now=System.currentTimeMillis();val ui=feedingWidgetUi(d.active(),d.lastFeed(),now);provideContent{StandardWidgetBox(context,ui,now,FeedAction::class.java,"feeding")}}}
class FeedingWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=FeedingWidget()}
class SleepWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val d=dao(context);val now=System.currentTimeMillis();val ui=sleepWidgetUi(d.lastSleep(),now);provideContent{StandardWidgetBox(context,ui,now,SleepAction::class.java,"sleep")}}}
class SleepWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=SleepWidget()}
class FeedingHorizontalWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val d=dao(context);val now=System.currentTimeMillis();val ui=feedingWidgetUi(d.active(),d.lastFeed(),now);provideContent{HorizontalWidgetBox(context,ui,now,FeedAction::class.java)}}}
class FeedingHorizontalWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=FeedingHorizontalWidget()}
class FeedingMiniWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val d=dao(context);val active=d.active()?.takeIf{it.type==EventType.FEEDING&&feedingKindOf(it.detail)!=FeedingKind.BOTTLE};val buttons=listOf("LEFT" to "L","RIGHT" to "R","BOTTLE" to "🍼").map{(command,label)->val isRunning=command!="BOTTLE"&&active?.detail==command;WidgetButton(if(isRunning)"■" else label,if(isRunning)"STOP" else command)};provideContent{MiniWidgetBox(buttons,FeedAction::class.java)}}}
class FeedingMiniWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=FeedingMiniWidget()}

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable private fun StandardWidgetBox(context:Context,ui:WidgetUi,now:Long,action:Class<out ActionCallback>,surface:String){Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF4EFF7))).clickable(actionStartActivity<MainActivity>()).padding(12.dp)){WidgetHeader(context,ui,now);Row(GlanceModifier.fillMaxWidth()){WidgetButton(ui.buttons[0],action,GlanceModifier.defaultWeight(),surface);Spacer(GlanceModifier.width(8.dp));WidgetButton(ui.buttons[1],action,GlanceModifier.defaultWeight(),surface)};if(ui.buttons.size>2){Spacer(GlanceModifier.height(7.dp));WidgetButton(ui.buttons[2],action,GlanceModifier.fillMaxWidth(),surface)}}}
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable private fun HorizontalWidgetBox(context:Context,ui:WidgetUi,now:Long,action:Class<out ActionCallback>){Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF4EFF7))).clickable(actionStartActivity<MainActivity>()).padding(12.dp)){WidgetHeader(context,ui,now);Row(GlanceModifier.fillMaxWidth()){ui.buttons.forEachIndexed{index,item->if(index>0)Spacer(GlanceModifier.width(7.dp));WidgetButton(item,action,GlanceModifier.defaultWeight(),"horizontal")}}}}
@Composable private fun MiniWidgetBox(buttons:List<WidgetButton>,action:Class<out ActionCallback>){Row(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF4EFF7))).clickable(actionStartActivity<MainActivity>()).padding(8.dp),verticalAlignment=Alignment.CenterVertically){buttons.forEachIndexed{index,item->if(index>0)Spacer(GlanceModifier.width(6.dp));WidgetButton(item,action,GlanceModifier.defaultWeight(),"mini")}}}
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable private fun WidgetHeader(context:Context,ui:WidgetUi,now:Long){Text(ui.title,style=TextStyle(fontWeight=FontWeight.Bold,fontSize=18.sp));Text(ui.status,style=TextStyle(fontSize=13.sp));ui.timerStartedAt?.let{started->val remote=RemoteViews(context.packageName,R.layout.widget_chronometer).apply{setChronometer(R.id.widget_chronometer,SystemClock.elapsedRealtime()-(now-started),null,true)};AndroidRemoteViews(remote,GlanceModifier.fillMaxWidth().height(28.dp))}?:Spacer(GlanceModifier.height(6.dp))}
@Composable private fun WidgetButton(item:WidgetButton,action:Class<out ActionCallback>,modifier:GlanceModifier,surface:String){val background=if(item.command=="STOP")Color(0xFFB3261E) else Color(0xFF6F579C);Box(modifier.height(42.dp).background(ColorProvider(background)).cornerRadius(16.dp).clickable(actionRunCallback(action,actionParametersOf(kindKey to item.command,surfaceKey to surface))),contentAlignment=Alignment.Center){if(item.imageRes!=null)Image(ImageProvider(item.imageRes),item.label.ifBlank{if(item.command=="LEFT")"Левый бок" else "Правый бок"},GlanceModifier.size(38.dp))else Text(item.label,style=TextStyle(color=ColorProvider(Color.White),fontWeight=FontWeight.Bold,fontSize=14.sp,textAlign=TextAlign.Center),maxLines=1)}}
private fun feedLabel(v:String)=when(v){"LEFT"->"L";"RIGHT"->"R";else->"бутылочка"}
private fun sleepLabel(v:String)=when(v){"LEFT"->"голова слева";"RIGHT"->"голова справа";else->"другое"}
private fun elapsed(ms:Long):String{val total=ms.coerceAtLeast(0)/1000;val h=total/3600;val m=(total%3600)/60;val s=total%60;return if(h>0)"$h:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}" else "$m:${s.toString().padStart(2,'0')}"}
