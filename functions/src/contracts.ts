import {HttpsError} from "firebase-functions/v2/https";

export type JsonRecord = Record<string, unknown>;
export type CommandInput = {
  commandId: string;
  command: string;
  payload: JsonRecord;
  occurredAt: number;
};

export const EVENT_COMMANDS = new Set([
  "LOG_SLEEP",
  "LOG_PUMPING",
  "LOG_BOTTLE",
  "START",
  "STOP",
  "UPDATE",
  "DELETE",
]);
export const REMINDER_COMMANDS = new Set([
  "REMINDER_UPSERT",
  "REMINDER_DELETE",
]);
export const COMPLETION_COMMANDS = new Set([
  "REMINDER_COMPLETE",
  "REMINDER_UNDO",
]);

export function record(value: unknown): JsonRecord {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", "Object payload is required");
  }
  return value as JsonRecord;
}

export function requiredString(
  value: unknown,
  label: string,
  maxLength = 200
): string {
  const text = String(value ?? "").trim();
  if (!text || text.length > maxLength) {
    throw new HttpsError("invalid-argument", `${label} is invalid`);
  }
  return text;
}

export function requiredNumber(value: unknown, label: string): number {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    throw new HttpsError("invalid-argument", `${label} is invalid`);
  }
  return number;
}

export function parseCommand(value: unknown): CommandInput {
  const input = record(value);
  const commandId = requiredString(input.commandId, "Command id", 100);
  const command = requiredString(input.command, "Command", 40);
  if (
    !EVENT_COMMANDS.has(command) &&
    !REMINDER_COMMANDS.has(command) &&
    !COMPLETION_COMMANDS.has(command)
  ) {
    throw new HttpsError("invalid-argument", "Unknown command");
  }
  const payload = record(input.payload ?? {});
  const occurredAt =
    input.occurredAt === undefined ?
      Date.now() :
      requiredNumber(input.occurredAt, "Occurred at");
  return {commandId, command, payload, occurredAt};
}

export function validatePayload(
  command: string,
  payload: JsonRecord
): void {
  if (REMINDER_COMMANDS.has(command)) {
    requiredString(payload.id, "Reminder id", 100);
    if (command === "REMINDER_UPSERT") {
      requiredString(payload.title, "Reminder title");
    }
    return;
  }
  if (COMPLETION_COMMANDS.has(command)) {
    requiredString(payload.reminderId, "Reminder id", 100);
    requiredNumber(payload.scheduledEpochDay, "Scheduled day");
    return;
  }
  requiredString(payload.remoteId, "Event id", 100);
  if (command !== "STOP" && command !== "DELETE") {
    requiredString(payload.type, "Event type", 20);
    requiredString(payload.detail, "Event detail", 100);
    requiredNumber(payload.startedAt, "Started at");
  }
}
