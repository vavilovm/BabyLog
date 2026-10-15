/** Compute active ownership inside the household transaction, preserving competing records. */
export function sleepWriteState(
  activeSleepId: unknown,
  remoteId: unknown,
  endedAt: unknown,
  deletedAt: unknown
): {conflict: boolean; pointer: unknown | undefined} {
  const active = endedAt == null && deletedAt == null;
  const conflict = active && !!activeSleepId && activeSleepId !== remoteId;
  if (conflict) return {conflict: true, pointer: undefined};
  if (active) return {conflict: false, pointer: remoteId};
  return {conflict: false, pointer: activeSleepId === remoteId ? null : undefined};
}
