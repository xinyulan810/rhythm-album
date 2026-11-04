package deckers.thibault.aves.rhythm

import java.text.Normalizer
import java.util.Locale

/** Reviewed screen-layout evidence. No filenames, ids, or installed-app rules. */
object RhythmScreenRules {
    const val BANDORI_GACHA = "bandori-gacha"

    fun extraCategory(text: String): String? {
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
        // BanG Dream recruitment result, not a generic gacha button or shop.
        val recruitment = Regex("招[募暮](结果|結果)").containsMatchIn(t)
        val waitingRoom = Regex("等[候侯]{1,2}室").containsMatchIn(t)
        val stamina = Regex("\\d{1,2}/(10|25)(?!\\d)").containsMatchIn(t)
        if (recruitment && waitingRoom && "被送至" in t && "EXP" in t && stamina) return BANDORI_GACHA

        // This Arcaea layout says MAX COMBO instead of MAX RECALL.
        if (("TRACKCOMPLETE" in t || "TRACKLOST" in t) && "MAXCOMBO" in t &&
            listOf("PURE", "FAR", "LOST").all { it in t }) return "arcaea"

        if (Regex("REALITY\\d+\\.\\d+").containsMatchIn(t) &&
            listOf("PERFECT", "GOOD", "BAD/MISS").all { it in t } &&
            Regex("\\d{2}\\.\\d+%").containsMatchIn(t) &&
            Regex("\\d{7}").containsMatchIn(t)) return "milthm"

        // Japanese Bandori result layout; ranking alone is not sufficient.
        if ("HIGHSCORERATING" in t && "リザルト" in t && "バンド" in t &&
            listOf("PERFECT", "GREAT", "GOOD", "BAD", "MISS").all { it in t } &&
            Regex("\\d{6,}").containsMatchIn(t)) return "bandori"
        return null
    }
}
