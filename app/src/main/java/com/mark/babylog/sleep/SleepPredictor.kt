package com.mark.babylog.sleep

import com.mark.babylog.data.*
import java.time.*
import kotlin.math.*

private const val MINUTE = 60_000L
private const val DAY = 86_400_000L

data class SleepEstimate(
    val earliest: Long,
    val expected: Long,
    val latest: Long,
    val samples: Int,
    val model: String,
    val medianErrorMinutes: Double?,
    val coverage: Double?,
    val preliminary: Boolean,
)

data class ForecastSleep(val start: Long, val end: Long, val uncertaintyMinutes: Int)

data class CurrentSleepEstimate(
    val startedAt: Long,
    val earliestWake: Long,
    val expectedWake: Long,
    val latestWake: Long,
    val samples: Int,
    val night: Boolean,
)

data class SleepPrediction(
    val next: SleepEstimate?,
    val forecast: List<ForecastSleep>,
    val conflicts: Set<String>,
    val current: CurrentSleepEstimate? = null,
)

/** Pure, deterministic model. Every backtest fits exclusively on preceding observations. */
object SleepPredictor {
    private data class Sample(
        val wake: Long,
        val onset: Long,
        val features: DoubleArray,
        val phase: Int,
    ) {
        val minutes
            get() = (onset - wake).toDouble() / MINUTE
    }

    fun correctedAgeDays(profile: BabyProfile, today: LocalDate): Long {
        val birth = LocalDate.parse(profile.birthday)
        val due = profile.dueDate?.let(LocalDate::parse)
        return java.time.temporal.ChronoUnit.DAYS.between(
                if (due != null && due > birth) due else birth,
                today,
            )
            .coerceAtLeast(0)
    }

    fun validSessions(events: List<BabyEvent>, now: Long): List<BabyEvent> {
        val timed =
            events.filter {
                it.type == EventType.SLEEP && it.sleepRecordVersion == 1 && it.deletedAt == null
            }
        return timed
            .filter { e ->
                e.syncState != SyncState.CONFLICT &&
                    e.startedAt <= now &&
                    (e.endedAt == null || (e.endedAt > e.startedAt && e.endedAt <= now)) &&
                    timed.none { other ->
                        other.remoteId != e.remoteId &&
                            other.startedAt < (e.endedAt ?: now) &&
                            (other.endedAt ?: now) > e.startedAt
                    }
            }
            .sortedBy { it.startedAt }
    }

    private fun features(
        wake: Long,
        prior: BabyEvent,
        history: List<BabyEvent>,
        zone: ZoneId,
    ): DoubleArray {
        val time = Instant.ofEpochMilli(wake).atZone(zone)
        val angle = (time.hour + time.minute / 60.0) * PI / 12
        val dayStart = time.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val naps = history.count { it.startedAt >= dayStart && it.startedAt < wake }
        val total =
            history
                .sumOf { e ->
                    ((e.endedAt ?: e.startedAt).coerceAtMost(wake) -
                            e.startedAt.coerceAtLeast(wake - DAY))
                        .coerceAtLeast(0)
                }
                .toDouble() / DAY
        return doubleArrayOf(
            1.0,
            sin(angle),
            cos(angle),
            ((prior.endedAt ?: wake) - prior.startedAt).toDouble() / DAY,
            naps / 5.0,
            total,
        )
    }

    private fun phase(wake: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(wake).atZone(zone).hour.let {
            if (it < 6) 2 else if (it >= 16) 1 else 0
        }

    private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
        val sorted = values.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var weight = 0.0
        return sorted
            .firstOrNull {
                weight += it.second
                weight >= half
            }
            ?.first ?: sorted.last().first
    }

    private fun weight(sample: Sample, at: Long) =
        exp(-ln(2.0) * (at - sample.onset).coerceAtLeast(0).toDouble() / (7 * DAY))

