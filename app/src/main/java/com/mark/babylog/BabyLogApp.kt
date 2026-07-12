package com.mark.babylog

import android.app.Application
import androidx.room.Room
import com.mark.babylog.data.BabyDatabase
import com.mark.babylog.data.BabyLogRepository
import com.mark.babylog.sync.FamilySync
import com.mark.babylog.sync.SyncWorker
import kotlinx.coroutines.*

class BabyLogApp : Application() {
    val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, BabyDatabase::class.java, "baby-log.db").fallbackToDestructiveMigration().build() }
    val repository by lazy { BabyLogRepository(database) }
    val familySync by lazy { FamilySync(this) }
    override fun onCreate(){super.onCreate();SyncWorker.periodic(this);appScope.launch{familySync.startRealtime()}}
}
