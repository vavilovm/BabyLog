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
