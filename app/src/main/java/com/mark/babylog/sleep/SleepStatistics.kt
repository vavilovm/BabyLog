package com.mark.babylog.sleep

import com.mark.babylog.data.BabyEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SleepDayStatistics(
    val day: LocalDate,
    val daytimeMs: Long,
    val nighttimeMs: Long,
    val naps: Int,
    val longestMs: Long,
    val averageNapMs: Long?,
    val hasRecords: Boolean,
) {
    val totalMs: Long
        get() = daytimeMs + nighttimeMs
}

/**
 * Calendar-day totals, classifying each sleep by its local start (06:00–17:59 = nap). Overnight
 * sessions retain their night classification across midnight. Day boundaries use the actual zone so
 * DST days are 23/25 hours. Callers supply validated sessions.
 */
fun sleepDayStatistics(
    sessions: List<BabyEvent>,
    day: LocalDate,
    now: Long,
    zone: ZoneId,
): SleepDayStatistics {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
    var daytime = 0L
    var nighttime = 0L
    var longest = 0L
    val naps = mutableListOf<Long>()
    sessions.forEach { session ->
        val until = minOf(session.endedAt ?: now, now)
        val clipped = (minOf(until, end) - maxOf(session.startedAt, start)).coerceAtLeast(0)
        if (clipped > 0) {
            val localStart = Instant.ofEpochMilli(session.startedAt).atZone(zone)
            if (localStart.hour in 6..17) {
                daytime += clipped
                if (localStart.toLocalDate() == day) naps += clipped
            } else nighttime += clipped
            longest = maxOf(longest, clipped)
        }
    }
    return SleepDayStatistics(
        day,
        daytime,
        nighttime,
        naps.size,
        longest,
        naps.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        longest > 0,
    )
}

data class SleepPeriodAverage(val days: Int, val daytimeMs: Long, val nighttimeMs: Long) {
    val totalMs: Long
        get() = daytimeMs + nighttimeMs
}

/** Missing logs aren't measured zero sleep; incomplete today never lowers the average. */
fun sleepPeriodAverage(days: List<SleepDayStatistics>, today: LocalDate): SleepPeriodAverage? {
    val recorded = days.filter { it.day < today && it.hasRecords }
    if (recorded.isEmpty()) return null
    return SleepPeriodAverage(
        recorded.size,
        recorded.sumOf { it.daytimeMs } / recorded.size,
        recorded.sumOf { it.nighttimeMs } / recorded.size,
    )
}
