package com.spotifry.music.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.androidMigration
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongNoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongNoteDbTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    @Test fun putReplacesAndDeleteRemoves() = runBlocking {
        assertNull(db.notes().observe("a").first())
        db.notes().put(SongNoteEntity("a", "first", 1))
        db.notes().put(SongNoteEntity("a", "second", 2))
        db.notes().put(SongNoteEntity("b", "other", 3))
        assertEquals("second", db.notes().observe("a").first())
        db.notes().delete("a")
        assertNull(db.notes().observe("a").first())
        assertEquals("other", db.notes().observe("b").first())
    }

    @Test fun migrationKeepsTheLibraryAndAddsTheTable() = runBlocking {
        db.songs().upsert(SongEntity("s", "T", "A", 100, null, null, null, null))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TABLE song_notes")
        androidMigration(AppDatabase.ADD_SONG_NOTES).migrate(sql)
        db.notes().put(SongNoteEntity("s", "kept", 1))
        assertEquals("kept", db.notes().observe("s").first())
        assertEquals("T", db.songs().get("s")!!.title)
    }
}
