package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test

class RhythmAchievementTest {
    private val judgments = "\nSCORE 1000000\nPERFECT 1000\nGREAT 0\nMISS 0"
    @Test fun englishJapaneseChineseAndSplitBadges() {
        for (badge in listOf("ALL PERFECT!", "+ALL PERFECT!+", "ＰＵＲＥ ＭＥＭＯＲＹ",
            "ALL\nPERFECT", "PURE\nMEMORY", "オールパーフェクト", "全完美")) {
            assertEquals(badge, "AP", RhythmAchievement.detect(badge + judgments))
        }
        for (badge in listOf("FULL COMBO!", "FULL\nCOMBO", "フルコンボ", "全连", "全連")) {
            assertEquals(badge, "FC", RhythmAchievement.detect(badge + judgments))
        }
    }
    @Test fun rejectProfilesHelpAndOtherPlayers() {
        assertEquals("", RhythmAchievement.detect("ID: 123\nCLEAR 400 / 468\nFULL 25 / 468\nPURE MEMORY"))
        assertEquals("", RhythmAchievement.detect("Get ALL PERFECT to unlock a reward" + judgments))
        assertEquals("", RhythmAchievement.detect("ALL PERFECT\nFULL COMBO" + judgments))
        assertEquals("", RhythmAchievement.detect("ALL PERFECT" + judgments + "\nPERFECT 100\nMISS 4"))
        assertEquals("", RhythmAchievement.detect("Happy Melody" + judgments))
    }
    @Test fun searchHandlesMixedScriptsAndFullWidth() {
        assertEquals("初音ミク测试english", RhythmAchievement.searchKey("初音ミク 测 试 ＥＮＧＬＩＳＨ"))
    }
}
