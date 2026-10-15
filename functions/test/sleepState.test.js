const test = require("node:test");
const assert = require("node:assert/strict");
const {sleepWriteState} = require("../lib/sleepState.js");

test("first start owns the household timer and repeated start preserves it", () => {
  assert.deepEqual(sleepWriteState(null, "a", null, null), {conflict: false, pointer: "a"});
  assert.deepEqual(sleepWriteState("a", "a", null, null), {conflict: false, pointer: "a"});
});
test("a competing offline start is preserved as a conflict without replacing the owner", () => {
  assert.deepEqual(sleepWriteState("a", "b", null, null), {conflict: true, pointer: undefined});
});
test("closing the owner releases the timer, closing another record cannot release it", () => {
  assert.deepEqual(sleepWriteState("a", "a", 2000, null), {conflict: false, pointer: null});
  assert.deepEqual(sleepWriteState("a", "b", 2000, null), {conflict: false, pointer: undefined});
});
test("deleting the owning record releases its timer", () => {
  assert.deepEqual(sleepWriteState("a", "a", null, 3000), {conflict: false, pointer: null});
});
