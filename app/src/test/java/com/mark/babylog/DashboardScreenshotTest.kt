package com.mark.babylog

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.material3.MaterialTheme
import com.mark.babylog.data.*
import org.junit.Rule
import org.junit.Test

class DashboardScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig.PIXEL_5)
    @Test fun dashboard_matches_golden(){val day=java.time.LocalDate.now();val now=day.atTime(19,23).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=listOf(BabyEvent(1,EventType.SLEEP,"LEFT",now-42*60000,null),BabyEvent(2,EventType.FEEDING,"RIGHT",now-2*3600000,now-105*60000)),segments=listOf(SleepSegment(1,1,SleepPosition.LEFT,now-42*60000,null))),fixedNow=now)}}}
    @Test fun longHistory_keepsActionsReachableAndNewestFirst(){val day=java.time.LocalDate.now();val now=day.atTime(21,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();val events=(1L..130L).map{index->BabyEvent(index,if(index%3L==0L)EventType.SLEEP else EventType.FEEDING,if(index%3L==0L)"BACK" else if(index%2L==0L)"LEFT" else "RIGHT",now-index*20*60000,now-index*20*60000+12*60000)};paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=events),fixedNow=now)}}}
    @Test fun activeFeeding_showsRedStopInBottomActions(){val day=java.time.LocalDate.now();val now=day.atTime(20,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=listOf(BabyEvent(1,EventType.FEEDING,"LEFT",now-73_000))),fixedNow=now)}}}
    @Test fun pumpingDialog_defaultsToOppositeOfLastFeeding(){paparazzi.snapshot{BabyTheme{PumpingDialog(FeedingKind.RIGHT,{}){_,_->}}}}
    @Test fun pumping_isShownInHistory(){val day=java.time.LocalDate.now();val now=day.atTime(19,23).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=listOf(BabyEvent(1,EventType.PUMPING,"RIGHT:120",now,now),BabyEvent(2,EventType.FEEDING,"LEFT",now-2*3600000,now-105*60000))),fixedNow=now)}}}
    @Test fun pumpingDefault_isOppositeOfLatestBreastfeeding(){val now=System.currentTimeMillis();assert(defaultPumpingSide(listOf(BabyEvent(1,EventType.FEEDING,"RIGHT",now,now)))==FeedingKind.LEFT);assert(defaultPumpingSide(listOf(BabyEvent(1,EventType.FEEDING,"LEFT",now,now)))==FeedingKind.RIGHT)}
    @Test fun bottleSummary_showsLastAndNextBreast(){val day=java.time.LocalDate.now();val now=day.atTime(19,23).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=listOf(BabyEvent(1,EventType.FEEDING,"BOTTLE",now-10*60_000,now-5*60_000),BabyEvent(2,EventType.FEEDING,"LEFT",now-3*60*60_000,now-2*60*60_000))),fixedNow=now)}}}
    @Test fun bottleSummary_isLegibleInDarkTheme(){val day=java.time.LocalDate.now();val now=day.atTime(19,23).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{MaterialTheme(colorScheme=Dark){BabyScreen(UiState(events=listOf(BabyEvent(1,EventType.FEEDING,"BOTTLE",now-10*60_000,now-5*60_000),BabyEvent(2,EventType.FEEDING,"LEFT",now-3*60*60_000,now-2*60*60_000))),fixedNow=now)}}}
    @Test fun editTime_hasMinuteShortcuts(){val day=java.time.LocalDate.now();val now=day.atTime(19,23).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{BabyTheme{EditDialog(BabyEvent(1,EventType.FEEDING,"LEFT",now-30*60_000,now-5*60_000),{},{},{})}}}
}
