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
    fun pinchAcrossRowsZoomsWithoutSelectingSleepAndResetRestoresDay() {
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
        compose.onNodeWithText("Весь день").assertIsDisplayed()
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithText("Весь день").performClick()
        compose.onNodeWithText("Весь день").assertDoesNotExist()
        compose.onNodeWithContentDescription("15.10.2026: сон 09:00 — 11:00").performClick()
        compose.runOnIdle { assertEquals(event, selected) }
    }

    @Test
    fun verticalSwipeStillScrollsPageAfterZooming() {
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
        compose.onNodeWithText("Весь день").assertIsDisplayed()
        compose.onNodeWithTag("sleep-chart").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0)
        }
    }
}
