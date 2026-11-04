package deckers.thibault.aves.rhythm

import kotlin.math.abs
import org.json.JSONObject

/** Positions measured from local result samples, keyed by original visual category. */
object RhythmSongLayouts {
    fun code(row: Map<String, Any>): String =
        (row["songLayoutCode"] as? String).orEmpty().ifBlank { (row["categoryCode"] as? String).orEmpty() }

    data class Box(val x: Double, val y: Double, val right: Double, val bottom: Double) {
        init { require(listOf(x,y,right,bottom).all { it.isFinite() && it in 0.0..1.0 } &&
            right - x >= .005 && bottom - y >= .005) { "请选择有效的歌名区域" } }
    }
    fun saved(store: RhythmStore, code: String): Box? =
        store.prefs.getString("songLayout:$code", null)?.let {
            val value = JSONObject(it)
            Box(value.getDouble("x"), value.getDouble("y"), value.getDouble("right"), value.getDouble("bottom"))
        }
    fun save(store: RhythmStore, code: String, box: Box?) {
        require(code.isNotBlank()) { "请先将此图分类到游戏相册，再设置识别位置" }
        val editor = store.prefs.edit()
        if (box == null) editor.remove("songLayout:$code")
        else editor.putString("songLayout:$code", JSONObject().put("x",box.x).put("y",box.y)
            .put("right",box.right).put("bottom",box.bottom).toString())
        check(editor.commit()) { "无法保存识别位置" }
        store.songGeneration++
        store.writableDatabase.execSQL("""UPDATE images SET songVersion=0,songError='',songId=0,songState=''
            WHERE (songLayoutCode=? OR (songLayoutCode='' AND categoryCode=?)) AND songManual=0 AND trashed=0""",
            arrayOf(code,code))
    }
    fun custom(box: Box, lines: List<RhythmSongMatcher.Line>): RhythmSongMatcher.Title? {
        val selected = lines.filter {
            it.center in box.x..box.right && (it.y + it.bottom) / 2 in box.y..box.bottom &&
                it.text.isNotBlank()
        }
        val engine = selected.firstOrNull { it.engine == "chinese" &&
            it.text.any { c -> c.code in 0x3040..0x9fff } }?.engine ?:
            selected.firstOrNull { it.engine == "latin" }?.engine ?: selected.firstOrNull()?.engine ?: return null
        val ordered = selected.filter { it.engine == engine }.sortedWith(compareBy({ it.y }, { it.x }))
        val text = ordered.joinToString(" ") { it.text }.take(300).trim()
        return RhythmSongMatcher.Title(text, "", listOf(text), ordered.first())
    }
    private data class Region(val left: Double, val right: Double, val top: Double, val bottom: Double,
                              val artist: Boolean = false)
    private val regions = mapOf(
        "phira" to Region(.30, .88, .025, .09),
        "phigros" to Region(.03, .38, .63, .73),
        "pjsekai" to Region(.17, .65, .01, .065),
        "astrodx" to Region(.045, .30, .08, .135, true),
        "bandori" to Region(.19, .75, .11, .17),
        "dynamix" to Region(.48, .97, .09, .20),
        "dynamite" to Region(.27, .76, .22, .285, true),
        "cytus2" to Region(.25, .75, .035, .10),
        "musedash" to Region(.20, .80, .015, .075),
        "worlddaistar" to Region(.12, .65, .11, .17),
        "paradigm" to Region(.29, .74, .105, .16, true),
        "sonolus" to Region(.07, .93, .24, .32, true),
        "overrapid" to Region(.065, .34, .60, .70),
        "holodreams" to Region(.155, .32, .775, .825)
    )
    val supported get() = regions.keys + "arcaea"
    fun crop(game: String): Box? = if (game == "arcaea") Box(.06,.075,.94,.27) else regions[game]?.let {
        Box((it.left-.015).coerceAtLeast(0.0),(it.top-.01).coerceAtLeast(0.0),
            (it.right+.055).coerceAtMost(1.0),(it.bottom + if (it.artist) .075 else .015).coerceAtMost(1.0))
    }
    fun game(category: String, text: String): String? =
        if (category in supported) category
        else if (category.isBlank() && RhythmSongMatcher.isArcaea(text)) "arcaea"
        else null

    fun locate(game: String, lines: List<RhythmSongMatcher.Line>): RhythmSongMatcher.Title? {
        if (game == "arcaea") return RhythmSongMatcher.locate(lines)
        val area = regions[game] ?: return null
        val candidates = lines.filter { l ->
            l.x >= area.left && l.x < area.right && l.right <= area.right + .055 &&
                l.y >= area.top && l.bottom <= area.bottom && l.height >= .012 &&
                l.text.length in 2..300 && l.text.any(Char::isLetter) &&
                !Regex("(?i)^(new\\s*(best|record).*|.*\\b(Lv\\.?|COMBO|SCORE|PERFECT|MASTER|EXPERT|STELLA)\\b.*)$")
                    .matches(l.text.trim())
        }
        val anchor = candidates.maxByOrNull { it.height } ?: return null
        val sameRow = candidates.filter { abs(it.y - anchor.y) < .012 && abs(it.bottom - anchor.bottom) < .018 }
        val primary = sameRow.firstOrNull { it.engine in listOf("chinese", "japanese") &&
            it.text.any { c -> c.code in 0x3040..0x9fff } } ?:
            sameRow.firstOrNull { it.engine == "latin" } ?: anchor
        if (sameRow.any { it.engine == primary.engine && (it.right < primary.x - .01 || it.x > primary.right + .01) })
            return null
        val artist = if (area.artist) lines.firstOrNull {
            it.engine == primary.engine && it.y >= primary.bottom && it.y <= primary.bottom + .055 &&
                it.height < primary.height && abs(it.x - primary.x) < .12 &&
                it.right <= area.right + .055
        }?.text.orEmpty() else ""
        return RhythmSongMatcher.Title(primary.text.trim(), artist.trim(),
            sameRow.map { it.text.trim() }.distinct(), primary)
    }
}
