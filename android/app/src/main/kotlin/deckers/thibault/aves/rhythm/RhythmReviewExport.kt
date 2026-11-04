package deckers.thibault.aves.rhythm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.withLock

/** Explicit ADB review export. Reads the library; never changes labels or sources. */
object RhythmReviewExport {
    const val WHERE = "trashed=0 AND status='compressed' AND NOT (${RhythmCategories.VISIBLE})"

    fun run(store: RhythmStore, output: File, stopped: () -> Boolean) {
        val run = System.currentTimeMillis().toString()
        val folder = File(output, "other-review-$run").apply { check(mkdirs()) }
        val images = File(folder, "images").apply { check(mkdirs()) }
        val rows = store.operations.withLock { store.query(WHERE, limit = Int.MAX_VALUE) }
        val records = JSONArray()
        var completed = 0
        var failed = 0
        fun status(done: Boolean) {
            RhythmStore.syncWrite(File(output, "review-status.json"), JSONObject()
                .put("run", run).put("directory", folder.name).put("total", rows.size)
                .put("completed", completed).put("failed", failed).put("complete", done)
                .put("cancelled", stopped()).toString(2))
        }
        status(false)
        for ((index, row) in rows.withIndex()) {
            if (stopped()) break
            val id = row["id"] as String
            val number = "O%04d".format(java.util.Locale.ROOT, index + 1)
            val record = JSONObject().put("number", number).put("id", id)
                .put("file", "images/$number.png")
                .put("reason", when {
                    (row["manualCategory"] as Number).toInt() == 1 -> "manual-other"
                    (row["categoryCode"] as String).isNotEmpty() -> "album-mapped-to-other"
                    else -> "auto-unrecognized"
                })
            for (key in listOf("name", "taken", "width", "height", "categoryCode", "manualCategory",
                "classified", "game", "eligible", "ocrText", "ocrLatin", "feature", "ocrVersion", "ocrError")) {
                record.put(key, row[key])
            }
            try {
                store.operations.withLock {
                    val current = store.get(id)
                    check((current["trashed"] as Number).toInt() == 0) { "Image moved to trash during export" }
                    val destination = File(images, "$number.png")
                    RhythmCodec.render(store, id, destination, maxDimension = 0)
                    record.put("sha256", RhythmStore.hash(destination)).put("bytes", destination.length())
                }
                completed++
            } catch (e: Exception) {
                record.put("error", e.message ?: e.javaClass.simpleName)
                failed++
            }
            records.put(record)
            RhythmStore.syncWrite(File(folder, "manifest.json"), JSONObject()
                .put("version", 1).put("run", run).put("records", records).toString(2))
            status(false)
        }
        // Also emit a manifest for an empty collection.
        RhythmStore.syncWrite(File(folder, "manifest.json"), JSONObject()
            .put("version", 1).put("run", run).put("records", records).toString(2))
        status(true)
    }
}
