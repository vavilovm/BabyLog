package com.mark.babylog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SleepStatisticsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 11)

    private fun at(day: LocalDate, hour: Int) =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val oldDay = today.minusDays(16)
    private val sessions =
        listOf(
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = at(oldDay, 12),
                endedAt = at(oldDay, 14),
                sleepRecordVersion = 1,
            ),
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = at(today, 10),
                endedAt = at(today, 11),
                sleepRecordVersion = 1,
            ),
        )

    private fun show(events: List<BabyEvent> = sessions) {
        compose.setContent {
            BabyTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SleepTotals(events, at(today, 16), zone)
                }
            }
        }
    }

    @Test
    fun buttonsReachOlderRecordsAndReturnToToday() {
        show()
        compose.onNodeWithContentDescription("Следующая неделя").assertIsNotEnabled()
        repeat(2) { compose.onNodeWithContentDescription("Предыдущая неделя").performClick() }
        compose.onNodeWithContentDescription("Предыдущая неделя").assertIsNotEnabled()
        compose.onNodeWithTag("sleep-statistics-day-$oldDay").performScrollTo().performClick()
        compose.onNodeWithText("26.10.2026").assertExists()
        compose.onNodeWithText("Бодрствование").assertExists()
        compose.onNodeWithText("22 ч").assertExists()
        compose.onNodeWithText("К сегодня").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-statistics-day-$today").assertIsSelected()
        compose.onNodeWithContentDescription("Следующая неделя").assertIsNotEnabled()
    }

    @Test
    fun swipesChangeWeekAndAllowSelectingEmptyDay() {
        show()
        compose.onNodeWithTag("sleep-statistics-week").performTouchInput { swipeRight() }
        val emptyDay = today.minusDays(7)
        compose.onNodeWithTag("sleep-statistics-day-$emptyDay").assertIsSelected()
        compose.onNodeWithText("Нет записей").assertExists()
        compose.onNodeWithTag("sleep-statistics-week").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("sleep-statistics-day-$today").assertIsSelected()
        // Swiping toward the future stops at the current week.
        compose.onNodeWithTag("sleep-statistics-week").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("sleep-statistics-day-$today").assertIsSelected()
    }

    @Test
    fun emptyHistoryKeepsNavigationDisabled() {
        show(emptyList())
        compose.onNodeWithContentDescription("Предыдущая неделя").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Следующая неделя").assertIsNotEnabled()
        compose.onNodeWithText("Нет записей").assertExists()
    }
}
