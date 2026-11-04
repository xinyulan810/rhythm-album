package deckers.thibault.aves.rhythm

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RhythmStoreTest {
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
