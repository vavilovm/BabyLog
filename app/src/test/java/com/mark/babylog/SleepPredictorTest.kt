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

    private fun fullRhythm(days: Int): List<BabyEvent> =
        (0 until days).flatMap { day ->
            val morning = base + day * 86_400_000L
            listOf(120 to 60, 300 to 60, 480 to 60, 660 to 30, 840 to 600).map { (offset, length) ->
                session(morning + offset * minute, length)
            }
        }

    @Test
    fun expiredNextEstimateKeepsProjectedRhythmWithoutSlidingItsAnchor() {
        val events = fullRhythm(10)
        val wake = events.last().endedAt!!
        val initial = SleepPredictor.calculate(events, wake, zone)
        val later = SleepPredictor.calculate(events, wake + 16 * 60 * minute, zone)
        assertNotNull(initial.next)
        assertNotNull(later.next)
        assertEquals(initial.next!!.expected, later.next!!.expected)
        assertTrue(later.next!!.latest < wake + 16 * 60 * minute)
        assertTrue(later.forecast.any { it.end > wake + 16 * 60 * minute })
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
    fun newlyObservedPendingSleepIsValidWhileRealOverlapRemainsAConflict() {
        val startedAt = base + 500
        val active = session(startedAt).copy(endedAt = null, syncState = SyncState.PENDING)
        val observedAt = startedAt + 1
        val result = SleepPredictor.calculate(listOf(active), observedAt, zone)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(active, SleepPredictor.validSessions(listOf(active), observedAt).single())
        val previous = session(startedAt - 60_000).copy(endedAt = startedAt + 1)
        assertTrue(
            SleepPredictor.calculate(listOf(previous, active), observedAt, zone)
                .conflicts
                .containsAll(listOf(previous.remoteId, active.remoteId))
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
    fun lateBedtimeUsesOvernightMorningWakeDespiteMoreShortEveningSleeps() {
        val start = base + 10 * 86_400_000L + 16 * 60 * minute // 23:00; usual bedtime 21:00.
        val history =
            (1..5).flatMap { day ->
                val bedtime = start - day * 86_400_000L - 120 * minute
                listOf(
                    session(bedtime - 180 * minute, 30),
                    session(bedtime - 120 * minute, 30),
                    session(bedtime - 60 * minute, 30),
                    session(bedtime, 660),
                )
            }
        val current =
            SleepPredictor.calculate(history + session(start).copy(endedAt = null), start, zone)
                .current!!
        assertEquals(
            start + 540 * minute,
            current.expectedWake,
        ) // Still 08:00, not a 30-minute nap or 10:00.
        assertEquals(5, current.samples)
    }

    @Test
    fun upcomingNightProjectionUsesTheSameOvernightPattern() {
        val bedtime = base + 10 * 86_400_000L + 14 * 60 * minute // 21:00.
        val history =
            (1..5).flatMap { day ->
                val prior = bedtime - day * 86_400_000L
                listOf(
                    session(prior - 180 * minute, 30),
                    session(prior - 120 * minute, 30),
                    session(prior - 60 * minute, 30),
                    session(prior, 660),
                )
            }
        val today =
            listOf(
                session(bedtime - 180 * minute, 30),
                session(bedtime - 120 * minute, 30),
                session(bedtime - 60 * minute, 30),
            )
        val next =
            SleepPredictor.calculate(history + today, bedtime - 30 * minute, zone).forecast.first()
        assertEquals(bedtime, next.start)
        assertEquals(bedtime + 660 * minute, next.end)
    }

    @Test
    fun eveningSleepThatContinuesPastUsualBedtimeBecomesOvernight() {
        val start = base + 10 * 86_400_000L + 12 * 60 * minute // 19:00.
        val history =
            (1..5).flatMap { day ->
                val evening = start - day * 86_400_000L
                listOf(session(evening, 30), session(evening + 120 * minute, 660))
            }
        val events = history + session(start).copy(endedAt = null)
        assertEquals(
            start + 30 * minute,
            SleepPredictor.calculate(events, start, zone).current!!.expectedWake,
        )
        assertEquals(
            start + 780 * minute,
            SleepPredictor.calculate(events, start + 240 * minute, zone).current!!.expectedWake,
        )
        assertEquals(
            start + 780 * minute,
            SleepPredictor.calculate(events, start + 20 * 60 * minute, zone).current!!.expectedWake,
        )
    }

    @Test
    fun returnToSleepAfterOvernightWakeUsesShortEpisodePattern() {
        val start = base + 10 * 86_400_000L - 3 * 60 * minute // 04:00.
        val history =
            (1..5).flatMap { day ->
                val wake = start - day * 86_400_000L
                listOf(session(wake - 420 * minute, 360), session(wake, 45))
            }
        val sameNight = session(start - 420 * minute, 360)
        val current =
            SleepPredictor.calculate(
                    history + sameNight + session(start).copy(endedAt = null),
                    start,
                    zone,
                )
                .current!!
        assertEquals(start + 45 * minute, current.expectedWake)
    }

    @Test
    fun shortNightEpisodesKeepTheirOwnDurationMode() {
        val start = base + 10 * 86_400_000L + 14 * 60 * minute
        val active = session(start).copy(endedAt = null)
        val short = (1..5).map { session(start - it * 86_400_000L, 45) }
        val long = session(start - 6 * 86_400_000L + 120 * minute, 660)
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
    fun overnightWakeKeepsRealRhythmAcrossDst() {
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
        assertEquals(start + 120 * minute, result.expectedWake)
    }

    @Test
    fun activeSleepKeepsLaterProjectionsThroughTomorrowWithoutMovingWakeEstimate() {
        val events = fullRhythm(10)
        val start = base + 10 * 86_400_000L + 120 * minute
        val active = session(start).copy(endedAt = null)
        val now = start + 10 * minute
        val result = SleepPredictor.calculate(events + active, now, zone)
        val current = result.current!!
        assertNull(result.next)
        assertEquals(start + 60 * minute, current.expectedWake)
        assertEquals(now, result.forecast.first().start)
        assertEquals(current.expectedWake, result.forecast.first().end)
        assertTrue(result.forecast.size > 1)
        val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        assertTrue(
            result.forecast.any {
                Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == tomorrow
            }
        )
        result.forecast.zipWithNext().forEach { (a, b) ->
            assertTrue(b.start > a.end)
            assertTrue(b.uncertaintyMinutes >= a.uncertaintyMinutes)
        }
        assertEquals(current, SleepPredictor.calculate(events + active, now + minute, zone).current)
        val overdue = SleepPredictor.calculate(events + active, start + 120 * minute, zone)
        assertEquals(current, overdue.current)
        assertTrue(overdue.forecast.any { it.end > start + 120 * minute })
        assertEquals(result.forecast.drop(1), overdue.forecast)
    }

    @Test
    fun awakeForecastIncludesTomorrowAndRetainsCalibratedNextEstimate() {
        val events = history(18)
        val wake = events.last().endedAt!!
        val result = SleepPredictor.calculate(events, wake, zone)
        assertEquals(wake + 90 * minute, result.next!!.expected)
        assertEquals(0.0, result.next!!.medianErrorMinutes!!, 0.01)
        assertFalse(result.next!!.preliminary)
        val tomorrow = Instant.ofEpochMilli(wake).atZone(zone).toLocalDate().plusDays(1)
        val completeHistory = fullRhythm(18)
        val fullWake = completeHistory.last().endedAt!!
        val fullResult = SleepPredictor.calculate(completeHistory, fullWake, zone)
        val fullTomorrow = Instant.ofEpochMilli(fullWake).atZone(zone).toLocalDate().plusDays(1)
        assertTrue(
            fullResult.forecast.any {
                Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == fullTomorrow
            }
        )
    }

    @Test
    fun ongoingOvernightDoesNotJumpAtEitherSeasonalClockChange() {
        for (date in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            val bedtime = date.minusDays(1).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
            val nights = (1..7).map { session(bedtime - it * 86_400_000L, 660) }
            val active = session(bedtime).copy(endedAt = null)
            val before = SleepPredictor.calculate(nights + active, bedtime, zone).current!!
            val after =
                SleepPredictor.calculate(nights + active, bedtime + 8 * 60 * minute, zone).current!!
            assertEquals(bedtime + 660 * minute, before.expectedWake)
            assertEquals(before, after)
            val expectedCivilHour = if (date.monthValue == 3) 9 else 7
            assertEquals(
                expectedCivilHour,
                Instant.ofEpochMilli(after.expectedWake).atZone(zone).hour,
            )
            // A fixed pre-transition clock must give identical results on the same observations.
            val oldClock = zone.rules.getOffset(Instant.ofEpochMilli(bedtime))
            assertEquals(
                after,
                SleepPredictor.calculate(nights + active, bedtime + 8 * 60 * minute, oldClock)
                    .current,
            )
        }
    }

    @Test
    fun nextSleepModelMatchesOldClockAcrossSeasonalChanges() {
        for (date in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            val oldClock = zone.rules.getOffset(date.minusDays(1).atStartOfDay(zone).toInstant())
            val first = date.minusDays(18).atTime(7, 0).atZone(oldClock).toInstant().toEpochMilli()
            val events =
                (0..18).flatMap { day ->
                    (0..3).map { nap -> session(first + day * 86_400_000L + nap * 150 * minute) }
                }
            val now = events.last().endedAt!!
            assertEquals(
                SleepPredictor.calculate(events, now, oldClock).next,
                SleepPredictor.calculate(events, now, zone).next,
            )
        }
    }

    @Test
    fun travelChangesCivilLabelsWithoutChangingCurrentOrNextPredictions() {
        val home = BabyProfile(birthday = "2026-07-01", rhythmZoneId = zone.id)
        val events = history(18)
        val wake = events.last().endedAt!!
        val expected = SleepPredictor.calculate(events, wake, zone, home).next!!
        val start = base + 18 * 86_400_000L
        val active = session(start).copy(endedAt = null)
        val before = SleepPredictor.calculate(events + active, start + minute, zone, home).current!!
        for (destination in
            listOf("America/New_York", "Asia/Tokyo", "Pacific/Honolulu", "Pacific/Kiritimati")) {
            val local = ZoneId.of(destination)
            assertEquals(expected, SleepPredictor.calculate(events, wake, local, home).next)
            val after = SleepPredictor.calculate(events + active, start + minute, local, home)
            assertEquals(before, after.current)
            assertTrue(after.forecast.size > 1)
            assertEquals(before.expectedWake, after.forecast.first().end)
        }
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
