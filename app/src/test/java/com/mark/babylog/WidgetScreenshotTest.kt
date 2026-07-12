package com.mark.babylog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.mark.babylog.data.*
import com.mark.babylog.widget.*
import org.junit.Rule
import org.junit.Test

private const val NOW=1_000_000L

class WidgetScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig(screenWidth=320,screenHeight=205,orientation=ScreenOrientation.LANDSCAPE,density=Density.MEDIUM))
    @Test fun feedingCompact_selection()=paparazzi.snapshot{BabyTheme{StandardPreview(feedingWidgetUi(null,null,NOW),null)}}
    @Test fun feedingCompact_activeLeft()=paparazzi.snapshot{BabyTheme{StandardPreview(feedingWidgetUi(BabyEvent(1,EventType.FEEDING,"LEFT",NOW-42_000),null,NOW),"00:42")}}
    @Test fun feedingCompact_finishedUsesEndTime(){val ui=feedingWidgetUi(null,BabyEvent(1,EventType.FEEDING,"RIGHT",NOW-120_000,NOW-30_000),NOW);org.junit.Assert.assertTrue(ui.status.contains("0:30 назад"))}
    @Test fun sleepCompact_onlyLeftRight()=paparazzi.snapshot{BabyTheme{StandardPreview(sleepWidgetUi(null,NOW),null)}}
}

class HorizontalWidgetScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig(screenWidth=430,screenHeight=145,orientation=ScreenOrientation.LANDSCAPE,density=Density.MEDIUM))
    @Test fun feedingHorizontal_allActionsFit()=paparazzi.snapshot{BabyTheme{HorizontalPreview(feedingWidgetUi(null,null,NOW))}}
}

class MiniWidgetScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig(screenWidth=210,screenHeight=65,orientation=ScreenOrientation.LANDSCAPE,density=Density.MEDIUM))
    @Test fun feedingMini_usesSingleSymbols()=paparazzi.snapshot{BabyTheme{Row(Modifier.fillMaxSize().background(Color(0xFFF4EFF7)).padding(7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("L","R","🍼").forEach{MiniButton(it,Modifier.weight(1f))}}}}
}

@Composable private fun StandardPreview(ui:WidgetUi,timer:String?){Column(Modifier.fillMaxSize().background(Color(0xFFF4EFF7)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(ui.title,style=MaterialTheme.typography.titleMedium);Text(ui.status,style=MaterialTheme.typography.bodySmall);if(timer!=null)Text(timer,style=MaterialTheme.typography.titleLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){ui.buttons.take(2).forEach{MiniButton(it.label,Modifier.weight(1f))}};ui.buttons.drop(2).firstOrNull()?.let{MiniButton(it.label,Modifier.fillMaxWidth())}}}
@Composable private fun HorizontalPreview(ui:WidgetUi){Column(Modifier.fillMaxSize().background(Color(0xFFF4EFF7)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("${ui.title} · ${ui.status}",style=MaterialTheme.typography.bodyMedium);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){ui.buttons.forEach{MiniButton(it.label,Modifier.weight(1f))}}}}
@Composable private fun MiniButton(text:String,modifier:Modifier){Surface(modifier.height(42.dp),shape=RoundedCornerShape(16.dp),color=Color(0xFF6F579C)){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(text,color=Color.White,maxLines=1)}}}
