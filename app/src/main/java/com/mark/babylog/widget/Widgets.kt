package com.mark.babylog.widget

import android.content.Context
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.compose.runtime.Composable
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.mark.babylog.data.*
import com.mark.babylog.MainActivity

private val kindKey=ActionParameters.Key<String>("kind")
private suspend fun dao(context:Context)=Room.databaseBuilder(context,BabyDatabase::class.java,"baby-log.db").build().events()

class FeedAction:ActionCallback { override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){dao(context).startFeeding(FeedingKind.valueOf(parameters[kindKey]?:"BOTTLE"),System.currentTimeMillis());FeedingWidget().updateAll(context)} }
class SleepAction:ActionCallback { override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){val d=dao(context);val now=System.currentTimeMillis();val command=parameters[kindKey]?:"BACK";if(command=="STOP")d.stopActive(now) else d.startSleep(SleepPosition.valueOf(command),now);SleepWidget().updateAll(context)} }

class FeedingWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val last=dao(context).lastFeed();provideContent{WidgetBox("Кормление",last?.let{"${label(it.detail)} · ${elapsed(System.currentTimeMillis()-it.startedAt)} назад"}?:"Записей нет",listOf("Левая" to "LEFT","Правая" to "RIGHT","Бутылка" to "BOTTLE"),FeedAction::class.java)}}}
class FeedingWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=FeedingWidget()}
class SleepWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){val active=dao(context).active();provideContent{WidgetBox("Сон",if(active?.type==EventType.SLEEP)"Спит ${elapsed(System.currentTimeMillis()-active.startedAt)}" else "Не спит",listOf(if(active?.type==EventType.SLEEP)"Закончить" to "STOP" else "Начать" to "BACK","Левый бок" to "LEFT","Правый бок" to "RIGHT"),SleepAction::class.java)}}}
class SleepWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=SleepWidget()}

@Composable private fun WidgetBox(title:String,status:String,buttons:List<Pair<String,String>>,action:Class<out ActionCallback>){Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFFF4EFF7))).clickable(actionStartActivity<MainActivity>()).padding(16.dp)){Text(title,style=TextStyle(fontWeight=FontWeight.Bold,fontSize=20.sp));Text(status,style=TextStyle(fontSize=14.sp));Spacer(GlanceModifier.height(10.dp));Row(GlanceModifier.fillMaxWidth()){WidgetButton(buttons[0],action,GlanceModifier.defaultWeight());Spacer(GlanceModifier.width(8.dp));WidgetButton(buttons[1],action,GlanceModifier.defaultWeight())};Spacer(GlanceModifier.height(8.dp));WidgetButton(buttons[2],action,GlanceModifier.fillMaxWidth())}}
@Composable private fun WidgetButton(item:Pair<String,String>,action:Class<out ActionCallback>,modifier:GlanceModifier){Button(item.first,onClick=actionRunCallback(action,actionParametersOf(kindKey to item.second)),modifier=modifier)}
private fun label(v:String)=when(v){"LEFT"->"левая";"RIGHT"->"правая";else->"бутылочка"}
private fun elapsed(ms:Long):String{val m=ms.coerceAtLeast(0)/60000;return if(m<60)"$m мин" else "${m/60} ч ${m%60} мин"}
