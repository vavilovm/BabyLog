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
    val database by lazy { Room.databaseBuilder(this, BabyDatabase::class.java, "baby-log.db").addMigrations(MIGRATION_2_3,MIGRATION_3_4).fallbackToDestructiveMigration().build() }
    val repository by lazy { BabyLogRepository(database) }
    val reminderRepository by lazy { ReminderRepository(this) }
    val familySync by lazy { FamilySync(this) }
    override fun onCreate(){super.onCreate();SyncWorker.periodic(this);appScope.launch{reminderRepository.ensureDefaults();reminderRepository.attachToFamily();ReminderScheduler.rescheduleAll(this@BabyLogApp);familySync.startRealtime();familySync.schedule()}}

    companion object{
        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `anchorEpochDay` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminder_completions` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_completions_reminderId` ON `reminder_completions` (`reminderId`)")
            }
        }
        val MIGRATION_3_4=object:Migration(3,4){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE `reminders_new` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `anchorEpochDay` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `reminders_new` (`id`,`title`,`hour`,`minute`,`intervalDays`,`anchorEpochDay`,`enabled`,`createdAt`,`updatedAt`,`deletedAt`,`syncState`) SELECT `id`,`title`,`hour`,`minute`,`intervalDays`,`anchorEpochDay`,`enabled`,`createdAt`,`createdAt`,NULL,'LOCAL_ONLY' FROM `reminders`")
                db.execSQL("CREATE TABLE `reminder_completions_new` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`))")
                db.execSQL("INSERT INTO `reminder_completions_new` (`reminderId`,`scheduledEpochDay`,`completedAt`,`updatedAt`,`deletedAt`,`syncState`) SELECT `reminderId`,`scheduledEpochDay`,`completedAt`,`completedAt`,NULL,'LOCAL_ONLY' FROM `reminder_completions`")
                db.execSQL("DROP TABLE `reminder_completions`")
                db.execSQL("DROP TABLE `reminders`")
                db.execSQL("ALTER TABLE `reminders_new` RENAME TO `reminders`")
                db.execSQL("CREATE TABLE `reminder_completions_final` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO `reminder_completions_final` SELECT * FROM `reminder_completions_new`")
                db.execSQL("DROP TABLE `reminder_completions_new`")
                db.execSQL("ALTER TABLE `reminder_completions_final` RENAME TO `reminder_completions`")
                db.execSQL("CREATE INDEX `index_reminder_completions_reminderId` ON `reminder_completions` (`reminderId`)")
            }
        }
    }
}
