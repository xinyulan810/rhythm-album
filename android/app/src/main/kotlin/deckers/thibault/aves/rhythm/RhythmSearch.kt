package deckers.thibault.aves.rhythm

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Bounded in-memory reading cache. Polyphones are paths, never a Cartesian product. */
class RhythmSearch(private val context: Context) {
    private val han by lazy {
        // .bin keeps aapt from transparently decompressing/renaming a .gz asset.
        GZIPInputStream(context.assets.open("rhythm-pinyin.bin")).bufferedReader().useLines { lines ->
            lines.associate { line -> line[0] to line.substringAfter('\t').split(',') }
        }
    }
    private val cache = LinkedHashMap<String, List<List<String>>>(32, .75f, true)
    private var cachedCharacters = 0
    @Synchronized fun matches(text: String, query: String): Boolean {
        val needle = key(query)
        if (needle.isEmpty() || needle.any { it !in 'a'..'z' && !it.isDigit() }) return false
        if (text.none { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' || it in '\uff66'..'\uff9f' }) return false
        val tokens = cache[text] ?: tokenize(text).also {
            if (text.length <= 24000) {
                cache[text] = it
                cachedCharacters += text.length
                while (cachedCharacters > 100000 || cache.size > 512) {
                    val first = cache.entries.iterator()
                    val entry = first.next()
                    cachedCharacters -= entry.key.length
                    first.remove()
                }
            }
        }
        var positions = setOf(0)
        for (options in tokens) {
            val next = mutableSetOf(0)
            for (position in positions) for (reading in options) {
                if (reading.isEmpty()) { next.add(position); continue }
                val rest = needle.substring(position)
                if (reading.startsWith(rest)) return true
                if (rest.startsWith(reading)) next.add(position + reading.length)
            }
            positions = next
        }
        return false
    }
    private fun tokenize(text: String): List<List<String>> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .map { if (it in 'ァ'..'ヶ') (it.code - 0x60).toChar() else it }.joinToString("")
        val result = mutableListOf<List<String>>()
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c == 'ー') {
                val vowel = result.lastOrNull()?.firstOrNull()?.lastOrNull { it in "aeiou" }
                if (vowel != null) result.add(listOf(vowel.toString(), "")) // explicit long vowel
                i++
                continue
            }
            if (c == 'っ' && i + 1 < normalized.length) {
                val next = kana[normalized.substring(i + 1, minOf(i + 3,normalized.length))]
                    ?: kana[normalized[i + 1].toString()]
                result.add(listOf(if (next?.startsWith("ch") == true) "t" else next?.take(1).orEmpty()))
                i++
                continue
            }
            val pair = if (i + 1 < normalized.length) normalized.substring(i,i+2) else ""
            val reading = kana[pair] ?: kana[c.toString()]
            if (reading != null) {
                val variants = when (reading) {
                    "shi" -> listOf("shi","si"); "chi" -> listOf("chi","ti")
                    "tsu" -> listOf("tsu","tu"); "fu" -> listOf("fu","hu")
                    "ji" -> listOf("ji","zi"); "n" -> listOf("n","nn")
                    "wo" -> listOf("wo","o")
                    else -> listOf(reading)
                }
                result.add(variants)
                i += if (pair in kana) 2 else 1
            } else {
                if (c in '\u4e00'..'\u9fff') result.add(han[c] ?: listOf("#"))
                else if (!c.isWhitespace() && c != '\'') result.add(listOf(
                    if (c in 'a'..'z' || c.isDigit()) c.toString() else key(c.toString()).ifEmpty { "#" }))
                i++
            }
        }
        return result
    }
    companion object {
        fun key(value: String): String = Normalizer.normalize(
            Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT),Normalizer.Form.NFD)
            .replace("u\u0308","v").replace(Regex("\\p{M}+"),"")
            .replace(Regex("[\\s'’-]"),"")
        private val kana = buildMap<String,String> {
            val chars = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんがぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽぁぃぅぇぉゔゎ"
            val sounds = "a i u e o ka ki ku ke ko sa shi su se so ta chi tsu te to na ni nu ne no ha hi fu he ho ma mi mu me mo ya yu yo ra ri ru re ro wa wo n ga gi gu ge go za ji zu ze zo da ji zu de do ba bi bu be bo pa pi pu pe po a i u e o vu wa".split(' ')
            chars.forEachIndexed { index,c -> put(c.toString(),sounds[index]) }
            for ((c,base) in listOf("き" to "ky","ぎ" to "gy","し" to "sh","じ" to "j","ち" to "ch",
                "ぢ" to "j","に" to "ny","ひ" to "hy","び" to "by","ぴ" to "py","み" to "my","り" to "ry")) {
                for ((small,vowel) in listOf("ゃ" to "a","ゅ" to "u","ょ" to "o")) put(c+small,base+vowel)
            }
            putAll(mapOf("しぇ" to "she","ちぇ" to "che","じぇ" to "je","てぃ" to "ti","でぃ" to "di",
                "とぅ" to "tu","どぅ" to "du","ふぁ" to "fa","ふぃ" to "fi","ふぇ" to "fe","ふぉ" to "fo",
                "うぃ" to "wi","うぇ" to "we","うぉ" to "wo","ゔぁ" to "va","ゔぃ" to "vi","ゔぇ" to "ve","ゔぉ" to "vo"))
        }
    }
}
