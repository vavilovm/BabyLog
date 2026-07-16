package com.mark.babylog

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

private fun Paparazzi.notificationPreview(backgroundColor:Int){
    val density=context.resources.displayMetrics.density
    fun dp(value:Int)=(value*density).toInt()
    fun row(text:String,firstAction:String,secondAction:String,stopFirst:Boolean)=
        LayoutInflater.from(context).inflate(R.layout.notification_timer_compact,null,false).apply{
            findViewById<TextView>(R.id.notification_chronometer).text=text
            findViewById<TextView>(R.id.notification_action_1).apply{
                this.text=firstAction
                if(stopFirst)setBackgroundResource(R.drawable.notification_action_stop)
            }
            findViewById<TextView>(R.id.notification_action_2).text=secondAction
            findViewById<TextView>(R.id.notification_action_3).visibility=View.GONE
        }
    val preview=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(12),dp(12),dp(12),dp(12))
        setBackgroundColor(backgroundColor)
        addView(row("R · 50:00","L","R",false),LinearLayout.LayoutParams(-1,dp(56)).apply{bottomMargin=dp(10)})
        addView(row("12:34","Стоп","R",true),LinearLayout.LayoutParams(-1,dp(56)))
        background=GradientDrawable().apply{
            color=android.content.res.ColorStateList.valueOf(backgroundColor)
            cornerRadius=dp(22).toFloat()
        }
    }
    snapshot(preview)
}

class NotificationLightScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig(screenWidth=360,screenHeight=150,orientation=ScreenOrientation.LANDSCAPE,density=Density.MEDIUM,nightMode=NightMode.NOTNIGHT))
    @Test fun notificationRows_areLegibleWithoutOwnFill()=paparazzi.notificationPreview(Color.rgb(247,242,250))
}

class NotificationDarkScreenshotTest {
    @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig(screenWidth=360,screenHeight=150,orientation=ScreenOrientation.LANDSCAPE,density=Density.MEDIUM,nightMode=NightMode.NIGHT))
    @Test fun notificationRows_areLegibleWithoutOwnFill()=paparazzi.notificationPreview(Color.rgb(29,27,32))
}
