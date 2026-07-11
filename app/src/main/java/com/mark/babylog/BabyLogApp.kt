package com.mark.babylog

import android.app.Application
import androidx.room.Room
import com.mark.babylog.data.BabyDatabase

class BabyLogApp : Application() {
    val database by lazy { Room.databaseBuilder(this, BabyDatabase::class.java, "baby-log.db").build() }
}
