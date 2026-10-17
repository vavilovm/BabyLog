package com.mark.babylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mark.babylog.data.BabyEvent
import com.mark.babylog.data.EventType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class SleepChartGestureTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("UTC")
    private val start = LocalDate.of(2026, 10, 15).atStartOfDay(zone).toInstant().toEpochMilli()
    private val event =
        BabyEvent(
            type = EventType.SLEEP,
            detail = "BACK",
            startedAt = start + 9 * 3_600_000,
            endedAt = start + 11 * 3_600_000,
            sleepRecordVersion = 1,
        )

    @Test
    fun pinchDoesNotZoomOrSelectSleepAndTapStillOpensDetails() {
        var selected: BabyEvent? = null
        compose.setContent {
            BabyTheme {
                Surface(Modifier.fillMaxWidth()) {
                    SleepChart(
                        listOf(event),
                        emptyList(),
                        start + 13 * 3_600_000,
                        zone,
                        7,
                        { selected = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("sleep-chart").performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.4f))
            down(1, Offset(width * 0.6f, height * 0.6f))
            moveTo(0, Offset(width * 0.25f, height * 0.25f))
            moveTo(1, Offset(width * 0.75f, height * 0.75f))
            up(0)
            up(1)
        }
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithText("Весь день").assertDoesNotExist()
        compose.onNodeWithContentDescription("15.10.2026: сон 09:00 — 11:00").performClick()
        compose.runOnIdle { assertEquals(event, selected) }
    }

    @Test
    fun hoverShowsExactTimeAndSleepRangeAndExitClearsGuide() {
        compose.setContent {
            BabyTheme {
                Surface(Modifier.fillMaxWidth()) {
                    SleepChart(listOf(event), emptyList(), start + 13 * 3_600_000, zone, 7, {})
                }
            }
        }
        val row = compose.onNodeWithTag("sleep-chart-row-2026-10-15")
        row.performMouseInput { enter(Offset(width * 10f / 24f, height / 2f)) }
        compose
            .onNodeWithTag("sleep-chart-time")
            .assertTextContains("15.10 · 10:00 · сон 09:00 — 11:00")
        compose.onNodeWithTag("sleep-chart-time-guide-2026-10-15").assertExists()
        compose.onNodeWithTag("sleep-chart-time-badge").assertTextEquals("10:00")
        row.performMouseInput { moveTo(Offset(width / 2f, height / 2f)) }
        compose.onNodeWithTag("sleep-chart-time").assertTextContains("15.10 · 12:00")
        row.performMouseInput { exit() }
        compose.onNodeWithTag("sleep-chart-time-guide-2026-10-15").assertDoesNotExist()
    }

    @Test
    fun longPressAndDragInspectsWithoutSelectingSleep() {
        var selected: BabyEvent? = null
        compose.setContent {
            BabyTheme {
                Surface(Modifier.fillMaxWidth()) {
                    SleepChart(
                        listOf(event),
                        emptyList(),
                        start + 13 * 3_600_000,
                        zone,
                        7,
                        { selected = it },
                    )
                }
            }
        }
        val row = compose.onNodeWithTag("sleep-chart-row-2026-10-15")
        row.performTouchInput {
            down(Offset(width * 10f / 24f, height / 2f))
            advanceEventTime(700)
            moveTo(Offset(width / 2f, height / 2f))
        }
        compose.onNodeWithTag("sleep-chart-time").assertTextContains("15.10 · 12:00")
        compose.runOnIdle { assertNull(selected) }
        row.performTouchInput { up() }
        compose.onNodeWithTag("sleep-chart-time-guide-2026-10-15").assertDoesNotExist()
        compose.runOnIdle { assertNull(selected) }
    }

    @Test
    fun cardTracksSelectionAndEditsThePickedSleep() {
        val other =
            event.copy(
                remoteId = "other",
                startedAt = start + 6 * 3_600_000,
                endedAt = start + 7 * 3_600_000,
            )
        var edited: BabyEvent? = null
        compose.setContent {
            BabyTheme {
                Surface(Modifier.fillMaxWidth()) {
                    SleepChart(
                        listOf(event, other),
                        emptyList(),
                        start + 13 * 3_600_000,
                        zone,
                        3,
                        {},
                        onEdit = { edited = it },
                    )
                }
            }
        }
        val first = compose.onNodeWithContentDescription("15.10.2026: сон 09:00 — 11:00")
        val second = compose.onNodeWithContentDescription("15.10.2026: сон 06:00 — 07:00")
        first.performClick()
        first.assertIsSelected()
        second.assertIsNotSelected()
        compose.onNodeWithTag("sleep-detail-card").assertIsDisplayed()
        compose.onNodeWithText("Длительность · 2 ч 0 мин").assertIsDisplayed()
        compose.onNodeWithText("Изменить сон").performClick()
        compose.runOnIdle { assertEquals(event, edited) }
        second.performClick()
        first.assertIsNotSelected()
        second.assertIsSelected()
        compose.onNodeWithText("Длительность · 1 ч 0 мин").assertIsDisplayed()
        compose.onNodeWithText("Изменить сон").performClick()
        compose.runOnIdle { assertEquals(other, edited) }
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithTag("sleep-detail-card").assertDoesNotExist()
        second.assertIsNotSelected()
    }

    @Test
    fun overnightSleepHasOneCardAndBothSegmentsStaySelected() {
        val overnight =
            event.copy(startedAt = start - 2 * 3_600_000, endedAt = start + 6 * 3_600_000)
        val events = mutableStateOf(listOf(overnight))
        compose.setContent {
            BabyTheme {
                Surface(Modifier.fillMaxWidth()) {
                    SleepChart(events.value, emptyList(), start + 13 * 3_600_000, zone, 3, {})
                }
            }
        }
        val segments =
            compose.onAllNodesWithContentDescription("сон 22:00 — 06:00", substring = true)
        segments[1].performClick()
        segments[0].assertIsSelected()
        segments[1].assertIsSelected()
        compose.onAllNodesWithTag("sleep-detail-card").assertCountEquals(1)
        compose.onNodeWithText("14.10.2026 · 22:00 — 06:00 · 15.10.2026").assertIsDisplayed()
        compose.runOnIdle { events.value = emptyList() }
        compose.onNodeWithTag("sleep-detail-card").assertDoesNotExist()
    }

    @Test
    fun openingCardAtBottomOfLongChartBringsEditIntoView() {
        compose.setContent {
            BabyTheme {
                LazyColumn(Modifier.fillMaxWidth().height(500.dp)) {
                    item {
                        SleepChart(listOf(event), emptyList(), start + 13 * 3_600_000, zone, 30, {})
                    }
                }
            }
        }
        val bar = compose.onNodeWithContentDescription("15.10.2026: сон 09:00 — 11:00")
        bar.performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText("Изменить сон").assertIsDisplayed()
        bar.assertIsSelected()
    }

    @Test
    fun verticalSwipeStillScrollsPage() {
        lateinit var scroll: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            BabyTheme {
                scroll = rememberLazyListState()
                LazyColumn(Modifier.fillMaxWidth().height(400.dp), state = scroll) {
                    item {
                        SleepChart(listOf(event), emptyList(), start + 13 * 3_600_000, zone, 7, {})
                    }
                    item { Spacer(Modifier.height(900.dp)) }
                }
            }
        }
        compose.onNodeWithTag("sleep-chart").performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.4f))
            down(1, Offset(width * 0.6f, height * 0.6f))
            moveTo(0, Offset(width * 0.25f, height * 0.25f))
            moveTo(1, Offset(width * 0.75f, height * 0.75f))
            up(0)
            up(1)
        }
        compose.onNodeWithText("Весь день").assertDoesNotExist()
        compose.onNodeWithTag("sleep-chart").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0)
        }
    }
}
