# Dashboard visual review

These are deterministic Paparazzi renders of the implemented Compose cards and
chart, not design mockups. The after fixtures also use the production app bar.
The six before fixtures were rendered from `bc4f2f1`, including the other agent's
pinch zoom and selected-history highlighting, before applying this change.

Open [the side-by-side gallery](index.html), or compare the PNGs below at full size.
The dashboard is scrollable: a chart extending below the viewport in the large-text
or sleeping fixture is reachable by scrolling, while the main action remains near the top.

| Scenario | Before | After |
| --- | --- | --- |
| Light, awake | [PNG](before/com.mark.babylog_DashboardReviewScreenshotTest_lightAwake.png) | [PNG](after/com.mark.babylog_DashboardReviewScreenshotTest_lightAwake.png) |
| Dark, awake | [PNG](before/com.mark.babylog_DashboardReviewScreenshotTest_darkAwake.png) | [PNG](after/com.mark.babylog_DashboardReviewScreenshotTest_darkAwake.png) |
| Light, sleeping | [PNG](before/com.mark.babylog_DashboardReviewScreenshotTest_lightSleeping.png) | [PNG](after/com.mark.babylog_DashboardReviewScreenshotTest_lightSleeping.png) |
| Dark, sleeping | [PNG](before/com.mark.babylog_DashboardReviewScreenshotTest_darkSleeping.png) | [PNG](after/com.mark.babylog_DashboardReviewScreenshotTest_darkSleeping.png) |
| Wide estimated window | [PNG](before/com.mark.babylog_DashboardReviewScreenshotTest_wideWindow.png) | [PNG](after/com.mark.babylog_DashboardReviewScreenshotTest_wideWindow.png) |
| 150% text | [PNG](before/com.mark.babylog_DashboardReviewScreenshotTest_largeText.png) | [PNG](after/com.mark.babylog_DashboardReviewScreenshotTest_largeText.png) |

[Zoomed chart with aligned labels/grid](after/zoomed-chart.png).
[Full dark chart](after/dark-chart.png).

The light action uses #65558F with white text (6.46:1 contrast). The dark action
uses #B7A4DB with #241A38 text (7.30:1); the dark background stays #141218.
Recorded blocks are solid; projected sleep blocks are hollow. The graph contains
no confidence bands. Its labels and grid share the same whole-hour ticks at every
zoom, with civil-time labels on an elapsed-time axis across DST.

The one-second timer is confined to the elapsed-duration leaf, which publishes
hours/minutes only. Predictions retain their 15-second cadence; chart/totals use
minute time inputs. Session validation is cached until events or actual validity
boundaries change. Compose tests measure parent recomposition, chart measurement,
and marker movement; they do not claim that recomposition equals a canvas redraw.

Forecasts remain visible after the first estimate expires, anchored to the last
recorded wake rather than sliding forward. They remain projections of recorded
history, not a schedule. With insufficient usable history, the app cannot invent
a forecast. Live Firebase exchange and device/emulator interaction are outside
these deterministic previews.

## Validation

- Gradle 8.9 / Java 17: `spotlessCheck testDebugUnitTest lintDebug assembleDebug` passed.
- 92 behavioral tests passed, including cancellation, sync display state, elapsed-clock
  isolation, marker movement, large-text controls, retained forecasts, and DST ticks.
- `verifyPaparazziDebug` passed: 39 screenshot cases (131 tests including behavioral cases).
- Lint: 0 errors, 65 warnings. The app bar stays 64 dp high; sync and main actions
  meet the checked 48 dp minimum at 150% text.
- Backend: 10 tests passed. Release tooling: 8 tests passed.
- Existing chart pinch/pan/navigation tests remain green.

The initial screenshot verification exposed the intended visual differences. Those
rendered results were inspected before recording affected goldens; unrelated
baselines were retained. The final comparison passes against the reviewed images.
