package com.mark.babylog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import java.time.*
import org.junit.Rule
import org.junit.Test

class SleepChartScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now =
        LocalDate.of(2026, 10, 15).atTime(13, 30).atZone(zone).toInstant().toEpochMilli()
    private val events =
        (0..6).flatMap { offset ->
            val day = LocalDate.of(2026, 10, 15).minusDays(offset.toLong())
            listOf(0 to 360, 9 to 60, 12 to 60, 15 to 45, 20 to 240)
                .map { (hour, minutes) ->
                    val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                    BabyEvent(
                        type = EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        endedAt = start + minutes * 60_000L,
                        sleepRecordVersion = 1,
                    )
                }
                .filter { it.endedAt!! < now }
        }

    @Composable
    private fun Chart(days: Int) {
        Surface {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Сон малыша", style = MaterialTheme.typography.headlineMedium)
                Text("Ритм сна · $days дней", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Сплошной — записанный сон · светлый — прогноз",
                    style = MaterialTheme.typography.bodySmall,
                )
                SleepChart(
                    events,
                    listOf(ForecastSleep(now + 3_600_000, now + 6_300_000, 30)),
                    now,
                    zone,
                    days,
                    {},
                )
                if (days == 7) SleepTotals(SleepPredictor.validSessions(events, now), now, zone)
            }
        }
    }

    @Test
    fun zoomedChart_labelsFollowGrid() =
        paparazzi.snapshot {
            BabyTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        SleepChart(
                            events,
                            listOf(ForecastSleep(now + 3_600_000, now + 6_300_000, 30)),
                            now,
                            zone,
                            3,
                            {},
                            initialViewport = SleepChartViewport(3f, 0.4f),
                        )
                    }
                }
            }
        }

    @Test
    fun sleepDashboard_nextNap() =
        paparazzi.snapshot {
            BabyTheme {
                Surface {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Сон малыша", style = MaterialTheme.typography.headlineMedium)
                        val sessions = SleepPredictor.validSessions(events, now)
                        SleepStatusCard(null, sessions.last().endedAt, now, zone, {}, {}, {})
                        NextSleepCard(
                            SleepPredictor.calculate(events, now, zone).next,
                            false,
                            now,
                            zone,
                        )
                        Text("Ритм сна", style = MaterialTheme.typography.titleLarge)
                        SleepChart(events, emptyList(), now, zone, 7, {})
                    }
                }
            }
        }

    @Test
    fun sleepEditor_timeAndDateShortcuts() =
        paparazzi.snapshot {
            BabyTheme { SleepEntryDialog(events.first(), zone, {}, { _, _ -> }, {}) }
        }

    @Test
    fun sleepStatus_firstMinuteShowsSeconds() =
        paparazzi.snapshot {
            BabyTheme { SleepStatusCard(null, now - 25_000, now, zone, {}, {}, {}) }
        }

    @Test
    fun sleepStatus_showsSeconds() =
        paparazzi.snapshot {
            BabyTheme { SleepStatusCard(null, now - 125_000, now, zone, {}, {}, {}) }
        }

    @Test
    fun ongoingNight_durationEstimate() =
        paparazzi.snapshot {
            BabyTheme {
                val start =
                    LocalDate.of(2026, 10, 15).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
                val current =
                    CurrentSleepEstimate(
                        start,
                        start + 6 * 3_600_000L,
                        start + 8 * 3_600_000L,
                        start + 10 * 3_600_000L,
                        5,
                        true,
                    )
                NextSleepCard(null, true, start + 3_600_000L, zone, current)
            }
        }

    @Test
    fun ongoingNight_projectsIntoTomorrow() =
        paparazzi.snapshot {
            BabyTheme {
                val start =
                    LocalDate.of(2026, 10, 15).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
                val active =
                    BabyEvent(
                        type = EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        sleepRecordVersion = 1,
                    )
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        SleepChart(
                            listOf(active),
                            listOf(ForecastSleep(start + 3_600_000L, start + 11 * 3_600_000L, 30)),
                            start + 3_600_000L,
                            zone,
                            7,
                            {},
                            CurrentSleepEstimate(
                                start,
                                start + 9 * 3_600_000L,
                                start + 11 * 3_600_000L,
                                start + 14 * 3_600_000L,
                                5,
                                true,
                            ),
                        )
                    }
                }
            }
        }

    @Test
    fun ongoingNap_expectedWakeOnly() =
        paparazzi.snapshot {
            BabyTheme {
                val start = now - 10 * 60_000L
                val active =
                    BabyEvent(
                        type = EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        sleepRecordVersion = 1,
                    )
                val current =
                    CurrentSleepEstimate(
                        start,
                        now + 60_000L,
                        now + 29 * 60_000L,
                        now + 42 * 60_000L,
                        5,
                        false,
                    )
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        SleepChart(
                            events.filter { it.endedAt!! <= start } + active,
                            listOf(ForecastSleep(now, current.expectedWake, 20)),
                            now,
                            zone,
                            7,
                            {},
                            current,
                        )
                    }
                }
            }
        }

    @Test
    fun repeatedHour_usesElapsedTimeline() =
        paparazzi.snapshot {
            BabyTheme {
                val day = LocalDate.of(2026, 10, 25)
                val local = day.atTime(2, 30)
                val start =
                    local.atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
                val end = local.atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
                val event =
                    BabyEvent(
                        type = EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        endedAt = end,
                        sleepRecordVersion = 1,
                    )
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        SleepChart(listOf(event), emptyList(), end + 60_000, zone, 3, {})
                    }
                }
            }
        }

    @Test
    fun nightDashboard_broadWindow() =
        paparazzi.snapshot {
            BabyTheme {
                val start =
                    LocalDate.of(2026, 10, 15).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
                val now = start + 3_600_000L
                val range =
                    CurrentSleepEstimate(
                        start,
                        start + 77 * 60_000L,
                        start + 743 * 60_000L,
                        start + 953 * 60_000L,
                        5,
                        true,
                    )
                Surface {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        NextSleepCard(null, true, now, zone, range)
                        SleepChart(
                            listOf(
                                BabyEvent(
                                    type = EventType.SLEEP,
                                    detail = "BACK",
                                    startedAt = start,
                                    sleepRecordVersion = 1,
                                )
                            ),
                            listOf(ForecastSleep(now, range.expectedWake, 30)),
                            now,
                            zone,
                            3,
                            {},
                            range,
                        )
                    }
                }
            }
        }

    @Test fun weeklySleep_chartAndTotals() = paparazzi.snapshot { BabyTheme { Chart(7) } }

    @Test
    fun fortnight_dark() = paparazzi.snapshot { MaterialTheme(colorScheme = Dark) { Chart(14) } }

    @Test fun month_compactRows() = paparazzi.snapshot { BabyTheme { Chart(30) } }

    @Test
    fun weeklySleep_largeText() =
        paparazzi.snapshot {
            CompositionLocalProvider(LocalDensity provides Density(2.75f, 1.3f)) {
                BabyTheme { Chart(7) }
            }
        }

    @Test
    fun birthday_requiredOnboarding() =
        paparazzi.snapshot { BabyTheme { ProfileDialog(null, {}, { _, _, _ -> }) } }
}
