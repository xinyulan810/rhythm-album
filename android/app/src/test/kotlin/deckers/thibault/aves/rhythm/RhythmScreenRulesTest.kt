package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RhythmScreenRulesTest {
    @Test fun explicitReviewedScreensReplayCorrectlyWithoutTreatingBlanksAsNegatives() {
        listOf(
            "招募结果 成员被送至等候室 EXP 26% 25 / 25" to RhythmScreenRules.BANDORI_GACHA,
            "TRACK COMPLETE MAX COMBO PURE FAR LOST" to "arcaea",
            "REALITY 15.2 PERFECT GOOD BAD/MISS 98.2% 1234567" to "milthm",
            "HIGH SCORE RATING リザルト バンド PERFECT GREAT GOOD BAD MISS 123456" to "bandori",
        ).forEach { (text, expected) ->
            assertEquals(expected, RhythmClassifier.textCategory(text))
        }
    }

    @Test fun newRulesDoNotOverridePreviouslyReviewedScreensWithWrongGames() {
        assertNull(RhythmScreenRules.extraCategory("RESULT PERFECT GREAT GOOD MISS SCORE 12345"))
        assertNull(RhythmScreenRules.extraCategory("招募 详情 EXP 25 / 25"))
    }

    @Test fun gachaRequiresResultWaitingRoomAndBandoriLayoutEvidence() {
        val text = "招募结果 获得过的成员已被送至等候室 EXP 26% 25 / 25"
        assertEquals(RhythmScreenRules.BANDORI_GACHA, RhythmClassifier.textCategory(text))
        assertNull(RhythmScreenRules.extraCategory("Dream Festival 招募 详情 2500 EXP 26% 25/25"))
        assertNull(RhythmScreenRules.extraCategory("招募结果 确定 再次招募"))
        assertNull(RhythmScreenRules.extraCategory(text.replace("被送至", "")))
        assertNull(RhythmScreenRules.extraCategory(text.replace("EXP", "")))
        assertNull(RhythmScreenRules.extraCategory(text.replace("25 / 25", "30 / 160")))
        assertFalse(RhythmClassifier.isSettlement(text))
        assertEquals("", RhythmAchievement.detect(text))
    }

    @Test fun subtypeSharesRenamedParentAlbumButKeepsSeparateReferenceCode() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "gacha-category")
        store.categories.ensure("bandori", "BanG Dream!")
        store.categories.rename("bandori", "我的邦邦")
        store.categories.ensureScreenCategory(RhythmScreenRules.BANDORI_GACHA, "BanG Dream!")
        for ((n, code) in listOf("bandori", RhythmScreenRules.BANDORI_GACHA).withIndex()) {
            store.enqueue("gacha:$n", "$n", "", false)
            val id = store.query("source=?", arrayOf("gacha:$n")).single()["id"] as String
            store.update(id, "categoryCode" to code, "status" to "compressed")
        }
        val albums = store.categories.albums()
        assertEquals(1, albums.size)
        assertEquals("我的邦邦", albums.single()["name"])
        assertEquals(2L, albums.single()["count"])
        assertEquals(2, store.query().map { it["categoryCode"] }.distinct().size)
        store.categories.merge(listOf(RhythmScreenRules.BANDORI_GACHA), "other")
        store.categories.ensureScreenCategory(RhythmScreenRules.BANDORI_GACHA, "BanG Dream!")
        assertEquals(2, store.categories.ids(listOf("other")).size)
        store.close()
    }

    @Test fun lateChineseOcrRefinesUnknownWithoutOverwritingManualOrReencoding() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "gacha-refine")
        store.enqueue("gacha:ocr", "test", "", false)
        val id = store.query().single()["id"] as String
        store.update(id, "status" to "compressed", "classified" to 1, "storedBytes" to 999)
        val text = "招募结果 成员被送至等候室 EXP 26% 25 / 25"
        assertTrue(RhythmClassifier.refineFromOcr(store, id, text))
        assertEquals(RhythmScreenRules.BANDORI_GACHA, store.get(id)["categoryCode"])
        assertEquals("compressed", store.get(id)["status"])
        assertEquals(999L, store.get(id)["storedBytes"])
        store.categories.correct(listOf(id), "", true)
        assertFalse(RhythmClassifier.refineFromOcr(store, id, text))
        assertEquals("", store.get(id)["categoryCode"])
        store.close()
    }
}
