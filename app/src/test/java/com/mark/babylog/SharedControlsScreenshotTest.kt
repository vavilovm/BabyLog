package com.mark.babylog

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.mark.babylog.data.*
import org.junit.Rule
import org.junit.Test

class SharedControlsScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun dailyStatistics_showsFeedingRhythmAndUsefulTotals() {
        val day = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        fun at(hour: Int, minute: Int = 0) =
            day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val events =
            listOf(
                BabyEvent(1, EventType.FEEDING, "LEFT", at(7, 30), at(7, 45)),
                BabyEvent(2, EventType.FEEDING, "RIGHT", at(7, 45), at(7, 58)),
                BabyEvent(3, EventType.FEEDING, "BOTTLE:120", at(11), at(11)),
                BabyEvent(4, EventType.FEEDING, "LEFT", at(14, 20), at(14, 38)),
                BabyEvent(5, EventType.SLEEP, "LEFT", at(9), at(9)),
                BabyEvent(6, EventType.SLEEP, "RIGHT", at(12), at(12)),
                BabyEvent(7, EventType.PUMPING, "LEFT:90", at(16), at(16)),
            )
        paparazzi.snapshot {
            BabyTheme { DailyStatisticsDialog(DailyStatisticsState(day, events, false), {}, {}) }
        }
    }
}
