package com.mark.babylog

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.data.BabyDatabase
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @Test
    fun migrationFromVersionOnePreservesEventsAndSegments() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "baby-log-v1-${UUID.randomUUID()}.db"
        createVersionOneDatabase(context, name)

        val database =
            Room.databaseBuilder(context, BabyDatabase::class.java, name)
                .addMigrations(
                    BabyLogApp.MIGRATION_1_2,
                    BabyLogApp.MIGRATION_2_3,
                    BabyLogApp.MIGRATION_3_4,
                    BabyLogApp.MIGRATION_4_5,
                )
                .allowMainThreadQueries()
                .build()

        try {
            val event = database.events().allForTest().single()
            val segment = database.events().allSegmentsForTest().single()
            assertEquals(7L, event.id)
            assertEquals("LEFT", event.detail)
            assertEquals("legacy-event-7", event.remoteId)
            assertEquals(11L, segment.id)
            assertEquals(7L, segment.eventId)
            assertEquals("legacy-segment-11", segment.remoteId)

            database.openHelper.readableDatabase.query("PRAGMA index_list(`events`)").use { cursor
                ->
                val names = buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
                assertTrue("history index missing", "index_events_deletedAt_startedAt" in names)
                assertTrue(
                    "typed history index missing",
                    "index_events_type_deletedAt_startedAt" in names,
                )
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersionOneDatabase(context: Context, name: String) {
        val helper =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(name)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(1) {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    db.execSQL(
                                        "CREATE TABLE IF NOT EXISTS `events` " +
                                            "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                            "`type` TEXT NOT NULL, `detail` TEXT NOT NULL, " +
                                            "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER)"
                                    )
                                    db.execSQL(
                                        "CREATE TABLE IF NOT EXISTS `sleep_segments` " +
                                            "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                            "`eventId` INTEGER NOT NULL, `position` TEXT NOT NULL, " +
                                            "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                                            "FOREIGN KEY(`eventId`) REFERENCES `events`(`id`) " +
                                            "ON UPDATE NO ACTION ON DELETE CASCADE)"
                                    )
                                    db.execSQL(
                                        "CREATE INDEX IF NOT EXISTS `index_sleep_segments_eventId` " +
                                            "ON `sleep_segments` (`eventId`)"
                                    )
                                }

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            }
                        )
                        .build()
                )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `events` (`id`,`type`,`detail`,`startedAt`,`endedAt`) " +
                    "VALUES (7,'SLEEP','LEFT',1000,2000)"
            )
            db.execSQL(
                "INSERT INTO `sleep_segments` " +
                    "(`id`,`eventId`,`position`,`startedAt`,`endedAt`) " +
                    "VALUES (11,7,'LEFT',1000,2000)"
            )
        } finally {
            helper.close()
        }
    }
}
