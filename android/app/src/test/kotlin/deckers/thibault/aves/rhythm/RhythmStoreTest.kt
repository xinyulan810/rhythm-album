package deckers.thibault.aves.rhythm

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.concurrent.withLock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RhythmStoreTest {
    @Test fun v8MigrationPreservesRecognitionAndCreatesIndependentLayoutFields() {
        val context = RuntimeEnvironment.getApplication()
        val old = context.openOrCreateDatabase("rhythm-benchmark-layout-v8.db",Context.MODE_PRIVATE,null)
        old.execSQL("CREATE TABLE images(id TEXT PRIMARY KEY,songLayoutCode TEXT,song TEXT,songManual INTEGER)")
        old.execSQL("INSERT INTO images VALUES('sample','arcaea','Confirmed song',1)")
        old.version = 8
        old.close()
        val upgraded = RhythmStore(context,true,"layout-v8")
        try {
            upgraded.readableDatabase.rawQuery("SELECT * FROM images",null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Confirmed song",c.getString(c.getColumnIndexOrThrow("song")))
                assertEquals(1,c.getInt(c.getColumnIndexOrThrow("songManual")))
                assertEquals("arcaea",c.getString(c.getColumnIndexOrThrow("songLayoutCode")))
                assertEquals("",c.getString(c.getColumnIndexOrThrow("songLayoutKey")))
                assertEquals("",c.getString(c.getColumnIndexOrThrow("songLayoutAnchor")))
            }
            assertTrue(upgraded.prefs.getBoolean("showSongMissing",true))
            assertTrue(upgraded.prefs.edit().putBoolean("showSongMissing",false).commit())
        } finally { upgraded.close() }
        val reopened = RhythmStore(context,true,"layout-v8")
        try { assertFalse(reopened.prefs.getBoolean("showSongMissing",true)) }
        finally { reopened.close() }
    }
    @Test fun v7MigrationIndexesUnconfirmedAndSingleCharacterTitles() {
        val context = RuntimeEnvironment.getApplication()
        val initial = RhythmStore(context,true,"song-v7")
        initial.enqueue("content://v7/one","Screenshot1.jpg","",false)
        initial.enqueue("content://v7/two","Screenshot2.jpg","",false)
        val ids = initial.query().map { it["id"] as String }
        ids.forEach { initial.update(it,"songRaw" to "雪","song" to "雪","status" to "ready") }
        initial.writableDatabase.execSQL("DROP TABLE image_song_grams")
        initial.writableDatabase.version = 7
        initial.close()
        val upgraded = RhythmStore(context,true,"song-v7")
        try {
            assertEquals(listOf(ids[1]),upgraded.songs.relatedIds(ids[0]))
            upgraded.update(ids[1],"song" to "Other title","songRaw" to "Other title")
            assertTrue(upgraded.songs.relatedIds(ids[0]).isEmpty())
        } finally { upgraded.close() }
    }

    @Test fun songRecognitionQueueIsBoundedAndSelectsOnlyEligibleAutomaticRows() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"song-queue")
        try {
            repeat(6) { store.enqueue("content://queue/$it","Screenshot$it.jpg","",false) }
            val rows = store.query()
            rows.forEach { store.update(it["id"] as String,"status" to "ready","classified" to 1,
                "ocrVersion" to 1,"songVersion" to 0) }
            store.update(rows[0]["id"] as String,"songManual" to 1)
            store.update(rows[1]["id"] as String,"songError" to "failed")
            store.update(rows[2]["id"] as String,"trashed" to 1)
            assertEquals(3,store.songQueue(3).size)
            assertEquals(1,store.songQueue(0).size)
            assertEquals(3,store.songQueue(40).size)
        } finally { store.close() }
    }

    @Test fun manualOverrideGenerationCancelsStaleSongWrites() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"song-generation")
        try {
            store.enqueue("content://generation/1","Screenshot.jpg","",false)
            val id = store.query().single()["id"] as String
            val started = store.songGeneration
            store.operations.withLock { store.songGeneration++ }
            var applied = false
            assertFalse(store.commitSongResult(id,started) { applied = true })
            assertFalse(applied)
            assertTrue(store.commitSongResult(id,store.songGeneration) { applied = true })
            assertTrue(applied)
        } finally { store.close() }
    }
    @Test
    fun upgradesV1AndPreservesSourceDeduplicationAndReferencedAnchors() {
        val context = RuntimeEnvironment.getApplication()
        val old = context.openOrCreateDatabase("rhythm-library-v1.db", Context.MODE_PRIVATE, null)
        old.execSQL("""CREATE TABLE images(
            id TEXT PRIMARY KEY,source TEXT NOT NULL UNIQUE,name TEXT NOT NULL,
            game TEXT NOT NULL DEFAULT '',song TEXT NOT NULL DEFAULT '',tags TEXT NOT NULL DEFAULT '',
            album TEXT NOT NULL DEFAULT '',eligible INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'queued',
            error TEXT NOT NULL DEFAULT '',originalBytes INTEGER NOT NULL DEFAULT 0,storedBytes INTEGER NOT NULL DEFAULT 0,
            width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0,anchor INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL)""")
        val anchor = "11111111-1111-1111-1111-111111111111"
        old.execSQL("INSERT INTO images(id,source,name,created) VALUES(?,?,?,?)",
            arrayOf<Any>(anchor, "content://com.android.providers.media.documents/document/image%3A42", "Screenshot_old.png", 1234))
        old.version = 1
        old.close()

        val store = RhythmStore.get(context)
        val migrated = store.get(anchor)
        assertEquals(1234L, migrated["taken"])
        assertEquals(0L, migrated["trashed"])
        assertEquals(0L, migrated["ocrVersion"])
        assertEquals("auto", migrated["achievementOverride"])
        store.update(anchor, "tags" to "personal", "achievementOverride" to "FC",
            "ocrText" to "ALL PERFECT", "ocrVersion" to 1, "achievement" to "AP")
        assertEquals("FC", store.get(anchor)["achievementOverride"])
        assertEquals("personal", store.get(anchor)["tags"])
        assertFalse(store.enqueue("content://media/external/images/media/42", "Screenshot_old.png", "", false))
        assertTrue(store.enqueue("content://media/external/images/media/43", "Screenshot_new.png", "", false, 5678))
        val target = store.query("sourceKey=?", arrayOf("media:43"), 1).first()["id"] as String
        assertEquals(target, store.query(limit = 1).first()["id"])
        assertEquals(anchor, store.query(limit = 1, ascending = true).first()["id"])

        val anchorDir = store.directory(anchor).apply { mkdirs() }
        File(anchorDir, "original").writeText("recovery")
        File(store.directory(target).apply { mkdirs() }, "manifest.json").writeText(
            """{"version":1,"stripes":[{"reference":"$anchor"}]}""")
        store.update(anchor, "trashed" to 2)
        store.collectDeleted()
        assertTrue("Live delta retains deleted anchor", File(anchorDir, "original").exists())
        assertFalse("Tombstone prevents auto resurrection",
            store.enqueue("content://media/external/images/media/42", "Screenshot_old.png", "", false))

        store.update(target, "trashed" to 1)
        store.collectDeleted()
        assertTrue("Recycle-bin image still retains its anchor", anchorDir.exists())
        store.update(target, "trashed" to 0)
        assertEquals(1L, store.count("trashed=0", emptyArray()))
        store.update(target, "trashed" to 2)
        store.collectDeleted()
        assertFalse(anchorDir.exists())
        assertFalse(store.directory(target).exists())
        assertEquals(2L, store.count("trashed=2", emptyArray()))
    }
}
