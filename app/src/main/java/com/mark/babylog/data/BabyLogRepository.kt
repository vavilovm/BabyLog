package com.mark.babylog.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

class BabyLogRepository(private val db:BabyDatabase){
    val dao=db.events()
    val events=dao.observeAll()
    val segments=dao.observeSegments()
    val membership=dao.observeMembership()
    val pendingCount=dao.observePendingCount()

    suspend fun startFeeding(kind:FeedingKind,time:Long=System.currentTimeMillis())=db.withTransaction{val owner=dao.membership();val event=dao.startFeeding(kind,time,owner);enqueue("START",event,time)}
    suspend fun startSleep(position:SleepPosition,time:Long=System.currentTimeMillis())=db.withTransaction{val owner=dao.membership();val event=dao.startSleep(position,time,owner);enqueue("LOG_SLEEP",event,time)}
    suspend fun logPumping(side:FeedingKind,volumeMl:Int,time:Long=System.currentTimeMillis())=db.withTransaction{val owner=dao.membership();val event=dao.logPumping(side,volumeMl,time,owner);enqueue("LOG_PUMPING",event,time)}
    suspend fun stop(time:Long=System.currentTimeMillis())=db.withTransaction{dao.stopActive(time)?.let{enqueue("STOP",it,time)}}
    suspend fun stopBottle(volumeMl:Int,time:Long=System.currentTimeMillis())=db.withTransaction{require(volumeMl>0);val stopped=dao.stopActive(time)?:return@withTransaction;require(feedingKindOf(stopped.detail)==FeedingKind.BOTTLE);val changed=stopped.copy(detail="BOTTLE:$volumeMl",updatedAt=time);dao.update(changed);enqueue("STOP",changed,time)}
    suspend fun changePosition(position:SleepPosition,time:Long=System.currentTimeMillis())=db.withTransaction{val active=dao.active()?:return@withTransaction;if(active.type!=EventType.SLEEP)return@withTransaction;dao.finishSegment(active.id,time);dao.insert(SleepSegment(eventId=active.id,position=position,startedAt=time,syncState=active.syncState));val changed=active.copy(detail=position.name,updatedAt=time,syncState=SyncState.PENDING);dao.update(changed);enqueue("UPDATE",changed,time)}
    suspend fun updateEvent(event:BabyEvent)=db.withTransaction{val owner=dao.membership();val changed=event.copy(updatedAt=System.currentTimeMillis(),authorId=owner?.memberId,authorName=owner?.displayName,syncState=if(owner==null)SyncState.LOCAL_ONLY else SyncState.PENDING);dao.update(changed);enqueue("UPDATE",changed,changed.updatedAt)}
    suspend fun deleteEvent(event:BabyEvent)=db.withTransaction{val now=System.currentTimeMillis();val deleted=event.copy(deletedAt=now,updatedAt=now,syncState=SyncState.PENDING);dao.update(deleted);enqueue("DELETE",deleted,now)}

    private suspend fun enqueue(command:String,event:BabyEvent,time:Long){if(dao.membership()==null)return;dao.put(SyncOperation(command=command,payload=eventJson(event).toString(),occurredAt=time))}
    private suspend fun eventJson(e:BabyEvent)=JSONObject().apply{put("remoteId",e.remoteId);put("type",e.type.name);put("detail",e.detail);put("startedAt",e.startedAt);put("endedAt",e.endedAt?:JSONObject.NULL);put("updatedAt",e.updatedAt);put("deletedAt",e.deletedAt?:JSONObject.NULL);put("authorId",e.authorId?:JSONObject.NULL);put("authorName",e.authorName?:JSONObject.NULL);put("segments",JSONArray(dao.segments(e.id).map{segment->JSONObject().apply{put("remoteId",segment.remoteId);put("position",segment.position.name);put("startedAt",segment.startedAt);put("endedAt",segment.endedAt?:JSONObject.NULL);put("updatedAt",segment.updatedAt)}}))}

    suspend fun applyRemote(values:List<Map<String,Any?>>)=db.withTransaction{values.forEach{raw->
        val remoteId=raw["remoteId"] as? String?:return@forEach;val existing=dao.byRemoteId(remoteId);val remoteUpdatedAt=(raw["updatedAt"] as? Number)?.toLong()?:0;if(existing?.syncState==SyncState.PENDING&&existing.updatedAt>remoteUpdatedAt)return@forEach;val type=runCatching{EventType.valueOf(raw["type"] as String)}.getOrNull()?:return@forEach
        val event=BabyEvent(id=existing?.id?:0,type=type,detail=raw["detail"] as? String?:"LEFT",startedAt=(raw["startedAt"] as? Number)?.toLong()?:0,endedAt=(raw["endedAt"] as? Number)?.toLong(),remoteId=remoteId,householdId=dao.membership()?.householdId,authorId=raw["authorId"] as? String,authorName=raw["authorName"] as? String,updatedAt=remoteUpdatedAt,syncState=SyncState.SYNCED,deletedAt=(raw["deletedAt"] as? Number)?.toLong())
        val localId=if(existing==null)dao.insert(event) else{dao.update(event);event.id}
        @Suppress("UNCHECKED_CAST") val remoteSegments=raw["segments"] as? List<Map<String,Any?>>?:emptyList()
        val localByRemote=dao.segments(localId).associateBy{it.remoteId};remoteSegments.forEach{segment->val sid=segment["remoteId"] as? String?:return@forEach;val old=localByRemote[sid];val value=SleepSegment(id=old?.id?:0,eventId=localId,position=runCatching{SleepPosition.valueOf(segment["position"] as String)}.getOrDefault(SleepPosition.LEFT),startedAt=(segment["startedAt"] as Number).toLong(),endedAt=(segment["endedAt"] as? Number)?.toLong(),remoteId=sid,updatedAt=(segment["updatedAt"] as? Number)?.toLong()?:0,syncState=SyncState.SYNCED);if(old==null)dao.insert(value) else dao.update(value)}
    }}
}
