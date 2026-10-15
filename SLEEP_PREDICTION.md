# Sleep tracking and predictions

Sleep sessions use `sleepRecordVersion = 1`: a null end means currently asleep.
Version 0 records are historical instant marks. Migration preserves those records,
feeding history, and sleep-position information; none is converted into invented
sleep durations. Birthday and optional due date are stored in the shared baby
profile, independently from events.

## Evidence and limits

Sleep pressure and circadian rhythms both contribute to infant sleep timing.
The modelling research below supports considering both mechanisms; it does **not**
validate this app's predictor or establish a universally best next-nap formula.

- Physiological modelling across infancy: https://pmc.ncbi.nlm.nih.gov/articles/PMC11527290/
- Parent diaries and objective measurements differ, even with relatively good
  onset/offset agreement: https://pmc.ncbi.nlm.nih.gov/articles/PMC8033447/
- AASM duration guidance excludes infants under four months because of variability
  and insufficient evidence: https://pmc.ncbi.nlm.nih.gov/articles/PMC4877308/

The app predicts **recorded sleep onset**, not an optimal or medically necessary
bedtime. It does not prescribe sleep training, force sleep quotas, or diagnose
sleep problems. It does not use an LLM or send sleep records to an AI service.

## Model

- Read overlapping sessions independently of paginated history; use the preceding
  28 days for training. Preserve overnight sessions across display boundaries.
- Exclude legacy marks, deleted, future, invalid, conflicting, and overlapping
  sessions. Never bridge an intervening record or an awake interval outside
  5 minutes–8 hours. This cannot detect every missed log: parents must record
  actual sleep and correct omissions.
- Distinguish daytime, evening, and overnight wake contexts. When a daytime or
  evening context is sparse, borrow other daytime observations and keep the
  estimate preliminary; never borrow daytime observations for overnight returns.
- Fit weighted personal medians, time-of-day medians, and ridge regression. Weight
  observations with a seven-day half-life. Regression uses cyclical local time,
  preceding sleep duration, number of earlier sleeps that day, and prior 24-hour
  recorded sleep. Those features use only information available at the forecast.
- Regression requires 30 earlier intervals to train and eight later observations
  for comparison. Select time-of-day only with at least 5% lower mean absolute
  error; select regression with at least 10% improvement. Otherwise retain the
  simpler model. These are conservative engineering defaults, not clinical rules.
- Derive the nominal 80% range from chronological absolute forecast errors after
  ten evaluations. Coverage is evaluated using only preceding errors to set each
  interval. Coverage remains an empirical diagnostic, not a guarantee. Model
  selection and calibration use the same rolling history and can be optimistic;
  long-term prospective validation is still required.
- Until enough errors exist, use a broad preliminary range; for corrected ages
  under 120 days, its half-width is at least 45 minutes (otherwise 30 minutes),
  and at least 35% of the central awake-duration estimate. These are uncertainty
  defaults, not age-specific wake-window recommendations. No history means no
  precise estimate.
- Every new start, wake, edit, deletion, profile change, or sync emission recomputes
  the result. An estimate that passes remains anchored to the recorded wake.
- Project the remaining day using personal sleep durations and awake intervals,
  with uncertainty increasing as the square root of the number of projected
  sleeps. These are tentative projections rather than separately calibrated
  whole-day prediction intervals.

## Ongoing sleep duration

While a sleep is active, estimate its total recorded duration and a wake-up window
from completed, valid sessions in the 28 days before its start. Separate night
starts (18:00–04:59 local time) from daytime starts. Use a weighted median with a
seven-day recency half-life and circular time-of-day similarity. One matching
session is enough for a clearly preliminary estimate; no matching history means
no invented duration. The active session never trains its own estimate.

The descriptive window spans weighted 20th–80th percentiles and is widened to at
least ±30%, with a minimum half-width of 15 minutes for naps or 60 minutes for
night sleep. It is not a calibrated probability interval. Keep the estimate
anchored to sleep onset when the clock advances; if it passes, explain that sleep
has exceeded the rough estimate. Night estimates refer to the next recorded wake,
not the total of multiple sleeps interrupted by night wakings. All elapsed
durations use timestamps, including across daylight-saving changes.

## Compatibility and rollout

Deploy the functions and Firestore rules **before distributing the updated APK**:

```sh
npm test --prefix functions
firebase deploy --only functions,firestore --project babylog-family
```

New commands are `SLEEP_START_V1`, `SLEEP_SAVE_V1`, and `PROFILE_UPSERT`.
The old backend cannot preserve active timed sleep. The app keeps failed commands
in its outbox and exposes sync errors until the updated backend is available.
Use the updated app on all caregivers' devices; older apps do not understand the
new session semantics and must not edit timed sessions.

The server serialises active-session ownership through the household document.
A competing active record is preserved with `sleepConflict = true` rather than
closing another parent's timer. Clients show it for correction and exclude it
from predictions. Closing or deleting the owning session clears its pointer.
Completed overlapping offline records remain visible and are excluded locally
until their times are corrected or an unwanted record is deleted.

The profile is stored at `households/{household}/profiles/baby`; only household
members can read it. Writes go through authenticated functions. Joining a family
adopts its existing profile. Subsequent profile changes use update timestamps.

## Checks

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew verifyPaparazziDebug
npm test --prefix functions
```

Unit tests cover deterministic prediction, drift, sparse history, no future-data
leakage, overlap exclusion, clock advancement, corrected age, DST elapsed time,
timed-session transitions, profile sync, and migration from the legacy database.
Screenshot fixtures cover charts, dark mode, larger text, and birthday onboarding.
The app exposes historical median timing error and interval coverage. These are
based on logged history; synthetic tests are not a claim of accuracy for a baby.
