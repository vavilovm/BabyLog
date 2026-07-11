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
import org.junit.Rule
import org.junit.Test

class WidgetScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig.PIXEL_5.copy(screenWidth=600,screenHeight=360))
    @Test fun feedingWidget_matchesGolden()=paparazzi.snapshot{BabyTheme{WidgetPreview("Кормление","правая · 1 ч 20 мин назад",listOf("Левая","Правая","Бутылочка"))}}
    @Test fun sleepWidget_matchesGolden()=paparazzi.snapshot{BabyTheme{WidgetPreview("Сон","Спит 42 мин",listOf("Закончить","Левый бок","Правый бок"))}}
}

@Composable private fun WidgetPreview(title:String,status:String,actions:List<String>){Column(Modifier.fillMaxSize().background(Color(0xFFF4EFF7)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleLarge);Text(status);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){actions.take(2).forEach{Button({},Modifier.weight(1f)){Text(it)}}};Button({},Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){Text(actions[2])}}}
