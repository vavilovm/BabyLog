import {initializeApp} from "firebase-admin/app";
import {
  DocumentReference,
  FieldValue,
  Transaction,
  getFirestore,
} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {logger} from "firebase-functions";
import {CallableRequest, HttpsError, onCall} from "firebase-functions/v2/https";
import {createHash, randomInt} from "node:crypto";
import {
  CommandInput,
  COMPLETION_COMMANDS,
  JsonRecord,
  REMINDER_COMMANDS,
  parseCommand,
  record,
  requiredString,
  validatePayload,
} from "./contracts";

initializeApp();
const db = getFirestore();

type UserRecord = {householdId: string; displayName: string};
type CommandResult = {commandId: string; revision: number; duplicate: boolean};

const hash = (value: string) =>
  createHash("sha256").update(value).digest("hex");
const inviteCode = () =>
  randomInt(0, 100_000_000).toString().padStart(8, "0");

function requireUid(request: CallableRequest<unknown>): string {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  return request.auth.uid;
}

async function userFor(uid: string): Promise<UserRecord> {
  const data = (await db.doc(`users/${uid}`).get()).data();
  if (!data) throw new HttpsError("failed-precondition", "No household");
  return {
    householdId: requiredString(data.householdId, "Household id"),
    displayName: requiredString(data.displayName, "Display name", 80),
  };
}

async function newInvite(
  householdId: string,
  createdBy: string
): Promise<string> {
  const value = inviteCode();
  await db.doc(`invites/${hash(value)}`).set({
    householdId,
    createdBy,
    expiresAt: Date.now() + 24 * 60 * 60 * 1000,
    used: false,
  });
  return value;
}

export const createHousehold = onCall(async (request) => {
  const uid = requireUid(request);
  const input = record(request.data);
  const displayName = requiredString(input.displayName, "Display name", 80);
  const household = db.collection("households").doc();
  await db.runTransaction(async (tx) => {
    tx.set(household, {createdAt: Date.now(), activeEventId: null, revision: 0});
    tx.set(household.collection("members").doc(uid), {
      displayName,
      joinedAt: Date.now(),
      tokens: [],
    });
    tx.set(db.doc(`users/${uid}`), {householdId: household.id, displayName});
  });
  return {
    householdId: household.id,
    inviteCode: await newInvite(household.id, uid),
  };
});

export const createInvite = onCall(async (request) => {
  const uid = requireUid(request);
  const user = await userFor(uid);
  return {inviteCode: await newInvite(user.householdId, uid)};
});

export const joinHousehold = onCall(async (request) => {
  const uid = requireUid(request);
  const input = record(request.data);
  const value = requiredString(input.code, "Invite code", 8)
    .replace(/\s/g, "")
    .toUpperCase();
  if (!/^\d{8}$/.test(value)) {
    throw new HttpsError("invalid-argument", "Invite code is invalid");
  }
  const displayName = requiredString(input.displayName, "Display name", 80);
  const ref = db.doc(`invites/${hash(value)}`);
  let householdId = "";
  await db.runTransaction(async (tx) => {
    const invite = await tx.get(ref);
    const data = invite.data();
    if (!data || data.used || Number(data.expiresAt) < Date.now()) {
      throw new HttpsError("not-found", "Invite is invalid or expired");
    }
    householdId = requiredString(data.householdId, "Household id");
    tx.update(ref, {used: true, usedBy: uid});
    tx.set(db.doc(`households/${householdId}/members/${uid}`), {
      displayName,
      joinedAt: Date.now(),
      tokens: [],
    });
    tx.set(db.doc(`users/${uid}`), {householdId, displayName});
  });
  return {householdId};
});

export const registerDevice = onCall(async (request) => {
  const uid = requireUid(request);
  const user = await userFor(uid);
  const input = record(request.data);
  const token = requiredString(input.token, "Device token", 4096);
  await db.doc(`households/${user.householdId}/members/${uid}`).update({
    tokens: FieldValue.arrayUnion(token),
  });
  return {ok: true};
});

function targetFor(
  family: DocumentReference,
  command: string,
  payload: JsonRecord
): DocumentReference {
  if (REMINDER_COMMANDS.has(command)) {
    return family.collection("reminders").doc(hash(String(payload.id)));
  }
  if (COMPLETION_COMMANDS.has(command)) {
    const identity = `${String(payload.reminderId)}:${String(
      payload.scheduledEpochDay
    )}`;
    return family.collection("reminderCompletions").doc(hash(identity));
  }
  return family.collection("events").doc(String(payload.remoteId));
}

