package deckers.thibault.aves.rhythm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.withLock

/** Explicit DUMP-protected label import; never automatically runs on upgrade. */
object RhythmReviewLabels {
    fun run(store: RhythmStore, output: File, apply: Boolean) = store.operations.withLock {
        val input = File(output, "review-labels.json")
        require(input.length() in 1..1_000_000)
        val spec = JSONObject(input.readText())
        val directory = spec.getString("directory")
        require(directory.matches(Regex("other-review-[0-9]+")))
        val folder = File(output, directory)
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        require(manifest.getString("run") == spec.getString("run"))
        val records = manifest.getJSONArray("records")
        val byId = (0 until records.length()).associate { records.getJSONObject(it).let { r -> r.getString("id") to r } }
        val assignments = spec.getJSONArray("assignments")
        val seen = mutableSetOf<String>()
        val before = JSONArray()
        val result = JSONArray()
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            val id = assignment.getString("id")
            require(seen.add(id))
            val original = requireNotNull(byId[id])
            require(assignment.getString("sha256") == original.getString("sha256") &&
                assignment.getString("number") == original.getString("number"))
            val code = assignment.getString("code")
            require(code in setOf("arcaea", "bandori", "milthm", RhythmScreenRules.BANDORI_GACHA))
            val row = store.get(id)
            val text = (row["ocrText"] as String).ifBlank { row["ocrLatin"] as String }
            val eligible = (row["manualCategory"] as Number).toInt() == 0 &&
                (row["trashed"] as Number).toInt() == 0 && row["status"] == "compressed" &&
                (row["categoryCode"] as String).isBlank()
            before.put(JSONObject(row))
            result.put(JSONObject().put("id", id).put("number", original.getString("number"))
                .put("expected", code).put("predicted", RhythmClassifier.textCategory(text) ?: "")
                .put("willApply", eligible))
        }
        val candidates = JSONArray()
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            if (record.getString("id") in seen) continue
            val code = RhythmClassifier.textCategory(record.optString("ocrText").ifBlank { record.optString("ocrLatin") })
            if (code != null) candidates.put(JSONObject().put("number", record.getString("number"))
                .put("id", record.getString("id")).put("predicted", code))
        }
        val report = JSONObject().put("run", spec.getString("run")).put("applied", false)
            .put("labelsSha256", spec.getString("labelsSha256")).put("checks", result)
            .put("unlabeledCandidates", candidates)
        if (apply) {
            // Archive the exact prior rows before the transaction. Repeat calls
            // preserve the first backup and skip now-manual records.
            val backup = File(folder, "before-label-import.json")
            if (!backup.exists()) RhythmStore.syncWrite(backup, before.toString(2))
            store.writableDatabase.beginTransaction()
            try {
                var changed = 0
                for (i in 0 until assignments.length()) {
                    if (!result.getJSONObject(i).getBoolean("willApply")) continue
                    val assignment = assignments.getJSONObject(i)
                    val code = assignment.getString("code")
                    val name = RhythmCategories.defaultName(code, if (code == "arcaea") "Arcaea" else code)
                    store.categories.ensureScreenCategory(code, name)
                    store.update(assignment.getString("id"), "categoryCode" to code,
                        "game" to name, "eligible" to 1, "classified" to 1, "manualCategory" to 1)
                    changed++
                }
                store.writableDatabase.setTransactionSuccessful()
                report.put("applied", true).put("changed", changed)
            } finally { store.writableDatabase.endTransaction() }
        }
        RhythmStore.syncWrite(File(folder, if (apply) "label-apply-result.json" else "classification-check.json"), report.toString(2))
    }
}
