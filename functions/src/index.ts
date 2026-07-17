import {initializeApp} from "firebase-admin/app";
import {getFirestore, FieldValue} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import {createHash, randomInt} from "node:crypto";

initializeApp();
const db=getFirestore();
const hash=(value:string)=>createHash("sha256").update(value).digest("hex");
const requireUid=(request:any)=>{if(!request.auth)throw new HttpsError("unauthenticated","Authentication required");return request.auth.uid as string};
const code=()=>randomInt(0,100_000_000).toString().padStart(8,"0");

async function newInvite(householdId:string,createdBy:string){const value=code();await db.doc(`invites/${hash(value)}`).set({householdId,createdBy,expiresAt:Date.now()+24*60*60*1000,used:false});return value}

export const createHousehold=onCall(async request=>{const uid=requireUid(request);const displayName=String(request.data.displayName||"").trim();if(!displayName)throw new HttpsError("invalid-argument","Display name is required");const household=db.collection("households").doc();await db.runTransaction(async tx=>{tx.set(household,{createdAt:Date.now(),activeEventId:null,revision:0});tx.set(household.collection("members").doc(uid),{displayName,joinedAt:Date.now(),tokens:[]});tx.set(db.doc(`users/${uid}`),{householdId:household.id,displayName})});return{householdId:household.id,inviteCode:await newInvite(household.id,uid)}});

export const createInvite=onCall(async request=>{const uid=requireUid(request);const user=(await db.doc(`users/${uid}`).get()).data();if(!user)throw new HttpsError("failed-precondition","No household");return{inviteCode:await newInvite(user.householdId,uid)}});

export const joinHousehold=onCall(async request=>{const uid=requireUid(request);const value=String(request.data.code||"").replace(/\s/g,"").toUpperCase();const displayName=String(request.data.displayName||"").trim();const ref=db.doc(`invites/${hash(value)}`);let householdId="";await db.runTransaction(async tx=>{const invite=await tx.get(ref);const data=invite.data();if(!data||data.used||data.expiresAt<Date.now())throw new HttpsError("not-found","Invite is invalid or expired");householdId=data.householdId;tx.update(ref,{used:true,usedBy:uid});tx.set(db.doc(`households/${householdId}/members/${uid}`),{displayName,joinedAt:Date.now(),tokens:[]});tx.set(db.doc(`users/${uid}`),{householdId,displayName})});return{householdId}});

export const registerDevice=onCall(async request=>{const uid=requireUid(request);const user=(await db.doc(`users/${uid}`).get()).data();if(!user)throw new HttpsError("failed-precondition","No household");const token=String(request.data.token||"");await db.doc(`households/${user.householdId}/members/${uid}`).update({tokens:FieldValue.arrayUnion(token)});return{ok:true}});

export const processCommand=onCall(async request=>{
  const uid=requireUid(request);
  const user=(await db.doc(`users/${uid}`).get()).data();
  if(!user)throw new HttpsError("failed-precondition","No household");
  const householdId=user.householdId as string;
  const commandId=String(request.data.commandId);
  const command=String(request.data.command);
  const payload={...(request.data.payload||{}),authorId:uid,authorName:user.displayName};
  const occurredAt=Number(request.data.occurredAt)||Date.now();
  const family=db.doc(`households/${householdId}`);
  await db.runTransaction(async tx=>{
    const commandRef=family.collection("commands").doc(commandId);
    if((await tx.get(commandRef)).exists)return;
    const state=(await tx.get(family)).data()||{};
    const isReminder=command==="REMINDER_UPSERT"||command==="REMINDER_DELETE";
    const isCompletion=command==="REMINDER_COMPLETE"||command==="REMINDER_UNDO";
    if(isReminder||isCompletion){
      if(isReminder&&!payload.id)throw new HttpsError("invalid-argument","Reminder id is required");
      if(isCompletion&&(!payload.reminderId||payload.scheduledEpochDay===undefined||payload.scheduledEpochDay===null))throw new HttpsError("invalid-argument","Reminder completion identity is required");
      const identity=isReminder?String(payload.id||""):`${String(payload.reminderId||"")}:${String(payload.scheduledEpochDay||"")}`;
      const collection=isReminder?"reminders":"reminderCompletions";
      const target=family.collection(collection).doc(hash(identity));
      const current=(await tx.get(target)).data();
      const incomingUpdatedAt=Number(payload.updatedAt)||occurredAt;
      if(!current||incomingUpdatedAt>=Number(current.updatedAt||0)){
        tx.set(target,{...payload,updatedAt:incomingUpdatedAt},{merge:true});
        tx.set(family,{revision:FieldValue.increment(1)},{merge:true});
      }
    }else{
      const eventId=String(payload.remoteId||"");
      const eventRef=family.collection("events").doc(eventId);
      const isInstantLog=command==="LOG_SLEEP"||command==="LOG_PUMPING"||command==="LOG_BOTTLE"||(command==="START"&&payload.type==="SLEEP");
      if(isInstantLog){tx.set(eventRef,{...payload,endedAt:payload.endedAt||payload.startedAt||occurredAt});tx.set(family,{revision:FieldValue.increment(1)},{merge:true})}
      else if(command==="START"){if(state.activeEventId){tx.set(family.collection("events").doc(state.activeEventId),{endedAt:occurredAt,updatedAt:occurredAt},{merge:true})}tx.set(eventRef,payload);tx.set(family,{activeEventId:eventId,revision:FieldValue.increment(1)},{merge:true})}
      else if(command==="STOP"){tx.set(eventRef,{endedAt:occurredAt,updatedAt:occurredAt,detail:payload.detail,authorId:uid,authorName:user.displayName},{merge:true});if(state.activeEventId===eventId)tx.set(family,{activeEventId:null,revision:FieldValue.increment(1)},{merge:true})}
      else if(command==="UPDATE"){tx.set(eventRef,payload,{merge:true});tx.set(family,{revision:FieldValue.increment(1)},{merge:true})}
      else if(command==="DELETE"){tx.set(eventRef,{deletedAt:occurredAt,updatedAt:occurredAt,authorId:uid,authorName:user.displayName},{merge:true});if(state.activeEventId===eventId)tx.set(family,{activeEventId:null,revision:FieldValue.increment(1)},{merge:true})}
      else throw new HttpsError("invalid-argument","Unknown command");
    }
    tx.set(commandRef,{uid,command,occurredAt,processedAt:Date.now()});
  });
  const members=await family.collection("members").get();
  const tokens=members.docs.filter(d=>d.id!==uid).flatMap(d=>(d.data().tokens||[]) as string[]);
  if(tokens.length)await getMessaging().sendEachForMulticast({tokens,data:{kind:"sync",householdId},android:{priority:"high"}});
  return{ok:true};
});
