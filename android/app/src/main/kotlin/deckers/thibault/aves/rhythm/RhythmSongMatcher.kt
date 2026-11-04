package deckers.thibault.aves.rhythm

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Pure geometry and matching; OCR engines and database storage stay outside this class. */
object RhythmSongMatcher {
    data class Line(val text: String, val x: Double, val y: Double, val right: Double,
                    val bottom: Double, val engine: String = "latin") {
        val height get() = bottom - y
        val center get() = (x + right) / 2
    }
    data class Title(val text: String, val artist: String, val alternatives: List<String>, val line: Line)
    data class Song(val id: Long, val title: String, val artist: String, val names: List<String>)
    data class Candidate(val song: Song, val score: Double, val artistScore: Double)
    data class Match(val candidates: List<Candidate>, val accepted: Song?)

    fun key(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    fun compact(text: String) = key(text).filter { it.isLetterOrDigit() }

    fun isArcaea(text: String): Boolean {
        val t = compact(text)
        return ("maxrecall" in t || ("maxcombo" in t && "far" in t)) &&
            listOf("future", "past", "present", "beyond", "eternal", "inscribed", "track", "pure").any { it in t }
    }

    fun locateComboPanel(lines: List<Line>, frame: Line): Title? {
        val dx = frame.right-frame.x
        val dy = frame.height
        val candidates = lines.filter {
            it.x in (frame.x+dx*.18)..(frame.x+dx*.50) && it.right <= frame.x+dx*1.18 &&
                it.y in (frame.y-dy*1.02)..(frame.y-dy*.65) &&
                it.bottom <= frame.y-dy*.54 && it.height in (dy*.10)..(dy*.40) &&
                it.text.trim().length in 1..300 && it.text.any(Char::isLetterOrDigit)
        }
        val primary = candidates.maxByOrNull { it.height + if (it.engine == "latin") .002 else 0.0 } ?: return null
        val sameRow = candidates.filter { abs(it.y-primary.y) < dy*.1 && abs(it.bottom-primary.bottom) < dy*.1 }
        val artist = lines.firstOrNull {
            it.engine == primary.engine && abs(it.x-primary.x) < dx*.08 &&
                it.y >= primary.bottom && it.y <= primary.bottom+dy*.20 &&
                it.bottom <= frame.y-dy*.40 && it.height < primary.height
        }?.text.orEmpty()
        return Title(primary.text.trim(),artist.trim(),sameRow.map { it.text.trim() }.distinct(),primary)
    }

    /** Reference band moves with the result UI; artist-only OCR is not promoted to a title. */
    fun titleBand(anchor: Line): Pair<Double, Double> {
        // Empirical anchor transform from the wide, tablet and lower-header samples.
        // Use the anchor position rather than glyph height (which varies with OCR engine).
        val expectedTop = 2.25 * anchor.y - .63
        return (expectedTop - .045).coerceAtLeast(.075) to expectedTop + .045
    }
    fun arcaeaTitleCandidates(lines: List<Line>, anchor: Line): List<Line> {
        val (top, lastTop) = titleBand(anchor)
        return lines.filter {
            it.y in top..lastTop && it.bottom <= anchor.y - .07 &&
                it.height in .022.. .095 && abs(it.center - .5) < .17 &&
                it.text.trim().length in 1..300 && it.text.any(Char::isLetterOrDigit) &&
                compact(it.text) !in setOf("potential","result","trackcomplete","tracklost","purememory","fullrecall","maxrecall","结果")
        }
    }
    fun locateAnchored(lines: List<Line>, anchor: Line): Title? {
        val candidates = arcaeaTitleCandidates(lines,anchor)
        val lastTop = titleBand(anchor).second
        val primary = candidates.maxByOrNull {
            it.height * 8 - abs(it.center - .5) * .6 + if (it.engine == "latin") .01 else 0.0
        } ?: return null
        val sameRow = candidates.filter { abs(it.y-primary.y) < .022 && abs(it.bottom-primary.bottom) < .028 }
        val artist = lines.firstOrNull {
            it.engine == primary.engine && it.y >= primary.bottom - .003 && it.y <= primary.bottom + .065 &&
                it.y > lastTop && it.bottom < anchor.y - .04 &&
                it.height < primary.height && abs(it.center - .5) < .12
        }?.text.orEmpty()
        return Title(primary.text.trim(), artist.trim(), sameRow.map { it.text.trim() }.distinct(), primary)
    }

    fun locate(lines: List<Line>): Title? {
        // Broad enough for the supplied 2.23:1 and 1.60:1 layouts.
        // UI anchors are checked by the caller; this is NOT a universal title detector.
        val choices = lines.filter {
            it.text.trim().length in 2..300 && it.y in .075.. .225 &&
                it.bottom <= .265 && it.height in .022.. .095 &&
                abs(it.center - .5) < .17 && it.right - it.x > .025 &&
                !Regex("^(potential|result|结果|残片|记忆源点)$", RegexOption.IGNORE_CASE).matches(it.text.trim())
        }
        if (choices.isEmpty()) return null
        val ranked = choices.sortedByDescending { it.height * 8 - abs(it.center - .5) * .6 - abs(it.y - .12) * .3 }
        val anchor = ranked.first()
        val sameRow = choices.filter { abs(it.y - anchor.y) < .022 && abs(it.bottom - anchor.bottom) < .028 }
        // Latin is preferable for Latin art lettering; never concatenate engine outputs.
        val primary = sameRow.firstOrNull { it.engine == "latin" && it.height >= anchor.height * .72 } ?: anchor
        val artists = lines.filter {
            it.y >= primary.bottom - .003 && it.y <= primary.bottom + .065 &&
                it.height < primary.height * .9 && abs(it.center - .5) < .1 &&
                it.bottom < .27 && it.text.isNotBlank()
        }
        val artist = (artists.firstOrNull { it.engine == primary.engine } ?: artists.firstOrNull())?.text.orEmpty()
        return Title(primary.text.trim(), artist.trim(),
            (listOf(primary.text) + sameRow.map { it.text }).map(String::trim).distinct(), primary)
    }

    /** Bounded retrieval index: only display names, never short community aliases. */
    class Index(val songs: List<Song>) {
        private val exact = mutableMapOf<String, MutableSet<Long>>()
        private val grams = mutableMapOf<String, MutableSet<Long>>()
        private val byId = songs.associateBy { it.id }
        init {
            songs.forEach { song -> song.names.forEach { name ->
                exact.getOrPut(key(name)) { mutableSetOf() }.add(song.id)
                trigrams(compact(name)).forEach { grams.getOrPut(it) { mutableSetOf() }.add(song.id) }
            } }
        }
        fun match(title: Title): Match {
            val votes = mutableMapOf<Long, Int>()
            title.alternatives.forEach { value ->
                exact[key(value)].orEmpty().forEach { votes[it] = (votes[it] ?: 0) + 10000 }
                trigrams(compact(value)).forEach { gram ->
                    grams[gram].orEmpty().forEach { votes[it] = (votes[it] ?: 0) + 1 }
                }
            }
            val candidates = votes.entries.sortedByDescending { it.value }.take(32).mapNotNull { byId[it.key] }.map { song ->
                Candidate(song, title.alternatives.maxOf { input -> song.names.maxOf { similarity(input, it) } },
                    if (title.artist.isBlank()) 0.0 else similarity(title.artist, song.artist))
            }.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.artistScore }).take(3)
            val best = candidates.firstOrNull() ?: return Match(emptyList(), null)
            val margin = best.score - (candidates.getOrNull(1)?.score ?: 0.0)
            val exactPrimary = best.song.names.any { key(it) == key(title.text) }
            val artistConflict = title.artist.isNotBlank() && best.song.artist.isNotBlank() && best.artistScore < .45
            val accepted = !artistConflict && margin >= .06 &&
                (exactPrimary || (compact(title.text).length >= 7 && best.score >= .94 &&
                    best.artistScore >= .85 && best.song.names.maxOf { similarity(title.text, it) } >= .82))
            return Match(candidates.filter { it.score >= .55 }, if (accepted) best.song else null)
        }
    }
    private fun trigrams(value: String): Set<String> =
        if (value.length < 3) setOf(value) else value.windowed(3).toSet()

    fun similarity(a: String, b: String): Double {
        val x = compact(a).take(300); val y = compact(b).take(300)
        if (x.isEmpty() || y.isEmpty()) return if (x == y) 1.0 else 0.0
        var prev = IntArray(y.length + 1) { it }
        for (i in x.indices) {
            val next = IntArray(y.length + 1); next[0] = i + 1
            for (j in y.indices) next[j + 1] = minOf(next[j] + 1, prev[j + 1] + 1, prev[j] + if (x[i] == y[j]) 0 else 1)
            prev = next
        }
        return 1.0 - prev[y.length].toDouble() / max(x.length, y.length)
    }
}
