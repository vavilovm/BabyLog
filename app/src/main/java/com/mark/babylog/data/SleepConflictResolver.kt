package com.mark.babylog.data

/** Only merge a whole overlap component when every record shares one sleep window. */
internal object SleepConflictResolver {
    data class Resolution(val merged: BabyEvent, val duplicates: List<BabyEvent>)

    fun project(events: List<BabyEvent>, now: Long = System.currentTimeMillis()): List<BabyEvent> {
        val resolutions = resolve(events, now)
        val hidden = resolutions.flatMap { it.duplicates }.map { it.remoteId }.toSet()
        val merged =
            resolutions.associate { resolution ->
                val sources = listOf(resolution.merged) + resolution.duplicates
                val state =
                    when {
                        sources.any { it.syncState == SyncState.PENDING } -> SyncState.PENDING
                        sources.any { it.syncState == SyncState.FAILED } -> SyncState.FAILED
                        sources.all { it.syncState == SyncState.LOCAL_ONLY } -> SyncState.LOCAL_ONLY
                        else -> SyncState.SYNCED
                    }
                resolution.merged.remoteId to resolution.merged.copy(syncState = state)
            }
        val projected =
            events
                .filter { it.remoteId !in hidden }
                .map { merged[it.remoteId] ?: it }
                .sortedBy { it.startedAt }
        // The server's active pointer can still name an older offline start.
        // Determine remaining conflicts from the actual windows after merging.
        return projected.map { event ->
            if (
                event.type != EventType.SLEEP ||
                    event.sleepRecordVersion != 1 ||
                    event.deletedAt != null
            )
                event
            else if (
                projected.any { other ->
                    other.remoteId != event.remoteId &&
                        other.type == EventType.SLEEP &&
                        other.sleepRecordVersion == 1 &&
                        other.deletedAt == null &&
                        other.startedAt < (event.endedAt ?: Long.MAX_VALUE) &&
                        (other.endedAt ?: Long.MAX_VALUE) > event.startedAt
                }
            )
                event.copy(syncState = SyncState.CONFLICT)
            else if (event.syncState == SyncState.CONFLICT) event.copy(syncState = SyncState.SYNCED)
            else event
        }
    }

    fun resolve(events: List<BabyEvent>, now: Long): List<Resolution> {
        val timed =
            events
                .filter {
                    it.type == EventType.SLEEP &&
                        it.sleepRecordVersion == 1 &&
                        it.deletedAt == null &&
                        it.startedAt <= now &&
                        (it.endedAt == null || it.endedAt > it.startedAt && it.endedAt <= now)
                }
                .sortedWith(compareBy<BabyEvent> { it.startedAt }.thenBy { it.remoteId })
        // A known wake also bounds older, still-open offline starts. They must
        // not bridge the following nap after another caregiver has logged a wake.
        val wakes = timed.mapNotNull { it.endedAt }.sorted()
        val components = mutableListOf<MutableList<BabyEvent>>()
        var end = Long.MIN_VALUE
        for (event in timed) {
            val effectiveEnd =
                event.endedAt ?: wakes.firstOrNull { it > event.startedAt } ?: Long.MAX_VALUE
            if (components.isEmpty() || event.startedAt >= end) {
                components.add(mutableListOf(event))
                end = effectiveEnd
            } else {
                components.last().add(event)
                end = maxOf(end, effectiveEnd)
            }
        }
        return components.mapNotNull { group ->
            if (group.size < 2) return@mapNotNull null
            val earliestEnd = group.mapNotNull { it.endedAt }.minOrNull()
            if (earliestEnd != null && group.maxOf { it.startedAt } >= earliestEnd)
                return@mapNotNull null
            // Stable identity across devices, even if timestamps are subsequently edited.
            val owner = group.minBy { it.remoteId }
            Resolution(
                owner.copy(startedAt = group.minOf { it.startedAt }, endedAt = earliestEnd),
                group.filter { it.remoteId != owner.remoteId },
            )
        }
    }
}
