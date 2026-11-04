package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RhythmAlbumTest {
    @Test fun timelineUnnamedLastCompleteSelectionAndAtomicMergeUndo() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "album-test")
        fun add(code: String, name: String, taken: Long): String {
            store.categories.ensure(code, name)
            store.enqueue("test:$taken", "Screenshot", "", false, taken)
            val id = store.query("source=?", arrayOf("test:$taken"), 1).single()["id"] as String
            store.update(id, "categoryCode" to code, "status" to "compressed", "classified" to 1)
            return id
        }
        add("old", "Zulu", 10)
        add("new", "Alpha", 20)
        add("unknown", "", 99)
        repeat(205) { add("old", "Zulu", 1000L + it) }
        assertEquals(listOf("old", "new", "unknown"), store.categories.albums().map { it["code"] })
        assertEquals(206, store.categories.ids(listOf("old")).size)
        assertEquals(208, store.categories.ids(listOf("", "old")).size)
        store.categories.merge(listOf("old", "unknown"), "new")
        assertEquals(208, store.categories.ids(listOf("new")).size)
        assertEquals(1, store.categories.albums().size)
        store.categories.undo()
        assertEquals(3, store.categories.albums().size)
        assertEquals(206, store.categories.ids(listOf("old")).size)
        store.close()
    }
}
