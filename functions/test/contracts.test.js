const test = require("node:test");
const assert = require("node:assert/strict");
const {
  parseCommand,
  requiredString,
  validatePayload,
} = require("../lib/contracts.js");

test("normalizes a valid legacy event command", () => {
  const value = parseCommand({
    commandId: "command-1",
    command: "LOG_BOTTLE",
    occurredAt: 123,
    payload: {
      remoteId: "event-1",
      type: "FEEDING",
      detail: "LEFT",
      startedAt: 123,
    },
  });
  validatePayload(value.command, value.payload);
  assert.equal(value.commandId, "command-1");
  assert.equal(value.occurredAt, 123);
});

test("rejects unknown commands and incomplete payloads", () => {
  assert.throws(
    () => parseCommand({commandId: "1", command: "DROP", payload: {}}),
    /Unknown command/
  );
  assert.throws(
    () => validatePayload("LOG_BOTTLE", {remoteId: "event-1"}),
    /Event type is invalid/
  );
});

test("trims and bounds user-controlled strings", () => {
  assert.equal(requiredString("  Мама  ", "Display name", 80), "Мама");
  assert.throws(() => requiredString("", "Display name"), /invalid/);
  assert.throws(() => requiredString("x".repeat(81), "Display name", 80), /invalid/);
});

test("versioned sleep commands preserve active and completed intervals", () => {
  const payload = {remoteId: "sleep-1", type: "SLEEP", detail: "BACK", sleepRecordVersion: 1, startedAt: 1000, endedAt: null};
  const command = parseCommand({commandId: "sleep-command", command: "SLEEP_START_V1", payload});
  validatePayload(command.command, command.payload);
  validatePayload("SLEEP_SAVE_V1", {...payload, endedAt: 2000});
  assert.throws(() => validatePayload("SLEEP_START_V1", {...payload, endedAt: 2000}), /Invalid timed sleep/);
  assert.throws(() => validatePayload("SLEEP_SAVE_V1", {...payload, endedAt: 500}), /Invalid timed sleep/);
  assert.throws(() => validatePayload("SLEEP_SAVE_V1", {...payload, sleepRecordVersion: 0}), /Invalid timed sleep/);
});

test("birthday profile rejects impossible and reversed dates", () => {
  validatePayload("PROFILE_UPSERT", {birthday: "2026-07-01", dueDate: "2026-08-01", updatedAt: 10});
  for (const birthday of ["2026-02-30", "tomorrow", "2999-01-01"]) {
    assert.throws(() => validatePayload("PROFILE_UPSERT", {birthday, updatedAt: 10}), /Invalid birthday/);
  }
  assert.throws(() => validatePayload("PROFILE_UPSERT", {birthday: "2026-07-01", dueDate: "2026-06-01", updatedAt: 10}), /Invalid birthday/);
});
