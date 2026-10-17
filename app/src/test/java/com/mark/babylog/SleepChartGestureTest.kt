package com.mark.babylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
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
    fun pinchDoesNotZoomOrSelectSleepAndTapStillOpensHistory() {
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
