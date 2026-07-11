package com.mark.babylog.sync

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mark.babylog.BabyLogApp
import com.mark.babylog.MainActivity
import com.mark.babylog.R
import com.mark.babylog.data.*
import com.mark.babylog.widget.FeedingWidget
import com.mark.babylog.widget.SleepWidget
import com.mark.babylog.widget.FeedingHorizontalWidget
import com.mark.babylog.widget.FeedingMiniWidget
import com.mark.babylog.widget.FeedingWidgetReceiver
import com.mark.babylog.widget.SleepWidgetReceiver
import com.mark.babylog.widget.FeedingHorizontalWidgetReceiver
import com.mark.babylog.widget.FeedingMiniWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.runComposition
import androidx.glance.GlanceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*

object AppSurfaceSync {
    private const val CHANNEL="active_timers"
    private const val NOTIFICATION_ID=42

    suspend fun refresh(context:Context){
        refreshWidgets(context)
        val active=(context.applicationContext as BabyLogApp).database.events().active()
        refreshNotification(context,active)
    }

    suspend fun refreshFromWidget(context:Context,id:GlanceId,widget:GlanceAppWidget){
        pushImmediately(context,widget,id)
        coroutineScope {
            launch { refreshWidgets(context) }
            launch {
                val active=(context.applicationContext as BabyLogApp).database.events().active()
                refreshNotification(context,active)
            }
        }
    }

    private suspend fun refreshWidgets(context:Context)=coroutineScope {
        val manager=AppWidgetManager.getInstance(context)
        val targets=listOf(
            FeedingWidget() to FeedingWidgetReceiver::class.java,
            SleepWidget() to SleepWidgetReceiver::class.java,
            FeedingHorizontalWidget() to FeedingHorizontalWidgetReceiver::class.java,
            FeedingMiniWidget() to FeedingMiniWidgetReceiver::class.java
        )
        targets.flatMap { (widget,receiver) ->
            manager.getAppWidgetIds(ComponentName(context,receiver)).map { appWidgetId ->
                async { pushImmediately(context,widget,AppWidgetId(appWidgetId)) }
            }
        }.awaitAll()
    }

    @OptIn(androidx.glance.ExperimentalGlanceApi::class)
    private suspend fun pushImmediately(context:Context,widget:GlanceAppWidget,id:GlanceId){
        val views=widget.runComposition(context,id).first()
        AppWidgetManager.getInstance(context).updateAppWidget((id as AppWidgetId).appWidgetId,views)
    }

    private fun refreshNotification(context:Context,active:BabyEvent?){
        val manager=NotificationManagerCompat.from(context)
        if(active==null){manager.cancel(NOTIFICATION_ID);return}
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        if(Build.VERSION.SDK_INT>=26)(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"Активные таймеры",NotificationManager.IMPORTANCE_LOW).apply{description="Текущее кормление или сон"})
        val open=PendingIntent.getActivity(context,1,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=if(active.type==EventType.FEEDING)"Кормление · ${feedName(active.detail)}" else "Сон · ${sleepName(active.detail)}"
        val builder=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_timer).setContentTitle(title).setContentText("Управление таймером доступно прямо здесь").setContentIntent(open).setWhen(active.startedAt).setUsesChronometer(true).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_STOPWATCH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.addAction(0,"Стоп",action(context,"STOP",10))
        if(active.type==EventType.FEEDING){builder.addAction(0,"Левая",action(context,"FEED_LEFT",11));builder.addAction(0,"Правая",action(context,"FEED_RIGHT",12));builder.addAction(0,"Бутылочка",action(context,"FEED_BOTTLE",13))}
        else{builder.addAction(0,"Лево",action(context,"SLEEP_LEFT",14));builder.addAction(0,"Право",action(context,"SLEEP_RIGHT",15))}
        manager.notify(NOTIFICATION_ID,builder.build())
    }

    private fun action(context:Context,command:String,request:Int)=PendingIntent.getBroadcast(context,request,Intent(context,TimerActionReceiver::class.java).putExtra("command",command),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun feedName(v:String)=when(v){"LEFT"->"левая грудь";"RIGHT"->"правая грудь";else->"бутылочка"}
    private fun sleepName(v:String)=when(v){"LEFT"->"голова слева";"RIGHT"->"голова справа";else->"другое"}
}

class TimerActionReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val pending=goAsync();CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{val dao=(context.applicationContext as BabyLogApp).database.events();val now=System.currentTimeMillis();when(intent.getStringExtra("command")){"STOP"->dao.stopActive(now);"FEED_LEFT"->dao.startFeeding(FeedingKind.LEFT,now);"FEED_RIGHT"->dao.startFeeding(FeedingKind.RIGHT,now);"FEED_BOTTLE"->dao.startFeeding(FeedingKind.BOTTLE,now);"SLEEP_LEFT"->dao.startSleep(SleepPosition.LEFT,now);"SLEEP_RIGHT"->dao.startSleep(SleepPosition.RIGHT,now)};AppSurfaceSync.refresh(context)}finally{pending.finish()}}}}

class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_BOOT_COMPLETED){val pending=goAsync();CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{AppSurfaceSync.refresh(context)}finally{pending.finish()}}}}}
