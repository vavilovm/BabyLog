package com.mark.babylog.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Civil day boundaries with monotonic elapsed-time coordinates, including 23/25-hour days. */
internal class SleepDayAxis(val day: LocalDate, val zone: ZoneId) {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val duration = end - start
    val changesOffset =
        zone.rules.getOffset(Instant.ofEpochMilli(start)) !=
            zone.rules.getOffset(Instant.ofEpochMilli(end - 1))

    fun fraction(time: Long): Float =
        ((time.coerceIn(start, end) - start).toDouble() / duration).toFloat()

    fun timeAt(fraction: Float): Long = start + (duration * fraction).toLong()
}

/** Preserve the selected occurrence when editing a repeated hour. Gaps advance to valid time. */
internal fun resolveSleepLocalTime(
    local: java.time.LocalDateTime,
    zone: ZoneId,
    preferredOffset: java.time.ZoneOffset,
): Long = java.time.ZonedDateTime.ofLocal(local, zone, preferredOffset).toInstant().toEpochMilli()