function writeEvent(
  tx: Transaction,
  family: DocumentReference,
  target: DocumentReference,
  command: string,
  payload: JsonRecord,
  occurredAt: number,
  revision: number,
  uid: string,
  user: UserRecord,
  activeEventId: unknown
): void {
  const authored = {...payload, authorId: uid, authorName: user.displayName, revision};
  const instant =
    command === "LOG_SLEEP" ||
    command === "LOG_PUMPING" ||
    command === "LOG_BOTTLE" ||
    (command === "START" && payload.type === "SLEEP");
  if (instant) {
    tx.set(target, {
      ...authored,
      endedAt: payload.endedAt ?? payload.startedAt ?? occurredAt,
    });
  } else if (command === "START") {
    if (activeEventId) {
      tx.set(
        family.collection("events").doc(String(activeEventId)),
        {endedAt: occurredAt, updatedAt: occurredAt, revision},
        {merge: true}
      );
    }
    tx.set(target, authored);
    tx.set(family, {activeEventId: String(payload.remoteId)}, {merge: true});
  } else if (command === "STOP") {
    tx.set(
      target,
      {
        endedAt: occurredAt,
        updatedAt: occurredAt,
        detail: payload.detail,
        authorId: uid,
        authorName: user.displayName,
        revision,
      },
      {merge: true}
    );
    if (activeEventId === payload.remoteId) {
      tx.set(family, {activeEventId: null}, {merge: true});
    }
  } else if (command === "UPDATE") {
    tx.set(target, authored, {merge: true});
  } else if (command === "DELETE") {
    tx.set(
      target,
      {
        deletedAt: occurredAt,
        updatedAt: occurredAt,
        authorId: uid,
        authorName: user.displayName,
        revision,
      },
      {merge: true}
    );
    if (activeEventId === payload.remoteId) {
      tx.set(family, {activeEventId: null}, {merge: true});
    }
  }
}

async function processOne(
  uid: string,
  user: UserRecord,
  input: CommandInput
): Promise<CommandResult> {
  validatePayload(input.command, input.payload);
  const family = db.doc(`households/${user.householdId}`);
  return db.runTransaction(async (tx) => {
    const commandRef = family.collection("commands").doc(input.commandId);
    const target = targetFor(family, input.command, input.payload);
    const [commandSnapshot, familySnapshot, targetSnapshot] = await Promise.all([
      tx.get(commandRef),
      tx.get(family),
      tx.get(target),
    ]);
    if (commandSnapshot.exists) {
      return {
        commandId: input.commandId,
        revision: Number(commandSnapshot.data()?.revision ?? 0),
        duplicate: true,
      };
    }

    const state = familySnapshot.data() ?? {};
    const currentRevision = Number(state.revision ?? 0);
    const current = targetSnapshot.data();
    const incomingUpdatedAt = Number(input.payload.updatedAt ?? input.occurredAt);
    const shouldApply =
      !current ||
      !REMINDER_COMMANDS.has(input.command) && !COMPLETION_COMMANDS.has(input.command) ||
      incomingUpdatedAt >= Number(current.updatedAt ?? 0);
    const revision = shouldApply ? currentRevision + 1 : currentRevision;

    if (shouldApply) {
      if (REMINDER_COMMANDS.has(input.command) || COMPLETION_COMMANDS.has(input.command)) {
        tx.set(
          target,
          {...input.payload, updatedAt: incomingUpdatedAt, revision},
          {merge: true}
        );
      } else {
        writeEvent(
          tx,
          family,
          target,
          input.command,
          input.payload,
          input.occurredAt,
          revision,
          uid,
          user,
          state.activeEventId
        );
      }
      tx.set(family, {revision}, {merge: true});
    }
    tx.set(commandRef, {
      uid,
      command: input.command,
      occurredAt: input.occurredAt,
      processedAt: Date.now(),
      revision,
    });
    return {commandId: input.commandId, revision, duplicate: false};
  });
}

async function notifyMembers(
  householdId: string,
  senderUid: string
): Promise<void> {
  try {
    const members = await db
      .doc(`households/${householdId}`)
      .collection("members")
      .get();
    const owners = new Map<string, DocumentReference>();
    for (const member of members.docs) {
      if (member.id === senderUid) continue;
      for (const token of (member.data().tokens ?? []) as string[]) {
        if (token) owners.set(token, member.ref);
      }
    }
    const tokens = [...owners.keys()];
    if (!tokens.length) return;
    const response = await getMessaging().sendEachForMulticast({
      tokens,
      data: {kind: "sync", householdId},
      android: {priority: "high"},
    });
    const invalidCodes = new Set([
      "messaging/registration-token-not-registered",
      "messaging/invalid-registration-token",
    ]);
    const removals = response.responses.flatMap((result, index) => {
      const token = tokens[index];
      const owner = owners.get(token);
      return !result.success && owner && invalidCodes.has(result.error?.code ?? "") ?
        [owner.update({tokens: FieldValue.arrayRemove(token)})] :
        [];
    });
    await Promise.all(removals);
  } catch (error) {
    // The command is already committed. Push is an optimization and must not
    // turn an acknowledged mutation into a client-visible failure.
    logger.warn("Family sync push failed", error);
  }
}

export const processCommand = onCall(async (request) => {
  const uid = requireUid(request);
  const user = await userFor(uid);
  const result = await processOne(uid, user, parseCommand(request.data));
  if (!result.duplicate) await notifyMembers(user.householdId, uid);
  return {ok: true, revision: result.revision};
});

export const processCommandsV2 = onCall(async (request) => {
  const uid = requireUid(request);
  const user = await userFor(uid);
  const input = record(request.data);
  if (!Array.isArray(input.commands) || input.commands.length > 50) {
    throw new HttpsError("invalid-argument", "One to fifty commands are required");
  }
  const commands = input.commands.map(parseCommand);
  if (!commands.length) {
    throw new HttpsError("invalid-argument", "At least one command is required");
  }
  const results: CommandResult[] = [];
  for (const command of commands) {
    results.push(await processOne(uid, user, command));
  }
  if (results.some((result) => !result.duplicate)) {
    await notifyMembers(user.householdId, uid);
  }
  return {results};
});
