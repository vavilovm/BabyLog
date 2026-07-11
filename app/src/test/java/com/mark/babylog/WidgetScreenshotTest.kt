package com.mark.babylog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.mark.babylog.data.*
import com.mark.babylog.widget.*
import org.junit.Rule
import org.junit.Test

class WidgetScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig(screenWidth=300,screenHeight=275,density=Density.MEDIUM))
    private val now=1_000_000L
    @Test fun feedingWidget_selection_matchesGolden()=paparazzi.snapshot{BabyTheme{WidgetPreview(feedingWidgetUi(null,null,now),null)}}
    @Test fun feedingWidget_activeLeft_showsSecondsAndStop()=paparazzi.snapshot{BabyTheme{WidgetPreview(feedingWidgetUi(BabyEvent(1,EventType.FEEDING,"LEFT",now-42_000),null,now),"00:42")}}
    @Test fun sleepWidget_selection_matchesGolden()=paparazzi.snapshot{BabyTheme{WidgetPreview(sleepWidgetUi(null,now),null)}}
    @Test fun sleepWidget_activeRight_showsSecondsAndStop()=paparazzi.snapshot{BabyTheme{WidgetPreview(sleepWidgetUi(BabyEvent(2,EventType.SLEEP,"RIGHT",now-83_000),now),"01:23")}}
}

@Composable private fun WidgetPreview(ui:WidgetUi,timer:String?){Column(Modifier.fillMaxSize().background(Color(0xFFF4EFF7)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(ui.title,style=MaterialTheme.typography.titleLarge);Text(ui.status);if(timer!=null)Text(timer,style=MaterialTheme.typography.headlineSmall);ui.buttons.forEach{Button({},Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Text(it.label,maxLines=1)}}}}
