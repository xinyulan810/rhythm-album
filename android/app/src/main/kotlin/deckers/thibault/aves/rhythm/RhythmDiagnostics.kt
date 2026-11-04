package deckers.thibault.aves.rhythm

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Bounded local diagnostics; no filenames, source URIs or image contents. */
object RhythmDiagnostics {
    @Synchronized
    fun event(store: RhythmStore, stage: String, vararg fields: Pair<String, Any>) {
        try {
            val line = JSONObject().put("time", System.currentTimeMillis()).put("stage", stage)
            fields.forEach { (key, value) -> line.put(key, value) }
            val file = File(store.root, "performance.jsonl")
            if (file.length() > 512 * 1024) {
                val previous = File(store.root, "performance.previous.jsonl")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText("$line\n")
            Log.i("RhythmPerf", line.toString())
        } catch (e: Exception) {
            Log.w("RhythmPerf", "Diagnostics unavailable", e)
        }
    }

    fun <T> measure(store: RhythmStore, id: String, stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            event(store, stage, "id" to id, "ms" to (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0, "ok" to ok)
        }
    }

    @Synchronized
    fun read(store: RhythmStore): String =
        listOf("performance.previous.jsonl", "performance.jsonl").map { File(store.root, it) }
            .filter { it.exists() }.joinToString("\n") { it.readText() }
}
