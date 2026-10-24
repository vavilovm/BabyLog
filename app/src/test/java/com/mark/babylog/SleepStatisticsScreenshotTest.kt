package com.mark.babylog

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class SleepStatisticsScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 10, 24)
    private val now = today.atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
    private val sessions =
        (0..14)
            .flatMap { offset ->
                val day = today.minusDays(offset.toLong())
                fun at(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                fun sleep(start: Long, end: Long) =
                    BabyEvent(
                        type = EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        endedAt = end,
                        sleepRecordVersion = 1,
                    )
                listOf(
                    sleep(at(0), at(7)),
                    sleep(at(10), at(11)),
                    sleep(at(13), at(14) + offset % 3 * 900_000L),
                    sleep(at(21), at(23)),
                )
            }
            .filter { it.endedAt!! <= now }

    private fun snapshot(dark: Boolean = false, large: Boolean = false) =
        paparazzi.snapshot {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)
            ) {
                BabyTheme {
                    MaterialTheme(colorScheme = if (dark) Dark else MaterialTheme.colorScheme) {
                        Surface {
                            androidx.compose.foundation.layout.Column(
                                Modifier.verticalScroll(rememberScrollState()).padding(16.dp)
                            ) {
                                SleepTotals(sessions, now, zone)
                            }
                        }
                    }
                }
            }
        }

    @Test fun light() = snapshot()

    @Test fun dark() = snapshot(dark = true)

    @Test fun largeText() = snapshot(large = true)
}
