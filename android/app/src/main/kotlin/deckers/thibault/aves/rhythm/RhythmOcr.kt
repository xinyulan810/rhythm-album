package deckers.thibault.aves.rhythm

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File

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
            val texts = mutableListOf<String>()
            texts.add((row["ocrLatin"] as String).ifBlank {
                Tasks.await(latin.value.process(image)).text
            })
            texts.add(Tasks.await(chinese.value.process(image)).text)
            texts.add(Tasks.await(japanese.value.process(image)).text)
            val text = texts.flatMap { it.lines() }.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n").take(24000)
            val votes = texts.map(RhythmAchievement::detect).filter(String::isNotBlank).distinct()
            val tag = if (texts.any(RhythmAchievement::ambiguous)) "" else votes.singleOrNull().orEmpty()
            store.update(id, "ocrText" to text, "ocrSearch" to RhythmAchievement.searchKey(text),
                "ocrVersion" to 1, "ocrError" to "", "achievement" to tag,
                "achievementEvidence" to if (tag.isEmpty()) "" else texts.first { RhythmAchievement.detect(it) == tag }.take(4000))
            RhythmClassifier.refineFromOcr(store, id, text)
        } finally { bitmap.recycle() }
    }
    override fun close() {
        if (latin.isInitialized()) latin.value.close()
        if (chinese.isInitialized()) chinese.value.close()
        if (japanese.isInitialized()) japanese.value.close()
    }
}
