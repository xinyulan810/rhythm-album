package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class RhythmManagedTest {
    @Test fun managedModeFinishesOcrBeforeStartingNextPendingImage() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "managed-pipeline")
        store.prefs.edit().putBoolean("managedScreenshots", true).commit()
        store.enqueue("test:a", "Screenshot_a.png", "", false)
        store.enqueue("test:b", "Screenshot_b.png", "", false)
        val rows = store.query()
        val completed = rows[0]["id"] as String
        val pending = rows[1]["id"] as String
        store.update(completed, "status" to "compressed", "classified" to 1)
        store.update(pending, "status" to "pending")
        assertEquals(completed, store.next()!!["id"])
        store.update(completed, "ocrVersion" to 1)
        assertEquals(pending, store.next()!!["id"])
        store.close()
    }
    @Test fun onlyCompletedScreenshotsAreCandidatesAndModeDefaultsOff() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "managed-test")
        assertFalse(store.prefs.getBoolean("managedScreenshots", false))
        assertFalse(store.prefs.getBoolean("managedChoiceMade", false))
        store.enqueue("content://media/external/images/media/123", "Screenshot_1.png", "", false)
        val id = store.query().single()["id"] as String
        assertEquals(0L, RhythmManaged.count(store))
        store.update(id, "status" to "compressed", "ocrVersion" to 1)
        assertEquals(1L, RhythmManaged.count(store))
        store.update(id, "trashed" to 1)
        assertEquals(0L, RhythmManaged.count(store))
        store.update(id, "trashed" to 0, "sourceRemoved" to 1)
        assertEquals(0L, RhythmManaged.count(store))
        store.update(id, "sourceRemoved" to 0, "sourceDeleteError" to "changed")
        assertEquals(0L, RhythmManaged.count(store))
        assertEquals(1L, RhythmManaged.count(store, true))
        store.close()
    }
    @Test fun arbitraryUrisCannotBecomeDeletionTargets() {
        assertNotNull(RhythmManaged.sourceUri("content://media/external_primary/images/media/42"))
        for (uri in listOf("file:///sdcard/a.png", "content://other/external/images/media/42",
            "content://media/external/images/media", "content://media/external/images/media/42?x=1",
            "content://media/external/video/media/42")) assertNull(RhythmManaged.sourceUri(uri))
    }
}
