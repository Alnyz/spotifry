package com.spotifry.music.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class, HistoryEntity::class, PlayCountEntity::class, ListenEntity::class, SongYearEntity::class, SongNoteEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun playlists(): PlaylistDao
    abstract fun history(): HistoryDao
    abstract fun plays(): PlayCountDao
    abstract fun listens(): ListenDao
    abstract fun backup(): BackupDao
    abstract fun songYears(): SongYearDao
    abstract fun notes(): SongNoteDao

    /** One upgrade as plain SQL. The phone and the desktop each wrap it in the kind of migration their Room needs. */
    class Step(val from: Int, val to: Int, val sql: List<String>)

    companion object {
        val ADD_PLAYS = Step(1, 2, listOf("CREATE TABLE IF NOT EXISTS `plays` (`songId` TEXT NOT NULL, `count` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))"))

        /** Songs already in playlists get their position as the added time, which keeps their order for "Recently added". */
        val ADD_ADDED_AT = Step(2, 3, listOf(
            "ALTER TABLE `playlist_songs` ADD COLUMN `addedAt` INTEGER NOT NULL DEFAULT 0",
            "UPDATE `playlist_songs` SET `addedAt` = `position`",
        ))

        /** The log starts empty: the app never kept seconds heard, so older listening can't be added. */
        val ADD_LISTENS = Step(3, 4, listOf("CREATE TABLE IF NOT EXISTS `listens` (`startedAt` INTEGER NOT NULL, `songId` TEXT NOT NULL, `secondsHeard` INTEGER NOT NULL, `skipped` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`startedAt`))"))

        val ADD_SONG_YEARS = Step(4, 5, listOf("CREATE TABLE IF NOT EXISTS `song_years` (`songId` TEXT NOT NULL, `year` INTEGER, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))"))

        val ADD_SONG_NOTES = Step(5, 6, listOf("CREATE TABLE IF NOT EXISTS `song_notes` (`songId` TEXT NOT NULL, `text` TEXT NOT NULL, `editedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))"))

        /** Every upgrade, oldest first. */
        val STEPS = listOf(ADD_PLAYS, ADD_ADDED_AT, ADD_LISTENS, ADD_SONG_YEARS, ADD_SONG_NOTES)

        /** The upgrades for a Room that opens the file through a SQLite driver, which is how the desktop app does it. */
        fun driverMigrations(): Array<Migration> = STEPS.map { step ->
            object : Migration(step.from, step.to) {
                override fun migrate(connection: SQLiteConnection) = step.sql.forEach { connection.execSQL(it) }
            }
        }.toTypedArray()
    }
}
