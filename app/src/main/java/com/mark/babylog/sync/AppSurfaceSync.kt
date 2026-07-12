package com.mark.babylog.sync

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AppSurfaceSync {
    private const val ACTIVE_TIMER_CHANNEL="active_timers_visible"
    private const val FEEDING_START_CHANNEL="feeding_starts_low"
    private const val LEGACY_NOTIFICATION_ID=42
    private const val NOTIFICATION_ID=43
    private const val FEEDING_START_NOTIFICATION_ID=44
    private val refreshMutex=Mutex()

    suspend fun refresh(context:Context)=refreshMutex.withLock{
        runCatching{refreshWidgets(context)}
        val active=(context.applicationContext as BabyLogApp).database.events().active()
        val lastFeed=(context.applicationContext as BabyLogApp).database.events().lastFeed()
        runCatching{refreshNotification(context,active,lastFeed)}
    }

    suspend fun refreshFromWidget(context:Context,id:GlanceId,widget:GlanceAppWidget){
        runCatching{pushImmediately(context,widget,id)}
        refreshMutex.withLock {
            supervisorScope {
                launch { runCatching{refreshWidgets(context)} }
                launch {
                    val active=(context.applicationContext as BabyLogApp).database.events().active()
                    val lastFeed=(context.applicationContext as BabyLogApp).database.events().lastFeed()
                    runCatching{refreshNotification(context,active,lastFeed)}
                }
            }
        }
    }

    private suspend fun refreshWidgets(context:Context)=supervisorScope {
        val manager=AppWidgetManager.getInstance(context)
        val targets=listOf(
            FeedingWidget() to FeedingWidgetReceiver::class.java,
            SleepWidget() to SleepWidgetReceiver::class.java,
            FeedingHorizontalWidget() to FeedingHorizontalWidgetReceiver::class.java,
            FeedingMiniWidget() to FeedingMiniWidgetReceiver::class.java
        )
        targets.flatMap { (widget,receiver) ->
            manager.getAppWidgetIds(ComponentName(context,receiver)).map { appWidgetId ->
                async { runCatching{pushImmediately(context,widget,AppWidgetId(appWidgetId))} }
            }
        }.awaitAll()
    }

    @OptIn(androidx.glance.ExperimentalGlanceApi::class)
    private suspend fun pushImmediately(context:Context,widget:GlanceAppWidget,id:GlanceId){
        val views=widget.runComposition(context,id).first()
        AppWidgetManager.getInstance(context).updateAppWidget((id as AppWidgetId).appWidgetId,views)
    }

    private fun refreshNotification(context:Context,active:BabyEvent?,lastFeed:BabyEvent?){
        val manager=NotificationManagerCompat.from(context)
        manager.cancel(LEGACY_NOTIFICATION_ID)
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        if(active==null){
            manager.cancel(NOTIFICATION_ID)
            showFeedingStartNotification(context,manager,lastFeed)
            return
        }
        manager.cancel(FEEDING_START_NOTIFICATION_ID)
        createActiveTimerChannel(context)
        val open=PendingIntent.getActivity(context,1,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=if(active.type==EventType.FEEDING)"Кормление · ${feedName(active.detail)}" else "Сон · ${sleepName(active.detail)}"
        val compact=compactNotification(context,active)
        val builder=NotificationCompat.Builder(context,ACTIVE_TIMER_CHANNEL).setSmallIcon(R.drawable.ic_stat_timer).setContentTitle(title).setContentText("Управление таймером доступно прямо здесь").setContentIntent(open).setWhen(active.startedAt).setUsesChronometer(true).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).setCategory(NotificationCompat.CATEGORY_STOPWATCH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCustomContentView(compact).setStyle(NotificationCompat.DecoratedCustomViewStyle())
        notificationActions(active).forEach{builder.addAction(0,it.label,action(context,it.command,it.request))}
        manager.notify(NOTIFICATION_ID,builder.build())
    }

    private fun createActiveTimerChannel(context:Context){
        if(Build.VERSION.SDK_INT>=26)(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(ACTIVE_TIMER_CHANNEL,"Активные таймеры",NotificationManager.IMPORTANCE_DEFAULT).apply{description="Текущее кормление или сон";setSound(null,null);enableVibration(false);setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
    }

    private fun showFeedingStartNotification(context:Context,manager:NotificationManagerCompat,lastFeed:BabyEvent?){
        if(Build.VERSION.SDK_INT>=26)(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(FEEDING_START_CHANNEL,"Быстрый старт кормления",NotificationManager.IMPORTANCE_LOW).apply{description="Быстрый старт кормления с экрана блокировки";setSound(null,null);enableVibration(false);setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
        val open=PendingIntent.getActivity(context,2,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val actions=feedingStartActions()
        val summary=lastFeedSummary(lastFeed)
        val compact=feedingStartCompactNotification(context,actions,summary)
        val builder=NotificationCompat.Builder(context,FEEDING_START_CHANNEL).setSmallIcon(R.drawable.ic_stat_timer).setContentTitle("Начать кормление").setContentText(summary?:"Выберите способ кормления").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCustomContentView(compact).setStyle(NotificationCompat.DecoratedCustomViewStyle())
        actions.forEach{builder.addAction(0,it.label,action(context,it.command,it.request))}
        manager.notify(FEEDING_START_NOTIFICATION_ID,builder.build())
    }

    private data class CompactAction(val label:String,val command:String,val request:Int)

    private fun compactNotification(context:Context,active:BabyEvent)=RemoteViews(context.packageName,R.layout.notification_timer_compact).apply{
        val elapsed=System.currentTimeMillis()-active.startedAt
        setChronometer(R.id.notification_chronometer,SystemClock.elapsedRealtime()-elapsed,null,true)
        setString(R.id.notification_chronometer,"setFormat",if(active.type==EventType.FEEDING)"🍼 %s" else "🌙 %s")
        val actions=notificationActions(active)
        listOf(R.id.notification_action_1,R.id.notification_action_2,R.id.notification_action_3).forEachIndexed{index,id->
            val item=actions.getOrNull(index)
            setViewVisibility(id,if(item==null)View.GONE else View.VISIBLE)
            item?.let{setTextViewText(id,it.label);setInt(id,"setBackgroundResource",if(it.command=="STOP")R.drawable.notification_action_stop else R.drawable.notification_action);setOnClickPendingIntent(id,action(context,it.command,it.request))}
        }
    }

    private fun feedingStartCompactNotification(context:Context,actions:List<CompactAction>,summary:String?)=RemoteViews(context.packageName,R.layout.notification_feeding_start_compact).apply{
        setTextViewText(R.id.notification_feeding_summary,summary?:"🍼 Начать")
        actions.forEachIndexed{index,item->
            val id=listOf(R.id.notification_action_1,R.id.notification_action_2,R.id.notification_action_3)[index]
            setTextViewText(id,item.label)
            setOnClickPendingIntent(id,action(context,item.command,item.request))
        }
    }

    private fun notificationActions(active:BabyEvent):List<CompactAction>{
        fun item(detail:String,label:String,command:String,request:Int)=CompactAction(if(active.detail==detail)"Стоп" else label,if(active.detail==detail)"STOP" else command,request)
        return if(active.type==EventType.FEEDING)listOf(
            item("LEFT","L","FEED_LEFT",11),
            item("RIGHT","R","FEED_RIGHT",12),
            item("BOTTLE","Бутылочка","FEED_BOTTLE",13)
        )else listOf(
            item("LEFT","Лево","SLEEP_LEFT",14),
            item("RIGHT","Право","SLEEP_RIGHT",15)
        )
    }

    private fun feedingStartActions()=listOf(
        CompactAction("L","FEED_LEFT",21),
        CompactAction("R","FEED_RIGHT",22),
        CompactAction("Бутылочка","FEED_BOTTLE",23)
    )

    private fun lastFeedSummary(lastFeed:BabyEvent?,now:Long=System.currentTimeMillis()):String?=lastFeed?.let{
        "Последнее: ${feedName(it.detail)} · ${formatElapsed((now-it.startedAt).coerceAtLeast(0))} назад"
    }

    private fun formatElapsed(elapsed:Long):String{
        val minutes=elapsed/60_000
        return when{
            minutes<1->"только что"
            minutes<60->"$minutes мин"
            else->{val hours=minutes/60;val remainder=minutes%60;if(remainder==0L)"$hours ч" else "$hours ч $remainder мин"}
        }
    }

    private fun action(context:Context,command:String,request:Int)=PendingIntent.getBroadcast(context,request,Intent(context,TimerActionReceiver::class.java).putExtra("command",command),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun feedName(v:String)=when(v){"LEFT"->"L";"RIGHT"->"R";else->"бутылочка"}
    private fun sleepName(v:String)=when(v){"LEFT"->"голова слева";"RIGHT"->"голова справа";else->"другое"}
}

class TimerActionReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val pending=goAsync();CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{val app=context.applicationContext as BabyLogApp;when(intent.getStringExtra("command")){"STOP"->app.repository.stop();"FEED_LEFT"->app.repository.startFeeding(FeedingKind.LEFT);"FEED_RIGHT"->app.repository.startFeeding(FeedingKind.RIGHT);"FEED_BOTTLE"->app.repository.startFeeding(FeedingKind.BOTTLE);"SLEEP_LEFT"->app.repository.startSleep(SleepPosition.LEFT);"SLEEP_RIGHT"->app.repository.startSleep(SleepPosition.RIGHT)};app.familySync.schedule();AppSurfaceSync.refresh(context)}finally{pending.finish()}}}}

class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_BOOT_COMPLETED){val pending=goAsync();CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{AppSurfaceSync.refresh(context)}finally{pending.finish()}}}}}
