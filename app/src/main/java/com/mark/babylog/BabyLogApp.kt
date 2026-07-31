package com.mark.babylog

import android.app.Application
import android.util.Log
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

class BabyLogApp : Application() {
    private val backgroundErrors = CoroutineExceptionHandler { _, error ->
        Log.e("BabyLog", "Background sync failed", error)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + backgroundErrors)
    val database by lazy {
        Room.databaseBuilder(this, BabyDatabase::class.java, "baby-log.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
    val repository by lazy { BabyLogRepository(database) }
    val reminderRepository by lazy { ReminderRepository(this) }
    val familySync by lazy { FamilySync(this) }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.periodic(this)
        appScope.launch {
            repository.normalizeLegacyActiveFeeding()
            repository.attachToFamily()
            reminderRepository.ensureDefaults()
            reminderRepository.attachToFamily()
            ReminderScheduler.rescheduleAll(this@BabyLogApp)
            familySync.startRealtime()
            familySync.schedule()
        }
    }

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Version 2 introduced family sync and UUID identities. Rebuild
                    // both related tables so existing v1 rows receive deterministic,
                    // unique remote ids without losing their local primary keys.
                    db.execSQL(
                        "CREATE TABLE `events_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `remoteId` TEXT NOT NULL, `householdId` TEXT, `authorId` TEXT, `authorName` TEXT, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `deletedAt` INTEGER)"
                    )
                    db.execSQL(
                        "INSERT INTO `events_new` (`id`,`type`,`detail`,`startedAt`,`endedAt`,`remoteId`,`householdId`,`authorId`,`authorName`,`updatedAt`,`syncState`,`deletedAt`) SELECT `id`,`type`,`detail`,`startedAt`,`endedAt`,'legacy-event-' || `id`,NULL,NULL,NULL,`startedAt`,'LOCAL_ONLY',NULL FROM `events`"
                    )
                    db.execSQL(
                        "CREATE TABLE `sleep_segments_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` INTEGER NOT NULL, `position` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `remoteId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO `sleep_segments_copy` (`id`,`eventId`,`position`,`startedAt`,`endedAt`,`remoteId`,`updatedAt`,`syncState`) SELECT `id`,`eventId`,`position`,`startedAt`,`endedAt`,'legacy-segment-' || `id`,`startedAt`,'LOCAL_ONLY' FROM `sleep_segments`"
                    )
                    db.execSQL("DROP TABLE `sleep_segments`")
                    db.execSQL("DROP TABLE `events`")
                    db.execSQL("ALTER TABLE `events_new` RENAME TO `events`")
                    db.execSQL(
                        "CREATE TABLE `sleep_segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` INTEGER NOT NULL, `position` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `remoteId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, FOREIGN KEY(`eventId`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL("INSERT INTO `sleep_segments` SELECT * FROM `sleep_segments_copy`")
                    db.execSQL("DROP TABLE `sleep_segments_copy`")
                    db.execSQL(
                        "CREATE UNIQUE INDEX `index_events_remoteId` ON `events` (`remoteId`)"
                    )
                    db.execSQL(
                        "CREATE INDEX `index_sleep_segments_eventId` ON `sleep_segments` (`eventId`)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX `index_sleep_segments_remoteId` ON `sleep_segments` (`remoteId`)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_operations` (`id` TEXT NOT NULL, `command` TEXT NOT NULL, `payload` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `family_membership` (`singleton` INTEGER NOT NULL, `householdId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `inviteCode` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`singleton`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
                    )
                }
            }
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `anchorEpochDay` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `reminder_completions` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_reminder_completions_reminderId` ON `reminder_completions` (`reminderId`)"
                    )
                }
            }
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `reminders_new` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `anchorEpochDay` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "INSERT INTO `reminders_new` (`id`,`title`,`hour`,`minute`,`intervalDays`,`anchorEpochDay`,`enabled`,`createdAt`,`updatedAt`,`deletedAt`,`syncState`) SELECT `id`,`title`,`hour`,`minute`,`intervalDays`,`anchorEpochDay`,`enabled`,`createdAt`,`createdAt`,NULL,'LOCAL_ONLY' FROM `reminders`"
                    )
                    db.execSQL(
                        "CREATE TABLE `reminder_completions_new` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`))"
                    )
                    db.execSQL(
                        "INSERT INTO `reminder_completions_new` (`reminderId`,`scheduledEpochDay`,`completedAt`,`updatedAt`,`deletedAt`,`syncState`) SELECT `reminderId`,`scheduledEpochDay`,`completedAt`,`completedAt`,NULL,'LOCAL_ONLY' FROM `reminder_completions`"
                    )
                    db.execSQL("DROP TABLE `reminder_completions`")
                    db.execSQL("DROP TABLE `reminders`")
                    db.execSQL("ALTER TABLE `reminders_new` RENAME TO `reminders`")
                    db.execSQL(
                        "CREATE TABLE `reminder_completions_final` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL(
                        "INSERT INTO `reminder_completions_final` SELECT * FROM `reminder_completions_new`"
                    )
                    db.execSQL("DROP TABLE `reminder_completions_new`")
                    db.execSQL(
                        "ALTER TABLE `reminder_completions_final` RENAME TO `reminder_completions`"
                    )
                    db.execSQL(
                        "CREATE INDEX `index_reminder_completions_reminderId` ON `reminder_completions` (`reminderId`)"
                    )
                }
            }
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_events_deletedAt_startedAt` ON `events` (`deletedAt`, `startedAt`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_events_type_deletedAt_startedAt` ON `events` (`type`, `deletedAt`, `startedAt`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_reminders_deletedAt_createdAt` ON `reminders` (`deletedAt`, `createdAt`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_reminder_completions_scheduledEpochDay_deletedAt` ON `reminder_completions` (`scheduledEpochDay`, `deletedAt`)"
                    )
                }
            }
    }
}
