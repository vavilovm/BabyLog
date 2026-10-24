package com.mark.babylog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import com.mark.babylog.sync.FamilySyncStatus
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashboardRefreshTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-15T13:30:00Z").toEpochMilli()

    @Test
    fun secondUpdatesDoNotRemeasureChartAndMinuteUpdatesMoveMarker() {
        val second = mutableIntStateOf(0)
        val liveClock = mutableLongStateOf(now)
        val minute = mutableLongStateOf(now)
        var compositions = 0
        var measures = 0
        val events =
            listOf(
                BabyEvent(
                    type = EventType.SLEEP,
                    detail = "BACK",
                    startedAt = now - 7_200_000,
                    endedAt = now - 3_600_000,
                    sleepRecordVersion = 1,
                )
            )
        val chartModifier =
            Modifier.layout { measurable, constraints ->
                measures++
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
        compose.setContent {
            BabyTheme {
                Column {
                    Text("${second.intValue}")
                    SleepElapsedText(now - 58_000, liveClock)
                    SideEffect { compositions++ }
                    SleepChart(
                        events,
                        emptyList(),
                        minute.longValue,
                        zone,
                        3,
                        {},
                        modifier = chartModifier,
                    )
                }
            }
        }
        compose.onNodeWithText("58 с").assertIsDisplayed()
        compose.waitForIdle()
        var initialMeasures = 0
        val initialMarker =
            compose
                .onNodeWithContentDescription("Сейчас 13:30")
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        var initialCompositions = 0
        compose.runOnIdle {
            assertTrue("Must observe actual measurement", measures > 0)
            initialMeasures = measures
            initialCompositions = compositions
            liveClock.longValue += 1_000
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Leaf timer must not recompose parent", initialCompositions, compositions)
            assertEquals("Second updates must not remeasure chart", initialMeasures, measures)
            liveClock.longValue += 1_000
        }
        compose.onNodeWithText("1 мин").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                "Minute transition must not recompose parent",
                initialCompositions,
                compositions,
            )
            assertEquals("Minute transition must not remeasure chart", initialMeasures, measures)
            second.intValue++
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(compositions > initialCompositions)
            assertEquals(
                "Unrelated recomposition must not remeasure chart",
                initialMeasures,
                measures,
            )
            minute.longValue += 15 * 60_000
        }
        compose.waitForIdle()
        assertTrue(
            "Current-time marker must move",
            compose
                .onNodeWithContentDescription("Сейчас 13:45")
                .fetchSemanticsNode()
                .boundsInRoot
                .left > initialMarker,
        )
    }

    @Test
    fun syncDetailsKeepAppBarHeightAndOfferRetryForPendingChanges() {
        var retries = 0
        val pending = mutableIntStateOf(1)
        compose.setContent {
            BabyTheme {
                Column(Modifier.testTag("app-bar")) {
                    SleepAppBar(
                        null,
                        LocalDate.of(2026, 10, 15),
                        true,
                        pending.intValue,
                        FamilySyncStatus(lastSyncedAt = now),
                        zone,
                        { retries++ },
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("app-bar").assertHeightIsEqualTo(64.dp)
        val height = compose.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText("Профиль малыша").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText(SyncDisplayState.PENDING.title).assertIsDisplayed()
        compose.onNodeWithText("Синхронизировать сейчас").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { pending.intValue = 0 }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText(SyncDisplayState.SYNCED.title).assertIsDisplayed()
        assertEquals(
            height,
            compose.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot.height,
        )
        compose.onNodeWithText("Профиль малыша").assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsMainAndAppBarActionsAccessible() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)
            ) {
                BabyTheme {
                    Column {
                        SleepAppBar(
                            BabyProfile(birthday = "2026-01-01"),
                            LocalDate.of(2026, 10, 15),
                            false,
                            0,
                            FamilySyncStatus(),
                            zone,
                            {},
                            {},
                        )
                        SleepStatusCard(null, now - 120_000, now, zone, {}, {}, {})
                    }
                }
            }
        }
        compose
            .onNodeWithContentDescription("Настройки")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose
            .onNodeWithContentDescription("Звуки для сна")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Начать сон").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Профиль малыша").assertIsDisplayed()
        compose.onNodeWithText(SyncDisplayState.LOCAL.title).assertIsDisplayed()
    }

    @Test
    fun statisticsSelectsDaysWithoutTreatingMissingLogsAsZeroSleep() {
        val sessions =
            listOf(
                BabyEvent(
                    type = EventType.SLEEP,
                    detail = "BACK",
                    startedAt = now - 3_600_000,
                    endedAt = now,
                    sleepRecordVersion = 1,
                )
            )
        compose.setContent { BabyTheme { SleepTotals(sessions, now, zone) } }
        compose.onNodeWithText("Дневных снов: 1").assertExists()
        compose.onNodeWithContentDescription("14.10: нет записей").performClick()
        compose.onNodeWithText("Нет записей").assertExists()
        compose.onNodeWithText("Дневных снов: 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("15.10: всего", substring = true).performClick()
        compose.onNodeWithText("Дневных снов: 1").assertExists()
    }

    @Test
    fun expiredAndMissingPredictionsDoNotShowPreciseTime() {
        val estimate =
            mutableStateOf<SleepEstimate?>(
                SleepEstimate(
                    now - 180_000,
                    now - 120_000,
                    now - 60_000,
                    4,
                    "Недавние периоды бодрствования",
                    null,
                    null,
                    true,
                )
            )
        compose.setContent { BabyTheme { NextSleepCard(estimate.value, false, now, zone) } }
        compose.onNodeWithText("Окно прогноза прошло").assertIsDisplayed()
        compose.onNodeWithText("Около", substring = true).assertDoesNotExist()
        compose.runOnIdle { estimate.value = null }
        compose.onNodeWithText("Пока мало данных").assertIsDisplayed()
    }

    @Test
    fun sessionValidityOnlyChangesAtRealEventBoundaries() {
        val event =
            BabyEvent(
                type = EventType.SLEEP,
                detail = "BACK",
                startedAt = now + 1_000,
                endedAt = now + 120_000,
                sleepRecordVersion = 1,
            )
        val events = listOf(event)
        assertEquals(sessionValidityKey(events, now), sessionValidityKey(events, now + 999))
        assertNotEquals(sessionValidityKey(events, now), sessionValidityKey(events, now + 1_000))
        assertEquals(
            sessionValidityKey(events, now + 1_000),
            sessionValidityKey(events, now + 119_999),
        )
        assertNotEquals(
            sessionValidityKey(events, now + 119_999),
            sessionValidityKey(events, now + 120_000),
        )
        assertTrue(SleepPredictor.validSessions(events, now).isEmpty())
        assertEquals(listOf(event), SleepPredictor.validSessions(events, now + 120_000))
    }
}
