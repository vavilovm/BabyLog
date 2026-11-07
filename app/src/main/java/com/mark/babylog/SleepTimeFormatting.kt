package com.mark.babylog

import java.time.*
import java.time.format.DateTimeFormatter

private val sleepClockFormat = DateTimeFormatter.ofPattern("HH:mm")
internal val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

internal fun clock(t: Long, zone: ZoneId): String {
    val local = Instant.ofEpochMilli(t).atZone(zone)
    val repeated = zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
    return local.format(sleepClockFormat) + if (repeated) " UTC${local.offset}" else ""
}

internal fun graphClock(time: Long, zone: ZoneId, showOffset: Boolean): String =
    Instant.ofEpochMilli(time).atZone(zone).let { local ->
        if (showOffset) local.format(sleepClockFormat) + " UTC${local.offset}"
        else clock(time, zone)
    }

internal fun wakeClock(time: Long, now: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return clock(time, zone) +
        when (date) {
            today -> ""
            today.plusDays(1) -> " завтра"
            else -> " ${date.format(DateTimeFormatter.ofPattern("dd.MM"))}"
        }
}

internal fun duration(ms: Long): String {
    val m = (ms.coerceAtLeast(0) / 60_000)
    return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
}
