package com.mark.babylog.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mark.babylog.BabyLogApp
import com.mark.babylog.MainActivity
import com.mark.babylog.R
import com.mark.babylog.data.BabyReminder
import com.mark.babylog.data.BabyDatabase
import com.mark.babylog.data.ReminderCompletion
import com.mark.babylog.data.SyncOperation
import com.mark.babylog.data.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime

class ReminderRepository(private val app:BabyLogApp,private val database:BabyDatabase=app.database,private val requestSync:()->Unit={app.familySync.schedule()},private val reschedule:suspend()->Unit={ReminderScheduler.rescheduleAll(app)}){
    private val dao get()=database.events()
    val reminders get()=dao.observeReminders()
    val completions get()=dao.observeReminderCompletions()

    suspend fun ensureDefaults(){
        val preferences=app.getSharedPreferences("reminder_defaults",Context.MODE_PRIVATE)
        if(preferences.getBoolean("created",false))return
        if(dao.reminderCount()==0){
            val today=LocalDate.now().toEpochDay()
            dao.putReminder(BabyReminder(id="default-vitamin-k",title="Дать витамин К",anchorEpochDay=today,createdAt=1))
            dao.putReminder(BabyReminder(id="default-vitamin-d",title="Дать витамин Д",anchorEpochDay=today,createdAt=2))
        }
        preferences.edit().putBoolean("created",true).apply()
    }

    suspend fun save(value:BabyReminder){
        val old=dao.reminder(value.id)
        val now=System.currentTimeMillis()
        val owner=dao.membership()
        val saved=value.copy(
            title=value.title.trim(),
            hour=value.hour.coerceIn(0,23),
            minute=value.minute.coerceIn(0,59),
            intervalDays=value.intervalDays.coerceAtLeast(1),
            anchorEpochDay=if(old!=null&&old.intervalDays==value.intervalDays)old.anchorEpochDay else LocalDate.now().toEpochDay(),
            createdAt=old?.createdAt?:value.createdAt,
            updatedAt=now,
            deletedAt=null,
            syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING
        )
        dao.putReminder(saved)
        if(owner!=null){queue("REMINDER_UPSERT",reminderJson(saved),now);requestSync()}
        ReminderScheduler.cancel(app,saved.id)
        if(saved.enabled)ReminderScheduler.schedule(app,saved,includeMissedToday=true)
    }

