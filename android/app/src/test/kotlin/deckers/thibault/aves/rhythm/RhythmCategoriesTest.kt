package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RhythmCategoriesTest {
    @Test fun reviewExportOnlySelectsLiveCompressedOtherAndKeepsManualProvenance() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true, "review-selection")
        store.categories.ensure("known", "Known")
        store.categories.ensure("mapped", "Mapped")
        fun add(source: String, status: String, code: String = "", manual: Int = 0, trashed: Int = 0): String {
            store.enqueue("review:$source", source, "", false)
            val id = store.query("source=?", arrayOf("review:$source")).single()["id"] as String
            store.update(id, "status" to status, "categoryCode" to code,
                "manualCategory" to manual, "trashed" to trashed)
            return id
        }
        val automatic = add("auto", "compressed")
        val manual = add("manual", "compressed", manual = 1)
        val mapped = add("mapped", "compressed", "mapped")
        add("known", "compressed", "known")
        add("pending", "pending")
        add("ready", "ready")
        add("trash", "compressed", trashed = 1)
        store.categories.merge(listOf("mapped"), "other")
        val rows = store.query(RhythmReviewExport.WHERE)
        assertEquals(setOf(automatic, manual, mapped), rows.map { it["id"] }.toSet())
        assertEquals(1L, rows.single { it["id"] == manual }["manualCategory"])
        assertEquals("mapped", rows.single { it["id"] == mapped }["categoryCode"])
        assertEquals(7L, store.count("1", emptyArray()))
        store.close()
    }
    @Test fun movingAlbumToOtherPreservesCodesAndIsUndoable() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true)
        store.categories.ensure("to-other", "Test")
        store.enqueue("test:other", "Screenshot", "", false)
        val id = store.query("source=?", arrayOf("test:other")).single()["id"] as String
        store.update(id, "categoryCode" to "to-other", "status" to "compressed")
        store.categories.rename("to-other", "其他")
        assertTrue(store.categories.albums().isEmpty())
        assertEquals(listOf(id), store.categories.ids(listOf("other")))
        assertEquals("to-other", store.get(id)["categoryCode"])
        store.categories.undo()
        assertEquals(1, store.categories.albums().size)
        assertTrue(store.categories.ids(listOf("other")).isEmpty())
        store.close()
    }
    @Test fun sameNameMergeKeepsCodesAndUndoRestoresMapping() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true)
        for (i in 1..2) {
            store.enqueue("test:$i", "$i", "", false)
            val id = store.query("source=?", arrayOf("test:$i")).single()["id"] as String
            store.update(id, "categoryCode" to "c$i", "status" to "compressed")
            store.categories.ensure("c$i", "Game$i")
        }
        val before = store.query().map { it["categoryCode"] }
        assertEquals(2, store.categories.albums().size)
        store.categories.rename("c2", "  Ｇａｍｅ１  ")
        assertEquals(1, store.categories.albums().size)
        assertEquals(2L, store.categories.albums().single()["count"])
        assertEquals(before, store.query().map { it["categoryCode"] })
        store.categories.undo()
        assertEquals(2, store.categories.albums().size)
        assertEquals(before, store.query().map { it["categoryCode"] })
        store.close()
    }

    @Test fun unknownNamesStaySeparateAndManualCorrectionOverridesStorageFallback() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(), true)
        store.categories.ensure("a")
        store.categories.ensure("b")
        store.categories.rename("a", "")
        store.categories.rename("b", "")
        for (i in 1..2) {
            store.enqueue("test:$i", "$i", "", false)
            val id = store.query("source=?", arrayOf("test:$i")).single()["id"] as String
            store.update(id, "categoryCode" to if (i == 1) "a" else "b", "status" to "compressed")
        }
        assertEquals(2, store.categories.albums().size)
        val id = store.query().first()["id"] as String
        store.update(id, "status" to "ready")
        assertEquals(1, store.categories.albums().size)
        store.categories.correct(listOf(id), "My Game", false)
        assertEquals(2, store.categories.albums().size)
        store.categories.correct(listOf(id), "", true)
        assertEquals(1, store.categories.albums().size)
        assertEquals(1L, store.get(id)["manualCategory"])
        store.close()
    }

    @Test fun gameplayCountersAloneDoNotCreateSettlementAlbum() {
        assertFalse(RhythmClassifier.isSettlement("PERFECT 4568 GOOD 16 MISS 134 COMBO 516 0726994"))
        assertFalse(RhythmClassifier.isSettlement("Song select HIGH SCORE 9999999 FULL COMBO"))
        assertTrue(RhythmClassifier.isSettlement("RESULT SCORE 0999999 PERFECT 1000 GREAT 20 MISS 1"))
        assertEquals("arcaea", RhythmClassifier.textCategory("MAX RECALL 1200 TRACK COMPLETE PURE 1300 LOST 2"))
        assertEquals("phira", RhythmClassifier.textCategory("RKS PERFECT GOOD BAD MISS"))
    }
}
