package deckers.thibault.aves.rhythm

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import kotlin.concurrent.withLock

/** Bundled, offline models reused for one background batch. */
class RhythmOcr(private val store: RhythmStore) : AutoCloseable {
    private val latin = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val japanese = lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    fun recognize(id: String) {
        val row = store.get(id)
        val source = File(store.directory(id), "original").takeIf { it.exists() } ?: RhythmImages.full(store, id)
        val bitmap = RhythmClassifier.sampled(source, 1800)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val results = read(image)
            val texts = results.map { it.second.text }
            val text = texts.flatMap { it.lines() }.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n").take(24000)
            val votes = texts.map(RhythmAchievement::detect).filter(String::isNotBlank).distinct()
            val tag = if (texts.any(RhythmAchievement::ambiguous)) "" else votes.singleOrNull().orEmpty()
            store.update(id, "ocrText" to text, "ocrSearch" to RhythmAchievement.searchKey(text),
                "ocrVersion" to 1, "ocrError" to "", "achievement" to tag,
                "achievementEvidence" to if (tag.isEmpty()) "" else texts.first { RhythmAchievement.detect(it) == tag }.take(4000))
            RhythmClassifier.refineFromOcr(store, id, text)
            recognizeSongSafely(id, bitmap, results)
        } finally { bitmap.recycle() }
    }
    private fun read(image: InputImage): List<Pair<String, Text>> = listOf(
        "latin" to Tasks.await(latin.value.process(image)),
        "chinese" to Tasks.await(chinese.value.process(image)),
        "japanese" to Tasks.await(japanese.value.process(image)))

    private fun lines(bitmap: Bitmap, results: List<Pair<String, Text>>, ox: Int = 0, oy: Int = 0) =
        results.flatMap { (engine, text) -> text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { b -> RhythmSongMatcher.Line(line.text,
                (b.left + ox).toDouble()/bitmap.width, (b.top + oy).toDouble()/bitmap.height,
                (b.right + ox).toDouble()/bitmap.width, (b.bottom + oy).toDouble()/bitmap.height, engine) }
        } }
    private fun cropLines(bitmap: Bitmap, box: RhythmSongLayouts.Box): List<RhythmSongMatcher.Line> {
        val x = (box.x * bitmap.width).toInt().coerceIn(0,bitmap.width-1)
        val y = (box.y * bitmap.height).toInt().coerceIn(0,bitmap.height-1)
        val width = ((box.right * bitmap.width).toInt()-x).coerceIn(1,bitmap.width-x)
        val height = ((box.bottom * bitmap.height).toInt()-y).coerceIn(1,bitmap.height-y)
        val crop = Bitmap.createBitmap(bitmap,x,y,width,height)
        return try { lines(bitmap,read(InputImage.fromBitmap(crop,0)),x,y) }
        finally { if (crop !== bitmap) crop.recycle() }
    }
    /** Scanning records layout evidence, but never confirms or overwrites a song. */
    fun inspectLayout(id: String, box: RhythmSongLayouts.Box?): Map<String, Any> {
        val source = File(store.directory(id),"original").takeIf { it.exists() } ?: RhythmImages.full(store,id)
        val bitmap = RhythmClassifier.sampled(source,2400)
        return try {
            val all = if (box == null) lines(bitmap,read(InputImage.fromBitmap(bitmap,0))) else cropLines(bitmap,box)
            if (box == null) {
                val row = store.get(id)
                // Existing layout identity stays stable across OCR engines and rescans.
                if ((row["songLayoutKey"] as String).isBlank() ||
                    (RhythmSongLayouts.layoutKey(row).startsWith("image:") &&
                        RhythmSongLayouts.saved(store,RhythmSongLayouts.layoutKey(row)) == null)) {
                    val detection = RhythmSongLayouts.detect(row,all)
                    if (detection.key != row["songLayoutKey"]) store.songGeneration++
                    RhythmSongLayouts.remember(store,id,detection)
                }
            }
            val code = RhythmSongLayouts.layoutKey(store.get(id))
            val saved = RhythmSongLayouts.saved(store,code)
            mapOf("code" to code, "width" to bitmap.width, "height" to bitmap.height,
                "region" to (saved?.let { mapOf("x" to it.x,"y" to it.y,"right" to it.right,"bottom" to it.bottom) } ?: emptyMap()),
                "text" to (box?.let { RhythmSongLayouts.custom(it,all)?.text }.orEmpty()),
                "lines" to all.take(240).map { mapOf("text" to it.text,"engine" to it.engine,"x" to it.x,
                    "y" to it.y,"right" to it.right,"bottom" to it.bottom) })
        } finally { bitmap.recycle() }
    }
    fun recognizeSong(id: String) {
        val row = store.get(id)
        val generation = store.songGeneration
        // Only decoding is serialized with file operations; OCR runs independently per lane.
        val bitmap = store.operations.withLock {
            val current = store.query("id=?",arrayOf(id),1).firstOrNull() ?: return
            if (generation != store.songGeneration || (current["songManual"] as Number).toInt() != 0 ||
                (current["trashed"] as Number).toInt() != 0 || RhythmCodec.viewing ||
                store.prefs.getBoolean("paused",false)) return
            val source = File(store.directory(id),"original").takeIf { it.exists() } ?: RhythmImages.full(store,id)
            RhythmClassifier.sampled(source,1280)
        }
        try {
            RhythmDiagnostics.measure(store,id,"song_layout_ocr") {
                // Upgrade once using full layout evidence. Later passes retain the cropped fast path.
                val key = RhythmSongLayouts.layoutKey(row)
                val custom = RhythmSongLayouts.saved(store,key)
                val known = (row["songLayoutKey"] as String).isNotBlank() &&
                    (!key.startsWith("image:") || custom != null)
                val game = RhythmSongLayouts.code(row)
                val anchor = RhythmSongLayouts.anchor(row)
                val box = custom ?: RhythmSongLayouts.crop(game,anchor)
                val all = if (!known) lines(bitmap,read(InputImage.fromBitmap(bitmap,0)))
                    else if (box != null) cropLines(bitmap,box) else emptyList()
                val detection = if (!known) RhythmSongLayouts.detect(row,all)
                    else RhythmSongLayouts.Detection(key,game,anchor)
                val selected = RhythmSongLayouts.saved(store,detection.key)
                val title = if (selected != null) RhythmSongLayouts.custom(selected,all)
                    else RhythmSongLayouts.locate(detection.game,all,detection.anchor)
                val evidence = evidence(detection,all,bitmap)
                store.commitSongResult(id,generation) {
                    RhythmSongLayouts.remember(store,id,detection)
                    store.songs.applyRecognition(id,title,evidence,detection.game)
                }
            }
        } finally { bitmap.recycle() }
    }
    private fun recognizeSongSafely(id: String, bitmap: Bitmap, results: List<Pair<String, Text>>) {
        if ((store.get(id)["songManual"] as Number).toInt() != 0) return
        val generation = store.songGeneration
        try {
            RhythmDiagnostics.measure(store, id, "song_match_total") {
                val all = lines(bitmap,results).toMutableList()
                val row = store.get(id)
                val detection = if ((row["songLayoutKey"] as String).isNotBlank() &&
                    (!RhythmSongLayouts.layoutKey(row).startsWith("image:") ||
                        RhythmSongLayouts.saved(store,RhythmSongLayouts.layoutKey(row)) != null))
                    RhythmSongLayouts.Detection(RhythmSongLayouts.layoutKey(row),RhythmSongLayouts.code(row),RhythmSongLayouts.anchor(row))
                    else RhythmSongLayouts.detect(row,all)
                val custom = RhythmSongLayouts.saved(store,detection.key)
                if (custom != null && RhythmSongLayouts.custom(custom,all) == null) all.addAll(cropLines(bitmap,custom))
                val box = RhythmSongLayouts.crop(detection.game,detection.anchor)
                if (custom == null && detection.game == "arcaea" && box != null &&
                    RhythmSongLayouts.locate(detection.game,all,detection.anchor) == null) {
                    all.addAll(cropLines(bitmap,box))
                }
                val located = if (custom != null) RhythmSongLayouts.custom(custom,all)
                    else RhythmSongLayouts.locate(detection.game,all,detection.anchor)
                store.commitSongResult(id,generation) {
                    RhythmSongLayouts.remember(store,id,detection)
                    store.songs.applyRecognition(id, located, evidence(detection,all,bitmap), detection.game)
                }
            }
        } catch (e: Exception) {
            store.commitSongResult(id,generation) {
                store.update(id, "songError" to (e.message ?: "曲名识别失败").take(500), "songState" to "error", "songId" to 0L)
            }
        } catch (_: OutOfMemoryError) {
            store.commitSongResult(id,generation) {
                store.update(id, "songError" to "内存不足，请稍后重试曲名识别", "songState" to "error", "songId" to 0L)
            }
            throw OutOfMemoryError("曲名识别内存不足")
        }
    }
    private fun evidence(detection: RhythmSongLayouts.Detection, all: List<RhythmSongMatcher.Line>, bitmap: Bitmap) =
        JSONObject().put("layout",detection.key).put("width",bitmap.width).put("height",bitmap.height)
            .put("lines",JSONArray().apply { all.take(100).forEach {
                put(JSONObject().put("text",it.text.take(300)).put("engine",it.engine)
                    .put("x",it.x).put("y",it.y).put("right",it.right).put("bottom",it.bottom))
            } }).toString()
    override fun close() {
        if (latin.isInitialized()) latin.value.close()
        if (chinese.isInitialized()) chinese.value.close()
        if (japanese.isInitialized()) japanese.value.close()
    }
}
