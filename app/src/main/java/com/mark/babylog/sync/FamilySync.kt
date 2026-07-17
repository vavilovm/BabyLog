package com.mark.babylog.sync

import android.content.Context
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.mark.babylog.BabyLogApp
import com.mark.babylog.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FamilySyncStatus(val running:Boolean=false,val error:String?=null,val lastSyncedAt:Long?=null)
data class FamilyMember(val id:String,val displayName:String)

class FamilySync(private val app:BabyLogApp){
    val status=MutableStateFlow(FamilySyncStatus())
    val members=MutableStateFlow<List<FamilyMember>>(emptyList())
    private val available get()=FirebaseApp.getApps(app).isNotEmpty()
    private val listeners=mutableListOf<ListenerRegistration>()
    private val dao get()=app.database.events()

    suspend fun createFamily(name:String):String{require(available){"Добавьте app/google-services.json"};val uid=auth();val result=FirebaseFunctions.getInstance().getHttpsCallable("createHousehold").call(mapOf("displayName" to name.trim())).await().getData() as Map<*,*>;val family=FamilyMembership(householdId=result["householdId"] as String,memberId=uid,displayName=name.trim(),inviteCode=result["inviteCode"] as? String);dao.putMembership(family);app.reminderRepository.attachToFamily();registerToken();startRealtime();schedule();return family.inviteCode.orEmpty()}
    suspend fun joinFamily(code:String,name:String){require(available){"Добавьте app/google-services.json"};val uid=auth();val result=FirebaseFunctions.getInstance().getHttpsCallable("joinHousehold").call(mapOf("code" to code.trim().uppercase(),"displayName" to name.trim())).await().getData() as Map<*,*>;dao.putMembership(FamilyMembership(householdId=result["householdId"] as String,memberId=uid,displayName=name.trim()));registerToken();pull();app.reminderRepository.attachToFamily();startRealtime();schedule()}
    suspend fun createInvite():String{val result=FirebaseFunctions.getInstance().getHttpsCallable("createInvite").call().await().getData() as Map<*,*>;val code=result["inviteCode"] as String;dao.membership()?.let{dao.putMembership(it.copy(inviteCode=code))};return code}
    private suspend fun auth():String{val auth=FirebaseAuth.getInstance();if(auth.currentUser==null)auth.signInAnonymously().await();return requireNotNull(auth.currentUser).uid}
    private suspend fun registerToken(){val token=FirebaseMessaging.getInstance().token.await();FirebaseFunctions.getInstance().getHttpsCallable("registerDevice").call(mapOf("token" to token)).await()}

    suspend fun sync(){if(!available||dao.membership()==null)return;status.value=FamilySyncStatus(running=true);try{auth();dao.pending().forEach{op->try{FirebaseFunctions.getInstance().getHttpsCallable("processCommand").call(mapOf("commandId" to op.id,"command" to op.command,"payload" to jsonMap(JSONObject(op.payload)),"occurredAt" to op.occurredAt)).await();dao.removeOperation(op.id)}catch(e:Exception){dao.failOperation(op.id,e.message?:"Ошибка синхронизации");throw e}};pull();status.value=FamilySyncStatus(lastSyncedAt=System.currentTimeMillis())}catch(e:Exception){status.value=FamilySyncStatus(error=e.message)}}
    suspend fun pull(){
        val owner=dao.membership()?:return
        val family=FirebaseFirestore.getInstance().collection("households").document(owner.householdId)
        val (eventSnapshot,reminderSnapshot,completionSnapshot)=coroutineScope{
            val events=async{family.collection("events").get().await()}
            val reminders=async{family.collection("reminders").get().await()}
            val completions=async{family.collection("reminderCompletions").get().await()}
            Triple(events.await(),reminders.await(),completions.await())
        }
        app.repository.applyRemote(eventSnapshot.documents.mapNotNull{it.data})
        app.reminderRepository.applyRemoteReminders(reminderSnapshot.documents.mapNotNull{it.data})
        app.reminderRepository.applyRemoteCompletions(completionSnapshot.documents.mapNotNull{it.data})
        AppSurfaceSync.refresh(app)
    }
    fun startRealtime(){
        if(!available)return
        val owner=runBlocking(Dispatchers.IO){dao.membership()}?:return
        listeners.forEach{it.remove()};listeners.clear()
        val family=FirebaseFirestore.getInstance().collection("households").document(owner.householdId)
        listeners+=family.collection("events").addSnapshotListener{snapshot,error->
            if(error!=null){status.value=FamilySyncStatus(error=error.message);return@addSnapshotListener}
            snapshot?:return@addSnapshotListener
            app.appScope.launch{try{app.repository.applyRemote(snapshot.documents.mapNotNull{it.data});AppSurfaceSync.refresh(app)}catch(error:Throwable){status.value=FamilySyncStatus(error=error.message?:"Ошибка realtime-синхронизации")}}
        }
        listeners+=family.collection("reminders").addSnapshotListener{snapshot,error->
            if(error!=null){status.value=FamilySyncStatus(error=error.message);return@addSnapshotListener}
            snapshot?:return@addSnapshotListener
            app.appScope.launch{try{app.reminderRepository.applyRemoteReminders(snapshot.documents.mapNotNull{it.data});val completions=family.collection("reminderCompletions").get().await();app.reminderRepository.applyRemoteCompletions(completions.documents.mapNotNull{it.data})}catch(error:Throwable){status.value=FamilySyncStatus(error=error.message?:"Ошибка синхронизации напоминаний")}}
        }
        listeners+=family.collection("reminderCompletions").addSnapshotListener{snapshot,error->
            if(error!=null){status.value=FamilySyncStatus(error=error.message);return@addSnapshotListener}
            snapshot?:return@addSnapshotListener
            app.appScope.launch{try{app.reminderRepository.applyRemoteCompletions(snapshot.documents.mapNotNull{it.data})}catch(error:Throwable){status.value=FamilySyncStatus(error=error.message?:"Ошибка синхронизации выполненного")}}
        }
        listeners+=family.collection("members").addSnapshotListener{snapshot,_->members.value=snapshot?.documents?.map{FamilyMember(it.id,it.getString("displayName").orEmpty())}.orEmpty()}
    }
    fun schedule(){SyncWorker.enqueue(app)}
    private fun jsonMap(value:JSONObject):Map<String,Any?> = value.keys().asSequence().associateWith{key->when(val v=value.get(key)){is JSONObject->jsonMap(v);is org.json.JSONArray->(0 until v.length()).map{i->val item=v.get(i);if(item is JSONObject)jsonMap(item) else item};JSONObject.NULL->null;else->v}}
}

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){override suspend fun doWork():Result{val sync=(applicationContext as BabyLogApp).familySync;sync.sync();return if(sync.status.value.error==null)Result.success() else Result.retry()}companion object{fun enqueue(context:Context){WorkManager.getInstance(context).enqueueUniqueWork("babylog-sync",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())}fun periodic(context:Context){WorkManager.getInstance(context).enqueueUniquePeriodicWork("babylog-periodic-sync",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).build())}}}

class BabyLogMessagingService:com.google.firebase.messaging.FirebaseMessagingService(){override fun onMessageReceived(message:com.google.firebase.messaging.RemoteMessage){SyncWorker.enqueue(this)}override fun onDeletedMessages(){SyncWorker.enqueue(this)}override fun onNewToken(token:String){SyncWorker.enqueue(this)}}
