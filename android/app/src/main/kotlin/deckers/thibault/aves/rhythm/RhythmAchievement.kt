package deckers.thibault.aves.rhythm

import java.text.Normalizer
import java.util.Locale

/** Conservative single-result labels, independent of the album classifier. */
object RhythmAchievement {
    fun searchKey(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    private fun token(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .uppercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    fun ambiguous(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return Regex("(?i)\\bMISS(?:\\b|\\d)").findAll(normalized).count() > 1 ||
            Regex("(?i)ALL\\s*PERFECT|PURE\\s*MEMORY|FULL\\s*COMBO|オールパーフェクト|フルコンボ|全完美|全[连連]")
                .findAll(normalized).count() > 1
    }
    fun detect(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val normalized = lines.map(::token)
        val combined = normalized.joinToString("")
        val score = listOf("SCORE", "RESULT", "得分", "成绩", "成績", "リザルト", "スコア", "MAXRECALL", "TRACKCOMPLETE").any(combined::contains)
        val judgments = listOf("PERFECT", "GREAT", "GOOD", "BAD", "MISS", "PURE", "FAR", "LOST", "CRITICAL", "ATTACK", "FAST", "SLOW").count(combined::contains)
        if (!score || judgments < 2 || ambiguous(text)) return ""
        // Multiple players/results have multiple judgement tables; do not assign another player's badge.
        if (normalized.count { it.matches(Regex("MISS\\d*")) } > 1 ||
            Regex("(?i)\\bMISS(?:\\b|\\d)").findAll(text).count() > 1) return ""
        if (Regex("\\d+\\s*/\\s*\\d+").findAll(text).count() > 1 && combined.contains("ID")) return ""
        fun badge(value: String): String = when {
            value.matches(Regex("(ALLPERFECT|PUREMEMORY|オールパーフェクト|全完美)\\d*")) -> "AP"
            value.matches(Regex("(FULLCOMBO|フルコンボ|全连|全連)\\d*")) -> "FC"
            else -> ""
        }
        val badges = normalized.map(::badge).filter(String::isNotEmpty).toMutableList()
        // Handle a title split across two OCR lines, without scanning arbitrary substrings.
        for (i in 0 until normalized.size - 1) {
            if (normalized[i] in listOf("ALL", "PURE", "FULL")) {
                badge(normalized[i] + normalized[i + 1]).takeIf(String::isNotEmpty)?.let(badges::add)
            }
        }
        return if (badges.size == 1) badges.single() else ""
    }
    const val EFFECTIVE_SQL = "(CASE WHEN achievementOverride='auto' THEN achievement ELSE achievementOverride END)"
}
