package com.mark.babylog

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ReminderMigrationTest{
    @Test fun migration3To4PreservesReminderAndCompletion(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="reminder-migration-${UUID.randomUUID()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(3){
            override fun onCreate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE `reminders` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `anchorEpochDay` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE `reminder_completions` (`reminderId` TEXT NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`reminderId`, `scheduledEpochDay`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX `index_reminder_completions_reminderId` ON `reminder_completions` (`reminderId`)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        try{
            val db=helper.writableDatabase
            db.execSQL("INSERT INTO `reminders` (`id`,`title`,`hour`,`minute`,`intervalDays`,`anchorEpochDay`,`enabled`,`createdAt`) VALUES (?,?,?,?,?,?,?,?)",arrayOf("r1","Витамин",9,15,1,20_000,1,123L))
            db.execSQL("INSERT INTO `reminder_completions` (`reminderId`,`scheduledEpochDay`,`completedAt`) VALUES (?,?,?)",arrayOf("r1",20_000,456L))
            BabyLogApp.MIGRATION_3_4.migrate(db)
            db.query("SELECT * FROM `reminders` WHERE `id`='r1'").use{cursor->
                cursor.moveToFirst()
                assertEquals("Витамин",cursor.getString(cursor.getColumnIndexOrThrow("title")))
                assertEquals(123L,cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
                assertEquals("LOCAL_ONLY",cursor.getString(cursor.getColumnIndexOrThrow("syncState")))
                assertNull(if(cursor.isNull(cursor.getColumnIndexOrThrow("deletedAt")))null else cursor.getLong(cursor.getColumnIndexOrThrow("deletedAt")))
            }
            db.query("SELECT * FROM `reminder_completions` WHERE `reminderId`='r1'").use{cursor->cursor.moveToFirst();assertEquals(456L,cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")));assertEquals("LOCAL_ONLY",cursor.getString(cursor.getColumnIndexOrThrow("syncState")))}
            db.query("PRAGMA foreign_key_list(`reminder_completions`)").use{cursor->cursor.moveToFirst();assertEquals("reminders",cursor.getString(cursor.getColumnIndexOrThrow("table")))}
        }finally{helper.close();context.deleteDatabase(name)}
    }
}