    private fun predict(train: List<Sample>, x: DoubleArray, at: Long, model: Int): Double {
        val median = weightedMedian(train.map { it.minutes to weight(it, at) })
        if (model == 0) return median
        if (model == 1)
            return weightedMedian(
                train.map {
                    val similarity = exp(2.0 * (it.features[1] * x[1] + it.features[2] * x[2] - 1))
                    it.minutes to weight(it, at) * similarity
                }
            )
        if (train.size < 30) return median
        // Ridge shrinkage around the robust personal baseline; scaled inputs keep regularisation
        // stable.
        val size = x.size
        val a = Array(size) { DoubleArray(size + 1) }
        train.forEach { s ->
            val w = weight(s, at)
            for (i in 0 until size) {
                for (j in 0 until size) a[i][j] += w * s.features[i] * s.features[j]
                a[i][size] += w * s.features[i] * (s.minutes - median)
            }
        }
        for (i in 0 until size) a[i][i] += if (i == 0) 0.1 else 4.0
        for (i in 0 until size) {
            val pivot = (i until size).maxBy { abs(a[it][i]) }
            val swap = a[i]
            a[i] = a[pivot]
            a[pivot] = swap
            if (abs(a[i][i]) < 1e-9) return median
            val divisor = a[i][i]
            for (j in i..size) a[i][j] /= divisor
            for (k in 0 until size) if (k != i) {
                val factor = a[k][i]
                for (j in i..size) a[k][j] -= factor * a[i][j]
            }
        }
        return (median + x.indices.sumOf { x[it] * a[it][size] }).coerceIn(5.0, 480.0)
    }

