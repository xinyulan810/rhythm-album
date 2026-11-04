package deckers.thibault.aves.rhythm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Small local layout prototypes plus conservative settlement OCR. No package-name rules. */
class RhythmClassifier(private val store: RhythmStore) : AutoCloseable {
    private val recognizerHolder = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    override fun close() { if (recognizerHolder.isInitialized()) recognizerHolder.value.close() }
    private val model by lazy {
        JSONObject(store.context.assets.open("rhythm-classifier.json").bufferedReader().use { it.readText() })
    }
    private val prototypes by lazy {
        val a = model.getJSONArray("prototypes")
        (0 until a.length()).map {
            val p = a.getJSONObject(it)
            p.getString("code") to vector(p.getJSONArray("vector"))
        }
    }
    fun classify(id: String) {
        val row = store.get(id)
        if ((row["manualCategory"] as Number).toInt() == 1) return
        RhythmDiagnostics.measure(store, id, "classify_total") {
            val file = File(store.directory(id), "original")
            val f = feature(file)
            var best = prototypes.minByOrNull { distance(f, it.second) }
            var d = best?.let { distance(f, it.second) } ?: 1.0
            var manualMatch = false
            // User corrections are local prototypes. A correction to Other is a negative example.
            for (sample in store.query("manualCategory=1 AND feature!='' AND trashed=0", limit = 240)) {
                val candidate = vector(JSONArray(sample["feature"] as String))
                val delta = distance(f, candidate)
                if (delta < d && delta < .09) { best = (sample["categoryCode"] as String).ifEmpty { "other" } to candidate; d = delta; manualMatch = true }
            }
            val cached = (row["ocrText"] as String).ifBlank { row["ocrLatin"] as String }
            val text = if (cached.isNotBlank()) cached
                else if (manualMatch && best?.first != RhythmScreenRules.BANDORI_GACHA) "" else readText(file)
            if (cached.isBlank() && text.isNotBlank()) store.update(id, "ocrLatin" to text)
            val textCode = textCategory(text)
            var code = textCode ?: best?.first?.takeIf { d <= model.getDouble("threshold") && it != "other" }.orEmpty()
            var evidence = if (d <= model.getDouble("threshold")) "prototype" else "unknown"
            if (textCode != null) evidence = "settlement_layout_text"
            // A weak colour match alone must not override contradictory screen text.
            if (!manualMatch && textCode == null && d > .085 && !isSettlement(text)) code = ""
            // A similar card illustration alone must not classify a new gacha
            // screenshot. Wait for the multi-script OCR result if necessary.
            if (code == RhythmScreenRules.BANDORI_GACHA && textCode != code) code = ""
            // Far from known layouts: only strong result-screen text can create an unnamed group.
            if (code.isEmpty() && isSettlement(text)) {
                evidence = "settlement_text"
                val candidates = store.query("categoryCode LIKE 'unknown-%' AND feature!='' AND trashed=0", limit = 240)
                val near = candidates.map { it to distance(f, vector(JSONArray(it["feature"] as String))) }.minByOrNull { it.second }
                code = if (near != null && near.second < .11) near.first["categoryCode"] as String
                    else "unknown-" + UUID.randomUUID()
            }
            if (code.isNotEmpty()) store.categories.ensureScreenCategory(code, RhythmCategories.defaultName(code, model.getJSONObject("names").optString(code, "")))
            store.update(id, "categoryCode" to code, "classified" to 1,
                "feature" to JSONArray(f.toList()).toString(),
                "eligible" to if (code.isEmpty()) 0 else 1,
                "game" to if (code.isEmpty()) "" else RhythmCategories.defaultName(code, model.getJSONObject("names").optString(code, "")))
            RhythmDiagnostics.event(store, "classification", "id" to id, "code" to code,
                "distance" to d, "evidence" to evidence, "modelVersion" to model.getInt("version"))
        }
    }
    private fun readText(file: File): String {
        val bitmap = sampled(file, 1600)
        return try {
            // Worker IO thread only. Await completion before recycling the input pixels.
            Tasks.await(recognizerHolder.value.process(InputImage.fromBitmap(bitmap, 0))).text
        } finally { bitmap.recycle() }
    }
    companion object {
        /** OCR runs after initial classification; use its CJK evidence without
         * repeating OCR, weakening thresholds, or replacing manual decisions. */
        fun refineFromOcr(store: RhythmStore, id: String, text: String): Boolean {
            val row = store.get(id)
            if ((row["manualCategory"] as Number).toInt() == 1 || (row["trashed"] as Number).toInt() != 0) return false
            val code = textCategory(text) ?: return false
            val current = row["categoryCode"] as String
            if (current == code) return false
            if (current.isNotEmpty() && !(current == "bandori" && code == RhythmScreenRules.BANDORI_GACHA)) return false
            val names = JSONObject(store.context.assets.open("rhythm-classifier.json").bufferedReader().use { it.readText() }).getJSONObject("names")
            val name = RhythmCategories.defaultName(code, names.optString(code, code))
            store.categories.ensureScreenCategory(code, name)
            store.update(id, "categoryCode" to code, "game" to name, "classified" to 1, "eligible" to 1)
            RhythmDiagnostics.event(store, "classification_refined", "id" to id, "code" to code,
                "evidence" to "multiscript_layout_text")
            return true
        }
        fun sampled(file: File, limit: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > limit * 2) sample *= 2
            return requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        }
        fun feature(file: File): IntArray {
            val bitmap = sampled(file, 192)
            val small = Bitmap.createScaledBitmap(bitmap, 96, 64, true)
            try {
                val pixels = IntArray(96 * 64)
                small.getPixels(pixels, 0, 96, 0, 0, 96, 64)
                return IntArray(24 * 16 * 3) { i ->
                    val cell = i / 3; val shift = (2 - i % 3) * 8
                    var sum = 0
                    for (dy in 0..3) for (dx in 0..3)
                        sum += (pixels[((cell / 24) * 4 + dy) * 96 + (cell % 24) * 4 + dx] shr shift) and 255
                    (sum + 8) / 16
                }
            } finally { if (small !== bitmap) small.recycle(); bitmap.recycle() }
        }
        fun vector(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
        fun distance(a: IntArray, b: IntArray): Double {
            if (a.size != b.size || a.isEmpty()) return 1.0
            var sum = 0L
            for (i in a.indices) sum += abs(a[i] - b[i])
            return sum.toDouble() / (a.size * 255)
        }
        fun isSettlement(text: String): Boolean {
            val t = text.uppercase().replace(Regex("\\s+"), " ")
            val judgements = listOf("PERFECT", "GREAT", "GOOD", "MISS", "PURE", "FAR", "LOST", "CRITICAL", "FAST", "LATE")
                .count { Regex("\\b$it\\b").containsMatchIn(t) }
            val result = Regex("RESULT|TRACK.?COMPLETE|TRACK.?LOST|FULL.?COMBO|ALL.?PERFECT|ACCURAC[YG]|ACHIEVEMENT|NEW.?BEST|NEW.?RECORD|RETRY|重.?试|重.?試|重.?新.?开.?始|结.?果|成.?绩").containsMatchIn(t)
            val score = Regex("\\d[\\d,. ]{4,}\\d").containsMatchIn(t)
            return score && judgements >= 2 && result
        }
        fun textCategory(text: String): String? {
            RhythmScreenRules.extraCategory(text)?.let { return it }
            val t = text.uppercase().replace(Regex("[\\s·•'’]"), "")
            // Result-layout anchors transcribed from the reviewed local reference corpus.
            // These never inspect image filenames, process lists, or installed packages.
            if ("MAXRECALL" in t && ("TRACK" in t || "HIGHSCORE" in t) && ("PURE" in t || "LOST" in t)) return "arcaea"
            if ("RKS" in t && "PERFECT" in t && "MISS" in t) return "phira"
            if ("DXSCORE" in t && ("CRIT" in t || "GREAT" in t)) return "astrodx"
            if ("CAPSO" in t && ("TP" in t) && ("PERFECT" in t || "COMBO" in t)) return "cytus2"
            if ("EXPORT" in t && "ACC" in t && ("BEST" in t || "COOL" in t) && "MISS" in t) return "malody"
            if (("ACCURACY" in t || "ACCURACG" in t) && "MAXCOMBO" in t && "PERFECT" in t && ("NEWBEST" in t || "已获得" in t)) return "phigros"
            if ("SCORERANK" in t && "FLICK" in t && ("PERFECT" in t || "GREAT" in t)) return "pjsekai"
            if (("SCORERANK" in t || "FCORERANK" in t) && "SLOW" in t && "MAXCOMBO" in t) return "bandori"
            if ("ONLINERANKING" in t && "PERFECT" in t && "BEST" in t && "MISS" in t) return "dynamite"
            if ("STAGECLEAR" in t && "EXCELLENT" in t && "MAXCOMBO" in t) return "overrapid"
            return null
        }
    }
}
