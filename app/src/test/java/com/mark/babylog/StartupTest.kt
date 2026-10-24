package com.mark.babylog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BabyLogApp::class, shadows = [StartupAppShadow::class])
class StartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realActivityOpensSleepScreen() {
        compose.onNodeWithText("Сон малыша").assertExists()
        compose.onNodeWithText("Update app").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки").assertExists()
        compose.onNodeWithContentDescription("Звуки для сна").assertExists()
        val app = compose.activity.application as BabyLogApp
        kotlinx.coroutines.runBlocking {
            app.database
                .events()
                .putProfile(com.mark.babylog.data.BabyProfile(birthday = "2026-01-01"))
            val start =
                java.time.LocalDate.now()
                    .atTime(1, 0)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            app.database
                .events()
                .insert(
                    com.mark.babylog.data.BabyEvent(
                        type = com.mark.babylog.data.EventType.SLEEP,
                        detail = "BACK",
                        startedAt = start,
                        endedAt = start + 3_600_000,
                        sleepRecordVersion = 1,
                    )
                )
        }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Update app").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Готово").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithContentDescription("сон 01:00 — 02:00", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}

@Implements(BabyLogApp::class)
class StartupAppShadow : org.robolectric.shadows.ShadowApplication() {
    @org.robolectric.annotation.RealObject lateinit var app: BabyLogApp

    @Implementation
    fun onCreate() {
        // Robolectric omits the Android Startup provider. Initialize its WorkManager
        // dependency before running the real Application.onCreate.
        androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        org.robolectric.shadow.api.Shadow.directlyOn<Unit, BabyLogApp>(
            app,
            BabyLogApp::class.java,
            "onCreate",
        )
    }
}
