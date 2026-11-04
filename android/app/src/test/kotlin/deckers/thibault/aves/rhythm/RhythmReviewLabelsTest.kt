package deckers.thibault.aves.rhythm

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RhythmReviewLabelsTest {
    @Test fun dryRunIsReadOnlyAndImportPreservesManualChoicesAndStoredArchives() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "label-import")
        val output = File(store.root, "review").apply { mkdirs() }
        val folder = File(output, "other-review-123").apply { mkdirs() }
        val records = JSONArray()
        val assignments = JSONArray()
        val ids = (0..2).map { index ->
            store.enqueue("labels:$index", "$index", "", false)
            val id = store.query("source=?", arrayOf("labels:$index")).single()["id"] as String
            store.update(id, "status" to "compressed", "storedBytes" to 1234, "classified" to 1,
                "manualCategory" to if (index == 1) 1 else 0, "trashed" to if (index == 2) 1 else 0)
            val item = JSONObject().put("id", id).put("number", "O$index").put("sha256", "test-$index")
            records.put(item)
            assignments.put(JSONObject(item.toString()).put("code", RhythmScreenRules.BANDORI_GACHA))
            id
        }
        File(folder, "manifest.json").writeText(JSONObject().put("run", "123").put("records", records).toString())
        File(output, "review-labels.json").writeText(JSONObject().put("run", "123")
            .put("directory", folder.name).put("labelsSha256", "test").put("assignments", assignments).toString())
        RhythmReviewLabels.run(store, output, false)
        assertEquals("", store.get(ids[0])["categoryCode"])
        assertFalse(File(folder, "before-label-import.json").exists())
        RhythmReviewLabels.run(store, output, true)
        val backup = File(folder, "before-label-import.json").readText()
        assertEquals(RhythmScreenRules.BANDORI_GACHA, store.get(ids[0])["categoryCode"])
        assertEquals(1L, store.get(ids[0])["manualCategory"])
        assertEquals("compressed", store.get(ids[0])["status"])
        assertEquals(1234L, store.get(ids[0])["storedBytes"])
        assertEquals("", store.get(ids[1])["categoryCode"])
        assertEquals("", store.get(ids[2])["categoryCode"])
        RhythmReviewLabels.run(store, output, true)
        assertEquals(backup, File(folder, "before-label-import.json").readText())
        assertEquals(0, JSONObject(File(folder, "label-apply-result.json").readText()).getInt("changed"))
        store.close()
    }
}
