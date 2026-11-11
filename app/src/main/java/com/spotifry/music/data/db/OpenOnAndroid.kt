package com.spotifry.music.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The phone's Room opens the file through Android's own SQLite and asks for this kind of migration; without it an
 * upgrade stops with "requires overriding the migrate(SupportSQLiteDatabase) function".
 */
fun androidMigration(step: AppDatabase.Step): Migration = object : Migration(step.from, step.to) {
    override fun migrate(db: SupportSQLiteDatabase) = step.sql.forEach(db::execSQL)
}

/** Opens the phone's database file with every upgrade the shared module knows. */
fun AppDatabase.Companion.create(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "music.db").addMigrations(*STEPS.map(::androidMigration).toTypedArray()).build()
