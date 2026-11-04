package deckers.thibault.aves.rhythm

import kotlin.math.abs
import org.json.JSONObject

/** Game identity and detected screen layout are deliberately separate. */
object RhythmSongLayouts {
    fun code(row: Map<String, Any>): String =
        (row["songLayoutCode"] as? String).orEmpty()

    fun layoutKey(row: Map<String, Any>): String = (row["songLayoutKey"] as? String).orEmpty()
        .ifBlank { "image:${row.getValue("id")}" }

    data class Detection(val key: String, val game: String, val anchor: RhythmSongMatcher.Line? = null)

    /** Only stable UI labels participate. Song names, scores and album names never form a key. */
    fun detect(row: Map<String, Any>, lines: List<RhythmSongMatcher.Line>): Detection {
        val text = lines.joinToString("\n") { it.text }
        val game = RhythmClassifier.textCategory(text) ?: if (RhythmSongMatcher.isArcaea(text)) "arcaea" else code(row)
        if (game == "arcaea") {
            // Separate left result panel: MAX COMBO plus the horizontal PURE/FAR/LOST row.
            val combo = lines.firstOrNull {
                "maxcombo" in RhythmSongMatcher.compact(it.text) && it.y in .2.. .6 && it.x < .5
            }
            if (combo != null) {
                fun judgment(label: String) = lines.firstOrNull {
                    RhythmSongMatcher.compact(it.text).matches(Regex("$label\\d*")) &&
                        it.y > combo.bottom && it.y < combo.y + .35
                }
                val pure = judgment("pure"); val far = judgment("far"); val lost = judgment("lost")
                if (pure != null && far != null && lost != null &&
                    far.x-pure.x in .08.. .30 && lost.x-far.x in .08.. .30 &&
                    abs(pure.y-far.y) < .025 && abs(pure.y-lost.y) < .025 &&
                    pure.y-combo.y in .08.. .30) {
                    return Detection("arcaea:combo-left-v1", game,
                        RhythmSongMatcher.Line("arcaea-combo-frame",pure.x,combo.y,lost.x,pure.y))
                }
                return Detection("image:${row.getValue("id")}", game)
            }
            val anchor = lines.filter {
                val normalized = RhythmSongMatcher.compact(it.text)
                normalized.startsWith("maxrecall") &&
                    it.x in .03.. .26 && it.y in .29.. .40 && it.height in .012.. .035
            }.sortedBy { it.y }.let { it.getOrNull(it.size / 2) }
            if (anchor != null) {
                // Position bands are screenshot-relative OCR measurements, not device names or aspect ratios.
                val band = when {
                    anchor.y < .335 -> "upper"
                    anchor.y < .353 -> "middle"
                    else -> "lower"
                }
                return Detection("arcaea:recall-$band-v1", game, anchor)
            }
            // No reliable anchor: let manual positioning bind to this image only.
            return Detection("image:${row.getValue("id")}", game)
        }
        return Detection(if (game.isNotBlank()) "$game:preset-v1" else "image:${row.getValue("id")}", game)
    }

    fun remember(store: RhythmStore, id: String, detection: Detection) {
        store.update(id, "songLayoutKey" to detection.key, "songLayoutCode" to detection.game,
            "songLayoutAnchor" to (detection.anchor?.let {
                JSONObject().put("text",it.text).put("x",it.x).put("right",it.right)
                    .put("y", it.y).put("height", it.height).toString()
            } ?: ""))
    }

    fun anchor(row: Map<String, Any>): RhythmSongMatcher.Line? =
        (row["songLayoutAnchor"] as? String)?.takeIf { it.isNotBlank() }?.let {
            val a = JSONObject(it)
            RhythmSongMatcher.Line(a.getString("text"), a.getDouble("x"), a.getDouble("y"),
                a.getDouble("right"), a.getDouble("y") + a.getDouble("height"))
        }

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
        require(code.isNotBlank()) { "请先扫描画面布局" }
        val editor = store.prefs.edit()
        if (box == null) editor.remove("songLayout:$code")
        else editor.putString("songLayout:$code", JSONObject().put("x",box.x).put("y",box.y)
            .put("right",box.right).put("bottom",box.bottom).toString())
        check(editor.commit()) { "无法保存识别位置" }
        store.songGeneration++
        store.writableDatabase.execSQL("""UPDATE images SET songVersion=0,songError='',songId=0,songState=''
            WHERE songLayoutKey=? AND songManual=0 AND trashed=0""", arrayOf(code))
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
    fun crop(game: String, anchor: RhythmSongMatcher.Line? = null): Box? = if (game == "arcaea")
        anchor?.let {
            if (it.text == "arcaea-combo-frame") {
                val dx = it.right-it.x
                Box((it.x+dx*.18).coerceIn(0.0,.95),(it.y-it.height*1.02).coerceAtLeast(0.0),
                    (it.x+dx*1.18).coerceAtMost(1.0),(it.y-it.height*.34).coerceAtMost(1.0))
            } else Box(.06,RhythmSongMatcher.titleBand(it).first,.94,(it.y-.035).coerceAtMost(.40))
        }
        else regions[game]?.let {
        Box((it.left-.015).coerceAtLeast(0.0),(it.top-.01).coerceAtLeast(0.0),
            (it.right+.055).coerceAtMost(1.0),(it.bottom + if (it.artist) .075 else .015).coerceAtMost(1.0))
    }
    fun locate(game: String, lines: List<RhythmSongMatcher.Line>, anchor: RhythmSongMatcher.Line? = null): RhythmSongMatcher.Title? {
        if (game == "arcaea") return anchor?.let {
            if (it.text == "arcaea-combo-frame") RhythmSongMatcher.locateComboPanel(lines,it)
            else RhythmSongMatcher.locateAnchored(lines, it)
        }
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