    private fun isNight(start: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(start).atZone(zone).hour.let { it >= 18 || it < 5 }

    private fun currentSleep(
        active: BabyEvent,
        completed: List<BabyEvent>,
        zone: ZoneId,
    ): CurrentSleepEstimate? {
        val night = isNight(active.startedAt, zone)
        val hour = Instant.ofEpochMilli(active.startedAt).atZone(zone).toLocalTime()
        val angle = (hour.hour + hour.minute / 60.0) * PI / 12
        val durations =
            completed
                .filter {
                    it.endedAt!! <= active.startedAt &&
                        it.startedAt >= active.startedAt - 28 * DAY &&
                        isNight(it.startedAt, zone) == night
                }
                .map {
                    val time = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalTime()
                    val otherAngle = (time.hour + time.minute / 60.0) * PI / 12
                    val recency =
                        exp(-ln(2.0) * (active.startedAt - it.startedAt).toDouble() / (7 * DAY))
                    val similarity = exp(2.0 * (cos(angle - otherAngle) - 1))
                    (it.endedAt!! - it.startedAt).toDouble() / MINUTE to recency * similarity
                }
        if (durations.isEmpty()) return null
        fun quantile(fraction: Double): Double {
            var accumulated = 0.0
            val threshold = durations.sumOf { it.second } * fraction
            return durations
                .sortedBy { it.first }
                .first {
                    accumulated += it.second
                    accumulated >= threshold
                }
                .first
        }
        val expected = weightedMedian(durations)
        // Descriptive personal spread, deliberately broad; not a calibrated confidence interval.
        val margin = max(if (night) 60.0 else 15.0, expected * 0.3)
        return CurrentSleepEstimate(
            active.startedAt,
            active.startedAt +
                (min(quantile(0.2), expected - margin).coerceAtLeast(0.0) * MINUTE).toLong(),
            active.startedAt + (expected * MINUTE).toLong(),
            active.startedAt + (max(quantile(0.8), expected + margin) * MINUTE).toLong(),
            durations.size,
            night,
        )
    }

    fun calculate(
        events: List<BabyEvent>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        profile: BabyProfile? = null,
    ): SleepPrediction {
        val ageDays =
            profile?.let {
                correctedAgeDays(it, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
            }
        val sessions = validSessions(events, now)
        val validIds = sessions.map { it.remoteId }.toSet()
        val conflicts =
            events
                .filter {
                    it.sleepRecordVersion == 1 && it.deletedAt == null && it.remoteId !in validIds
                }
                .map { it.remoteId }
                .toSet()
        val completed = sessions.filter { it.endedAt != null }
        sessions
            .firstOrNull { it.endedAt == null }
            ?.let { active ->
                return SleepPrediction(
                    null,
                    emptyList(),
                    conflicts,
                    currentSleep(active, completed, zone),
                )
            }
        val latest = completed.lastOrNull() ?: return SleepPrediction(null, emptyList(), conflicts)
        val samples =
            completed.zipWithNext().mapNotNull { (prior, next) ->
                val wake = prior.endedAt!!
                val gap = (next.startedAt - wake) / MINUTE
                // Never bridge an unresolved/legacy record or a long unobserved gap.
                val interrupted =
                    events.any {
                        it.remoteId !in setOf(prior.remoteId, next.remoteId) &&
                            it.startedAt in wake..next.startedAt
                    }
                if (gap !in 5..480 || next.startedAt < now - 28 * DAY || interrupted) null
                else
                    Sample(
                        wake,
                        next.startedAt,
                        features(
                            wake,
                            prior,
                            completed.filter { it.startedAt <= prior.startedAt },
                            zone,
                        ),
                        phase(wake, zone),
                    )
            }
        val wake = latest.endedAt!!
        if (now - wake > 12 * 60 * MINUTE) return SleepPrediction(null, emptyList(), conflicts)
        val matched = samples.filter { it.phase == phase(wake, zone) }
        val pool =
            if (matched.size >= 3) matched
            else samples.filter { (it.phase == 2) == (phase(wake, zone) == 2) }
        val borrowedContext = matched.size < 3
        if (pool.isEmpty()) return SleepPrediction(null, emptyList(), conflicts)
        val x = features(wake, latest, completed, zone)
        val errors = Array(3) { mutableListOf<Double>() }
        // Use a fixed chronological holdout subset shared by eligible candidate models.
        val first = if (pool.size >= 38) 30 else 3
        for (i in first until pool.size) {
            val train = pool.take(i)
            for (model in 0..2) errors[model] +=
                abs(predict(train, pool[i].features, pool[i].wake, model) - pool[i].minutes)
        }
        fun mae(model: Int) =
            errors[model].takeIf { it.isNotEmpty() }?.average() ?: Double.POSITIVE_INFINITY
        var selected = 0
        if (errors[1].size >= 8 && mae(1) < mae(0) * 0.95) selected = 1
        if (pool.size >= 38 && mae(2) < mae(selected) * 0.90) selected = 2
        val estimate = predict(pool, x, now, selected)
        val residuals = errors[selected].sorted()
        val calibrated = residuals.size >= 10 && !borrowedContext
        val radius =
            if (calibrated)
                residuals[
                        (ceil((residuals.size + 1) * 0.8).toInt() - 1).coerceIn(residuals.indices)]
                    .coerceAtLeast(10.0)
            else max(if (ageDays != null && ageDays < 120) 45.0 else 30.0, estimate * 0.35)
        val expected = wake + (estimate * MINUTE).toLong()
        val coverageResults =
            (10 until residuals.size).map { index ->
                val preceding = errors[selected].take(index).sorted()
                val q =
                    preceding[
                            (ceil((preceding.size + 1) * 0.8).toInt() - 1).coerceIn(
                                preceding.indices
                            )]
                        .coerceAtLeast(10.0)
                if (errors[selected][index] <= q) 1.0 else 0.0
            }
        val result =
            SleepEstimate(
                wake + (max(5.0, estimate - radius) * MINUTE).toLong(),
                expected,
                wake + ((estimate + radius) * MINUTE).toLong(),
                pool.size,
                listOf(
                    "Недавние периоды бодрствования",
                    "Личный ритм по времени суток",
                    "Личная адаптивная модель",
                )[selected],
                residuals.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
                coverageResults.takeIf { it.isNotEmpty() }?.average(),
                !calibrated,
            )
        val forecast = mutableListOf<ForecastSleep>()
        // A passed estimate is kept visible, never silently shifted into the future.
        if (expected >= now && completed.size >= 4) {
            val dayEnd =
                Instant.ofEpochMilli(now)
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            var start = expected
            val history = completed.toMutableList()
            repeat(8) { index ->
                if (start >= dayEnd) return@repeat
                val hour = Instant.ofEpochMilli(start).atZone(zone).hour
                val durations =
                    completed
                        .filter { e ->
                            val h = Instant.ofEpochMilli(e.startedAt).atZone(zone).hour
                            (h >= 18 || h < 5) == (hour >= 18 || hour < 5)
                        }
                        .map {
                            (it.endedAt!! - it.startedAt).toDouble() / MINUTE to
                                exp(-ln(2.0) * (now - it.startedAt).toDouble() / (7 * DAY))
                        }
                if (durations.isEmpty()) return@repeat
                val duration = weightedMedian(durations).coerceAtLeast(5.0)
                val end = start + (duration * MINUTE).toLong()
                forecast += ForecastSleep(start, end, (radius * sqrt(index + 1.0)).toInt())
                val synthetic =
                    BabyEvent(
                        type = EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        endedAt = end,
                        sleepRecordVersion = 1,
                    )
                history += synthetic
                val candidates = samples.filter { it.phase == phase(end, zone) }
                if (candidates.isEmpty()) {
                    start = dayEnd
                    return@repeat
                }
                start =
                    end +
                        (predict(candidates, features(end, synthetic, history, zone), now, 1) *
                                MINUTE)
                            .toLong()
            }
        }
        return SleepPrediction(result, forecast, conflicts)
    }
}
