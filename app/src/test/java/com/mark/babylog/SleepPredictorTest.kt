package com.mark.babylog

import com.mark.babylog.data.*
import com.mark.babylog.sleep.SleepPredictor
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SleepPredictorTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val base =
        LocalDate.of(2026, 10, 1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun session(start: Long, length: Int = 60) =
        BabyEvent(
            type = EventType.SLEEP,
            detail = "BACK",
            startedAt = start,
            endedAt = start + length * minute,
            sleepRecordVersion = 1,
        )

    private fun history(days: Int, wakeMinutes: Int = 90): List<BabyEvent> =
        (0 until days).flatMap { day ->
            (0..3).map { nap ->
                session(base + day * 86_400_000L + nap * (wakeMinutes + 60) * minute)
            }
        }

    @Test
    fun noHistoryAndLegacyMarksDoNotInventPredictions() {
        assertNull(SleepPredictor.calculate(emptyList(), base, zone).next)
        assertNull(
            SleepPredictor.calculate(
                    listOf(session(base).copy(sleepRecordVersion = 0)),
                    base + 100 * minute,
                    zone,
                )
                .next
        )
    }

    @Test
    fun learnsPersonalIntervalsAndAnchorsToLatestWakeImmediately() {
        val events = history(3)
        val wake = events.last().endedAt!!
        val result = SleepPredictor.calculate(events, wake, zone).next!!
        assertEquals(90.0, (result.expected - wake).toDouble() / minute, 0.1)
        val changed = events.dropLast(1) + events.last().copy(endedAt = wake + 10 * minute)
        assertEquals(
            10 * minute,
            SleepPredictor.calculate(changed, wake + 10 * minute, zone).next!!.expected -
                result.expected,
        )
    }

    @Test
    fun activeSleepAndOverlapsAreNotUsedForTraining() {
        val events = history(2)
        val last = events.last()
        assertNull(
            SleepPredictor.calculate(
                    events.dropLast(1) + last.copy(endedAt = null),
                    last.startedAt + 30 * minute,
                    zone,
                )
                .next
        )
        val overlap = session(last.startedAt + minute)
        val result = SleepPredictor.calculate(events + overlap, overlap.endedAt!!, zone)
        assertTrue(result.conflicts.containsAll(listOf(last.remoteId, overlap.remoteId)))
        assertFalse(
            SleepPredictor.validSessions(events + overlap, overlap.endedAt!!).any {
                it.remoteId == last.remoteId
            }
        )
    }

    @Test
    fun oldPredictionDoesNotMoveForwardWithClock() {
        val events = history(3)
        val wake = events.last().endedAt!!
        val early = SleepPredictor.calculate(events, wake, zone).next!!
        val late = SleepPredictor.calculate(events, wake + 200 * minute, zone).next!!
        assertEquals(early.expected, late.expected)
        assertTrue(late.latest < wake + 200 * minute)
    }

    @Test
    fun futureDataIsExcludedAndBacktestFindsStableRhythm() {
        val events = history(18)
        val now = events.last().endedAt!!
        val before = SleepPredictor.calculate(events, now, zone).next!!
        val after = SleepPredictor.calculate(events + session(now + 500 * minute), now, zone).next!!
        assertEquals(before.expected, after.expected)
        assertEquals(0.0, before.medianErrorMinutes!!, 0.01)
        assertFalse(before.preliminary)
    }

    @Test
    fun newestDaysOutweighOlderRoutine() {
        val old = history(15)
        val recentBase = base + 15 * 86_400_000L
        val recent =
            (0..9).flatMap { day ->
                (0..3).map { nap -> session(recentBase + day * 86_400_000L + nap * 180 * minute) }
            }
        val events = old + recent
        val wake = events.last().endedAt!!
        val next = SleepPredictor.calculate(events, wake, zone).next!!
        assertTrue((next.expected - wake) / minute > 105)
    }

    @Test
    fun ongoingNapUsesDaytimeDurationsAndShowsWakeWindow() {
        val start = base + 10 * 86_400_000L
        val active = session(start).copy(endedAt = null)
        val naps = (1..5).map { session(start - it * 86_400_000L, 60) }
        val nights = (1..5).map { session(start - it * 86_400_000L + 14 * 60 * minute, 480) }
        val result = SleepPredictor.calculate(naps + nights + active, start + 10 * minute, zone)
        val current = result.current!!
        assertNull(result.next)
        assertFalse(current.night)
        assertEquals(5, current.samples)
        assertEquals(start + 60 * minute, current.expectedWake)
        assertTrue(current.earliestWake < current.expectedWake)
        assertTrue(current.latestWake > current.expectedWake)
    }

    @Test
    fun ongoingNightPredictsAcrossMidnightAndDoesNotSlideWithClock() {
        val start = base + 10 * 86_400_000L + 14 * 60 * minute
        val active = session(start).copy(endedAt = null)
        val nights = (1..5).map { session(start - it * 86_400_000L, 480) }
        val early = SleepPredictor.calculate(nights + active, start, zone).current!!
        val late = SleepPredictor.calculate(nights + active, start + 600 * minute, zone).current!!
        assertTrue(early.night)
        assertEquals(start + 480 * minute, early.expectedWake)
        assertEquals(early, late)
    }

    @Test
    fun overnightWindowDoesNotSpanShortNightEpisodesOrAddThirtyPercent() {
        val start = base + 10 * 86_400_000L + 14 * 60 * minute
        val active = session(start).copy(endedAt = null)
        val nights = (1..5).map { session(start - it * 86_400_000L, 660) }
        val short = (6..7).map { session(start - it * 86_400_000L, 30) }
        val now = start + 60 * minute
        val result = SleepPredictor.calculate(nights + short + active, now, zone)
        val estimate = result.current!!
        assertEquals(start + 660 * minute, estimate.expectedWake)
        assertEquals(60 * minute, estimate.latestWake - estimate.earliestWake)
        assertEquals(5, estimate.samples)
        assertEquals(now, result.forecast.single().start)
        assertEquals(estimate.expectedWake, result.forecast.single().end)
        assertTrue(
            SleepPredictor.calculate(nights + short + active, start + 720 * minute, zone)
                .forecast
                .isEmpty()
        )
    }

    @Test
    fun shortNightEpisodesKeepTheirOwnDurationMode() {
        val start = base + 10 * 86_400_000L + 14 * 60 * minute
        val active = session(start).copy(endedAt = null)
        val short = (1..5).map { session(start - it * 86_400_000L, 45) }
        val long = session(start - 6 * 86_400_000L, 660)
        assertEquals(
            start + 45 * minute,
            SleepPredictor.calculate(short + long + active, start, zone).current!!.expectedWake,
        )
    }

    @Test
    fun durationEstimateNeedsMatchingRecentValidHistory() {
        val start = base + 40 * 86_400_000L
        val active = session(start).copy(endedAt = null)
        val excluded =
            listOf(
                session(base), // Older than 28 days.
                session(start - 86_400_000L + 14 * 60 * minute, 480), // Night, not nap.
                session(start - 2 * 86_400_000L).copy(sleepRecordVersion = 0),
                session(start - 3 * 86_400_000L).copy(syncState = SyncState.CONFLICT),
                session(start - 4 * 86_400_000L).copy(deletedAt = start),
                session(start + 2 * 86_400_000L),
            )
        assertNull(SleepPredictor.calculate(excluded + active, start, zone).current)
        val valid = session(start - 5 * 86_400_000L, 45)
        val result = SleepPredictor.calculate(excluded + valid + active, start, zone).current!!
        assertEquals(1, result.samples)
        assertEquals(start + 45 * minute, result.expectedWake)
    }

    @Test
    fun nightDurationUsesElapsedTimeAcrossDst() {
        val historicalStart =
            ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
        val historicalEnd =
            ZonedDateTime.of(2026, 10, 25, 3, 30, 0, 0, zone).toInstant().toEpochMilli()
        val start = ZonedDateTime.of(2026, 10, 26, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
        val active = session(start).copy(endedAt = null)
        val result =
            SleepPredictor.calculate(
                    listOf(session(historicalStart).copy(endedAt = historicalEnd), active),
                    start,
                    zone,
                )
                .current!!
        assertEquals(start + 180 * minute, result.expectedWake)
    }

    @Test
    fun ongoingDurationExcludesOverlappingRecords() {
        val start = base + 10 * 86_400_000L
        val prior = session(start - 86_400_000L)
        val overlap = session(prior.startedAt + minute)
        val active = session(start).copy(endedAt = null)
        assertNull(SleepPredictor.calculate(listOf(prior, overlap, active), start, zone).current)
    }

    @Test
    fun ageUsesDueDateAndNeverGoesNegative() {
        val profile = BabyProfile(birthday = "2026-07-01", dueDate = "2026-08-01")
        assertEquals(0, SleepPredictor.correctedAgeDays(profile, LocalDate.of(2026, 7, 15)).toInt())
        assertEquals(31, SleepPredictor.correctedAgeDays(profile, LocalDate.of(2026, 9, 1)).toInt())
    }

    @Test
    fun dstCrossingUsesRealElapsedDuration() {
        val start = ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 10, 25, 3, 30, 0, 0, zone).toInstant().toEpochMilli()
        val event = session(start).copy(endedAt = end)
        assertEquals(
            180,
            ((SleepPredictor.validSessions(listOf(event), end).single().endedAt!! - start) / minute)
                .toInt(),
        )
    }
}