    suspend fun delete(value:BabyReminder){
        val now=System.currentTimeMillis()
        val owner=dao.membership()
        val deleted=value.copy(updatedAt=now,deletedAt=now,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        dao.putReminder(deleted)
        if(owner!=null){queue("REMINDER_DELETE",reminderJson(deleted),now);requestSync()}
        ReminderScheduler.cancel(app,value.id)
        ReminderScheduler.cancelNotification(app,value.id)
    }
    suspend fun setEnabled(value:BabyReminder,enabled:Boolean)=save(value.copy(enabled=enabled))
    suspend fun complete(reminderId:String,epochDay:Long){
        val now=System.currentTimeMillis()
        val owner=dao.membership()
        val value=ReminderCompletion(reminderId,epochDay,completedAt=now,updatedAt=now,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        dao.putReminderCompletion(value)
        if(owner!=null){queue("REMINDER_COMPLETE",completionJson(value),now);requestSync()}
        ReminderScheduler.cancelNotification(app,reminderId)
        ReminderScheduler.cancel(app,reminderId)
        dao.reminder(reminderId)?.takeIf{it.enabled&&it.deletedAt==null}?.let{ReminderScheduler.schedule(app,it,includeMissedToday=false)}
    }
    suspend fun undo(reminderId:String,epochDay:Long){
        val now=System.currentTimeMillis()
        val owner=dao.membership()
        val existing=dao.reminderCompletionIncludingDeleted(reminderId,epochDay)
        val value=(existing?:ReminderCompletion(reminderId,epochDay,completedAt=now)).copy(updatedAt=now,deletedAt=now,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING)
        dao.putReminderCompletion(value)
        if(owner!=null){queue("REMINDER_UNDO",completionJson(value),now);requestSync()}
        dao.reminder(reminderId)?.takeIf{it.enabled&&it.deletedAt==null}?.let{ReminderScheduler.schedule(app,it,includeMissedToday=true)}
    }

    suspend fun attachToFamily(){
        if(dao.membership()==null)return
        var queued=false
        dao.allRemindersForSync().filter{it.syncState==SyncState.LOCAL_ONLY}.forEach{value->
            val pending=value.copy(syncState=SyncState.PENDING)
            dao.putReminder(pending)
            queue(if(value.deletedAt==null)"REMINDER_UPSERT" else "REMINDER_DELETE",reminderJson(pending),pending.updatedAt)
            queued=true
        }
        dao.allReminderCompletionsForSync().filter{it.syncState==SyncState.LOCAL_ONLY}.forEach{value->
            val pending=value.copy(syncState=SyncState.PENDING)
            dao.putReminderCompletion(pending)
            queue(if(value.deletedAt==null)"REMINDER_COMPLETE" else "REMINDER_UNDO",completionJson(pending),pending.updatedAt)
            queued=true
        }
        if(queued)requestSync()
    }

    suspend fun applyRemoteReminders(values:List<Map<String,Any?>>){
        values.forEach{raw->
            val id=raw["id"] as? String?:return@forEach
            val remoteUpdatedAt=(raw["updatedAt"] as? Number)?.toLong()?:0
            val existing=dao.reminder(id)
            if(!shouldApplyRemote(existing?.syncState,existing?.updatedAt?:0,remoteUpdatedAt))return@forEach
            val value=BabyReminder(
                id=id,
                title=raw["title"] as? String?:return@forEach,
                hour=(raw["hour"] as? Number)?.toInt()?:9,
                minute=(raw["minute"] as? Number)?.toInt()?:0,
                intervalDays=((raw["intervalDays"] as? Number)?.toInt()?:1).coerceAtLeast(1),
                anchorEpochDay=(raw["anchorEpochDay"] as? Number)?.toLong()?:LocalDate.now().toEpochDay(),
                enabled=raw["enabled"] as? Boolean?:true,
                createdAt=(raw["createdAt"] as? Number)?.toLong()?:remoteUpdatedAt,
                updatedAt=remoteUpdatedAt,
                deletedAt=(raw["deletedAt"] as? Number)?.toLong(),
                syncState=SyncState.SYNCED
            )
            dao.putReminder(value)
            if(value.deletedAt!=null){ReminderScheduler.cancel(app,id);ReminderScheduler.cancelNotification(app,id)}
        }
        reschedule()
    }

    suspend fun applyRemoteCompletions(values:List<Map<String,Any?>>){
        values.forEach{raw->
            val reminderId=raw["reminderId"] as? String?:return@forEach
            if(dao.reminder(reminderId)==null)return@forEach
            val epochDay=(raw["scheduledEpochDay"] as? Number)?.toLong()?:return@forEach
            val remoteUpdatedAt=(raw["updatedAt"] as? Number)?.toLong()?:0
            val existing=dao.reminderCompletionIncludingDeleted(reminderId,epochDay)
            if(!shouldApplyRemote(existing?.syncState,existing?.updatedAt?:0,remoteUpdatedAt))return@forEach
            dao.putReminderCompletion(ReminderCompletion(
                reminderId=reminderId,
                scheduledEpochDay=epochDay,
                completedAt=(raw["completedAt"] as? Number)?.toLong()?:remoteUpdatedAt,
                updatedAt=remoteUpdatedAt,
                deletedAt=(raw["deletedAt"] as? Number)?.toLong(),
                syncState=SyncState.SYNCED
            ))
        }
        reschedule()
    }

    private suspend fun queue(command:String,payload:JSONObject,time:Long){dao.put(SyncOperation(command=command,payload=payload.toString(),occurredAt=time))}
    private fun reminderJson(value:BabyReminder)=JSONObject().apply{put("id",value.id);put("title",value.title);put("hour",value.hour);put("minute",value.minute);put("intervalDays",value.intervalDays);put("anchorEpochDay",value.anchorEpochDay);put("enabled",value.enabled);put("createdAt",value.createdAt);put("updatedAt",value.updatedAt);put("deletedAt",value.deletedAt?:JSONObject.NULL)}
    private fun completionJson(value:ReminderCompletion)=JSONObject().apply{put("reminderId",value.reminderId);put("scheduledEpochDay",value.scheduledEpochDay);put("completedAt",value.completedAt);put("updatedAt",value.updatedAt);put("deletedAt",value.deletedAt?:JSONObject.NULL)}
}

internal fun shouldApplyRemote(localState:SyncState?,localUpdatedAt:Long,remoteUpdatedAt:Long)=localState!=SyncState.PENDING||localUpdatedAt<=remoteUpdatedAt

internal fun BabyReminder.occursOn(date:LocalDate):Boolean =
    date.toEpochDay()>=anchorEpochDay && Math.floorMod(date.toEpochDay()-anchorEpochDay,intervalDays.toLong())==0L

object ReminderScheduler{
    private const val CHANNEL="scheduled_reminders_silent"
    private const val ACTION_FIRE="com.mark.babylog.REMINDER_FIRE"
    internal const val ACTION_DONE="com.mark.babylog.REMINDER_DONE"
    internal const val EXTRA_ID="reminder_id"
    internal const val EXTRA_DAY="reminder_day"

    suspend fun rescheduleAll(context:Context){
        val app=context.applicationContext as BabyLogApp
        app.database.events().reminders().forEach{reminder->
            cancel(context,reminder.id)
            if(reminder.enabled)schedule(context,reminder,includeMissedToday=true)
        }
    }

    suspend fun schedule(context:Context,reminder:BabyReminder,includeMissedToday:Boolean){
        if(!reminder.enabled||reminder.deletedAt!=null)return
        val app=context.applicationContext as BabyLogApp
        val now=ZonedDateTime.now()
        val next=nextOccurrence(reminder,now,includeMissedToday){day->app.database.events().reminderCompletion(reminder.id,day.toEpochDay())!=null}?:return
        val alarm=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),alarmIntent(context,reminder.id,next.toLocalDate().toEpochDay()))
    }

