package com.mark.babylog

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mark.babylog.data.BabyDatabase
import com.mark.babylog.data.BabyLogRepository
import com.mark.babylog.reminders.ReminderRepository
import com.mark.babylog.reminders.ReminderScheduler
import com.mark.babylog.sync.FamilySync
import com.mark.babylog.sync.SyncWorker
import kotlinx.coroutines.*
import android.util.Log

class BabyLogApp : Application() {
    private val backgroundErrors=CoroutineExceptionHandler{_,error->Log.e("BabyLog","Background sync failed",error)}
    val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO+backgroundErrors)
    val database by lazy { Room.databaseBuilder(this, BabyDatabase::class.java, "baby-log.db").addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build() }
    val repository by lazy { BabyLogRepository(database) }
    val reminderRepository by lazy { ReminderRepository(this) }
    val familySync by lazy { FamilySync(this) }
    override fun onCreate(){super.onCreate();SyncWorker.periodic(this);appScope.launch{reminderRepository.ensureDefaults();ReminderScheduler.rescheduleAll(this@BabyLogApp);familySync.startRealtime()}}

    companion object{
        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `anchorEpochDay` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminder_completions` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_completions_reminderId` ON `reminder_completions` (`reminderId`)")
            }
        }
    }
}
