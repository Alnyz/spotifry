package com.spotifry.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class, HistoryEntity::class, PlayCountEntity::class, ListenEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun playlists(): PlaylistDao
    abstract fun history(): HistoryDao
    abstract fun plays(): PlayCountDao
    abstract fun listens(): ListenDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "music.db").addMigrations(ADD_PLAYS, ADD_ADDED_AT, ADD_LISTENS).build()

        val ADD_PLAYS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `plays` (`songId` TEXT NOT NULL, `count` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))")
            }
        }

        /** Songs already in playlists get their position as the added time, which keeps their order for "Recently added". */
        val ADD_ADDED_AT = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlist_songs` ADD COLUMN `addedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `playlist_songs` SET `addedAt` = `position`")
            }
        }

        /** The log starts empty: the app never kept seconds heard, so older listening can't be added. */
        val ADD_LISTENS = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `listens` (`startedAt` INTEGER NOT NULL, `songId` TEXT NOT NULL, `secondsHeard` INTEGER NOT NULL, `skipped` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`startedAt`))")
            }
        }
    }
}
