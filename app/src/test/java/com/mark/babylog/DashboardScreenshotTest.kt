package com.mark.babylog

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.mark.babylog.data.*
import org.junit.Rule
import org.junit.Test

class DashboardScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig.PIXEL_5)
    @Test fun dashboard_matches_golden(){val day=java.time.LocalDate.now();val now=day.atTime(19,23).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=listOf(BabyEvent(1,EventType.SLEEP,"LEFT",now-42*60000,null),BabyEvent(2,EventType.FEEDING,"RIGHT",now-2*3600000,now-105*60000)),selectedDay=day),fixedNow=now)}}}
    @Test fun longHistory_keepsActionsReachableAndNewestFirst(){val day=java.time.LocalDate.now();val now=day.atTime(21,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();val events=(1L..30L).map{index->BabyEvent(index,if(index%3L==0L)EventType.SLEEP else EventType.FEEDING,if(index%3L==0L)"BACK" else if(index%2L==0L)"LEFT" else "RIGHT",now-index*20*60000,now-index*20*60000+12*60000)};paparazzi.snapshot{BabyTheme{BabyScreen(UiState(events=events,selectedDay=day),fixedNow=now)}}}
}
