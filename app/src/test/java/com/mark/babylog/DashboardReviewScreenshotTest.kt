package com.mark.babylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/** Review fixtures use the same cards and chart as SleepScreen. */
class DashboardReviewScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now =
        LocalDate.of(2026, 10, 15).atTime(13, 30).atZone(zone).toInstant().toEpochMilli()
    private val sessions =
        (1..7).map { day ->
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = now - day * 86_400_000L - 3_600_000,
                endedAt = now - day * 86_400_000L,
                sleepRecordVersion = 1,
            )
        }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun dashboard(
        dark: Boolean = false,
        sleeping: Boolean = false,
        wide: Boolean = false,
        large: Boolean = false,
    ) =
        paparazzi.snapshot {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)
            ) {
                BabyTheme {
                    MaterialTheme(colorScheme = if (dark) Dark else MaterialTheme.colorScheme) {
                        val active =
                            if (sleeping)
                                BabyEvent(
                                    type = EventType.SLEEP,
                                    detail = "BACK",
                                    startedAt = now - 1_800_000,
                                    sleepRecordVersion = 1,
                                )
                            else null
                        val next =
                            SleepEstimate(
                                now + 1_800_000,
                                now + 5_400_000,
                                now + if (wide) 14_400_000 else 7_200_000,
                                12,
                                "Недавние периоды бодрствования",
                                null,
                                null,
                                false,
                            )
                        val current =
                            if (sleeping)
                                CurrentSleepEstimate(
                                    active!!.startedAt,
                                    now + 900_000,
                                    now + 2_700_000,
                                    now + if (wide) 10_800_000 else 4_500_000,
                                    5,
                                    false,
                                )
                            else null
                        Scaffold(
                            topBar = {
                                SleepAppBar(
                                    null,
                                    LocalDate.of(2026, 10, 15),
                                    true,
                                    0,
                                    com.mark.babylog.sync.FamilySyncStatus(lastSyncedAt = now),
                                    zone,
                                    {},
                                    {},
                                    updateAction = { TextButton(onClick = {}) { Text("Обновить") } },
                                )
                            }
                        ) { padding ->
                            Column(
                                Modifier.padding(padding)
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                SleepStatusCard(active, now - 5_400_000, now, zone, {}, {}, {})
                                NextSleepCard(next, sleeping, now, zone, current)
                                Text("Ритм сна", style = MaterialTheme.typography.titleLarge)
                                SleepChart(
                                    sessions + listOfNotNull(active),
                                    listOf(ForecastSleep(now + 5_400_000, now + 8_100_000, 30)),
                                    now,
                                    zone,
                                    3,
                                    {},
                                    current,
                                )
                            }
                        }
                    }
                }
            }
        }

    @Test fun lightAwake() = dashboard()

    @Test fun darkAwake() = dashboard(dark = true)

    @Test fun lightSleeping() = dashboard(sleeping = true)

    @Test fun darkSleeping() = dashboard(dark = true, sleeping = true)

    @Test fun wideWindow() = dashboard(wide = true)

    @Test fun largeText() = dashboard(large = true)
}