    private suspend fun nextOccurrence(reminder:BabyReminder,now:ZonedDateTime,includeMissedToday:Boolean,isComplete:suspend(LocalDate)->Boolean):ZonedDateTime?{
        var date=now.toLocalDate()
        repeat(3660){
            if(reminder.occursOn(date)&&!isComplete(date)){
                var candidate=date.atTime(reminder.hour,reminder.minute).atZone(now.zone)
                if(candidate.isAfter(now))return candidate
                if(includeMissedToday&&date==now.toLocalDate())return now.plusSeconds(2)
            }
            date=date.plusDays(1)
        }
        return null
    }

    fun cancel(context:Context,id:String){(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context,id,0))}
    fun cancelNotification(context:Context,id:String)=NotificationManagerCompat.from(context).cancel(notificationId(id))

    private fun alarmIntent(context:Context,id:String,day:Long)=PendingIntent.getBroadcast(
        context,requestCode(id),Intent(context,ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("babylog://reminder/$id")).putExtra(EXTRA_ID,id).putExtra(EXTRA_DAY,day),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal suspend fun fire(context:Context,id:String,day:Long){
        val app=context.applicationContext as BabyLogApp
        val dao=app.database.events()
        val reminder=dao.reminder(id)?:return
        val date=LocalDate.ofEpochDay(day)
        if(!reminder.enabled||reminder.deletedAt!=null)return
        if(!reminder.occursOn(date)||dao.reminderCompletion(id,day)!=null){schedule(context,reminder,includeMissedToday=false);return}
        show(context,reminder,day)
        schedule(context,reminder,includeMissedToday=false)
    }

    private fun show(context:Context,reminder:BabyReminder,day:Long){
        createChannel(context)
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val open=PendingIntent.getActivity(context,requestCode(reminder.id),Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val done=PendingIntent.getBroadcast(context,requestCode(reminder.id)+1,Intent(context,ReminderReceiver::class.java).setAction(ACTION_DONE).setData(Uri.parse("babylog://reminder/${reminder.id}/done/$day")).putExtra(EXTRA_ID,reminder.id).putExtra(EXTRA_DAY,day),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(reminder.title)
            .setContentText("Нажмите «Готово», когда выполните")
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0,"Готово",done)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(reminder.id),notification)
    }

    private fun createChannel(context:Context){
        if(Build.VERSION.SDK_INT>=26)(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL,"Напоминания",NotificationManager.IMPORTANCE_DEFAULT).apply{description="Тихие напоминания об уходе";setSound(null,null);enableVibration(false);setShowBadge(true);lockscreenVisibility=Notification.VISIBILITY_PRIVATE}
        )
    }

    private fun requestCode(id:String)=id.hashCode() and 0x3fffffff
    private fun notificationId(id:String)=10_000+(requestCode(id)%100_000)
}

class ReminderReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{
            try{
                val id=intent.getStringExtra(ReminderScheduler.EXTRA_ID)?:return@launch
                val day=intent.getLongExtra(ReminderScheduler.EXTRA_DAY,LocalDate.now().toEpochDay())
                val app=context.applicationContext as BabyLogApp
                if(intent.action==ReminderScheduler.ACTION_DONE)app.reminderRepository.complete(id,day) else ReminderScheduler.fire(context,id,day)
            }finally{pending.finish()}
        }
    }
}
