package deckers.thibault.aves.rhythm

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RhythmSongsTest {
    @Test fun phoneticsSupportKanaPolyphonesAndEditedTitles() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"phonetics")
        try {
            val readings = store.readings
            for ((text,query) in listOf("重庆" to "chong qing","重庆" to "zhongqing","音乐" to "yinyue",
                "快乐" to "kuaile","银行" to "yinhang","女神" to "nüshen",
                "キャット" to "kyatto","がっこう" to "gakkou","ミュージック" to "myuujikku",
                "ｷｬｯﾄ" to "kyatto","しんぶん" to "shinbun","スーパー" to "suupaa","スーパー" to "supa")) {
                assertTrue("$text / $query",readings.matches(text,query))
            }
            assertFalse(readings.matches("快乐","yinyue"))
            assertFalse(readings.matches("初音","hatsune")) // Kanji Japanese readings are deliberately not inferred.
            store.enqueue("content://phonetic/one","Screenshot.png","",false)
            val id = store.query().single()["id"] as String
            store.update(id,"song" to "重庆音乐")
            assertEquals(listOf(id),store.phoneticIds("chongqingyinyue"))
            store.update(id,"song" to "キャット")
            assertTrue(store.phoneticIds("chongqingyinyue").isEmpty())
            assertEquals(listOf(id),store.phoneticIds("kyatto"))
            store.songs.confirm(id,0,"本地曲","",listOf("ミュージック"))
            assertTrue(store.songs.search("myuujikku").any { it["title"] == "本地曲" })
        } finally { store.close() }
    }

    @Test fun relatedOrderPrefersOriginalGameThenDateAndMissingCountsExcludeManualOptOut() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"song-order")
        try {
            store.categories.ensure("arcaea","Renamed")
            store.categories.ensure("phira","Other")
            val ids = (0..3).map { n ->
                store.enqueue("content://order/$n","Screenshot$n.jpg","",false,1000L+n)
                val id = store.query("source=?",arrayOf("content://order/$n")).single()["id"] as String
                store.update(id,"song" to "Same title","songLayoutCode" to if (n == 3) "phira" else "arcaea",
                    "categoryCode" to "arcaea","status" to "compressed")
                id
            }
            assertEquals(listOf(ids[2],ids[1],ids[3]),store.songs.relatedIds(ids[0]))
            val rows = store.query("id!=?",arrayOf(ids[0]),preferredGame = "arcaea")
            assertEquals(listOf(ids[2],ids[1],ids[3]),rows.map { it["id"] })
            store.update(ids[1],"song" to "")
            store.update(ids[2],"song" to "","songManual" to 1)
            assertEquals(1L,store.stats()["songMissing"])
            assertEquals(1L,store.categories.albums().single()["songMissing"])
            store.update(ids[1],"songRaw" to "Recognized custom song")
            assertEquals(0L,store.stats()["songMissing"])
        } finally { store.close() }
    }

    @Test fun relatedSearchAcceptsUnconfirmedTyposAndFragmentsWithoutAssigningIdentity() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"song-related")
        try {
            val titles = listOf("Unknown Custom Song","Unknown Custorn Song","Custom Song","Completely unrelated","Unknown Custom Song")
            val ids = titles.mapIndexed { i, text ->
                store.enqueue("content://related/$i","Screenshot$i.jpg","",false)
                val id = store.query("source=?",arrayOf("content://related/$i")).single()["id"] as String
                store.update(id,"song" to text,"songRaw" to text,"songState" to "unmatched","songId" to 0L)
                id
            }
            store.update(ids[4],"trashed" to 1)
            assertEquals(setOf(ids[1],ids[2]),store.songs.relatedIds(ids[0]).toSet())
            ids.forEach { assertEquals(0L,store.get(it)["songId"]) }
            store.update(ids[0],"song" to "","songRaw" to "")
            assertTrue(store.songs.relatedIds(ids[0]).isEmpty())
        } finally { store.close() }
    }
    @Test fun customPositionSurvivesRenameMergeMoveAndKeepsManualTitles() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"layout-persistence")
        try {
            store.categories.ensure("phira","Phira")
            store.categories.ensure("arcaea","Arcaea")
            store.enqueue("content://layout/1","Screenshot.jpg","Phira",true)
            store.enqueue("content://layout/2","Screenshot2.jpg","Phira",true)
            val rows = store.query()
            rows.forEach { store.update(it["id"] as String,"categoryCode" to "phira","songLayoutCode" to "phira",
                "songLayoutKey" to "phira:preset-v1",
                "status" to "ready","ocrVersion" to 1,"songVersion" to 1) }
            val first = rows[0]["id"] as String
            val second = rows[1]["id"] as String
            store.songs.confirm(first,0,"Local title","",emptyList())
            store.categories.rename("phira","Renamed")
            store.categories.merge(listOf("phira"),"arcaea")
            store.categories.correct(listOf(second),"Arcaea",false)
            assertEquals("phira",RhythmSongLayouts.code(store.get(second)))
            val box = RhythmSongLayouts.Box(.2,.1,.8,.2)
            RhythmSongLayouts.save(store,"phira:preset-v1",box)
            assertEquals(box,RhythmSongLayouts.saved(store,"phira:preset-v1"))
            assertNull(RhythmSongLayouts.saved(store,"arcaea"))
            assertEquals("manual",store.get(first)["songState"])
            assertEquals(0L,store.get(second)["songVersion"])
            val title = RhythmSongMatcher.Title("Local title","",listOf("Local title"),
                RhythmSongMatcher.Line("Local title",.3,.12,.6,.16))
            assertNotNull(store.songs.match(title,"phira").accepted)
            assertNull(store.songs.match(title,"arcaea").accepted)
            RhythmSongLayouts.save(store,"phira:preset-v1",null)
            assertNull(RhythmSongLayouts.saved(store,"phira:preset-v1"))
        } finally { store.close() }
    }

    @Test fun savingOneDetectedLayoutDoesNotResetOtherLayoutsOrUseLegacyBox() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"layout-v3")
        try {
            val keys = listOf("arcaea:recall-upper-v1","arcaea:combo-left-v1")
            val ids = keys.mapIndexed { n,key ->
                store.enqueue("content://layout-v3/$n","Screenshot$n.jpg","",false)
                val id = store.query("source=?",arrayOf("content://layout-v3/$n")).single()["id"] as String
                store.update(id,"songLayoutCode" to "arcaea","songLayoutKey" to key,"songVersion" to RhythmSongs.VERSION)
                id
            }
            store.prefs.edit().putString("songLayout:arcaea","{\"x\":0.2,\"y\":0.1,\"right\":0.8,\"bottom\":0.2}").commit()
            assertNull(RhythmSongLayouts.saved(store,keys[0]))
            assertNull(RhythmSongLayouts.saved(store,keys[1]))
            RhythmSongLayouts.save(store,keys[0],RhythmSongLayouts.Box(.2,.1,.8,.2))
            assertEquals(0L,store.get(ids[0])["songVersion"])
            assertEquals(RhythmSongs.VERSION.toLong(),store.get(ids[1])["songVersion"])
            assertNotNull(store.prefs.getString("songLayout:arcaea",null))
            store.categories.correct(listOf(ids[1]),"Different album",false)
            assertEquals(keys[1],RhythmSongLayouts.layoutKey(store.get(ids[1])))
            store.enqueue("content://layout-v3/unknown","Unknown.jpg","",false)
            val unknown = store.query("source=?",arrayOf("content://layout-v3/unknown")).single()["id"] as String
            store.categories.correct(listOf(unknown),"Arcaea",false)
            assertEquals("",RhythmSongLayouts.code(store.get(unknown)))
        } finally { store.close() }
    }

    @Test fun progressCountsWaitingFailureManualAndOldVersionSeparately() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"song-progress")
        try {
            repeat(4) { store.enqueue("content://progress/$it","Screenshot$it.jpg","",false) }
            val rows = store.query()
            store.update(rows[0]["id"] as String,"status" to "ready","ocrVersion" to 1,"songVersion" to 1)
            store.update(rows[1]["id"] as String,"status" to "ready","ocrVersion" to 1,"songError" to "failed")
            store.update(rows[2]["id"] as String,"status" to "ready","songManual" to 1,"songVersion" to 1)
            val progress = store.songProgress()
            assertEquals(4L,progress["total"])
            assertEquals(2L,progress["completed"])
            assertEquals(1L,progress["failed"])
            assertEquals(1L,progress["waiting"])
            assertEquals(1L,store.stats()["songPending"])
        } finally { store.close() }
    }
    @Test fun sampleOcrReplayUsesCatalogOnlyAsCorrectionEvidence() {
        val store = RhythmStore(RuntimeEnvironment.getApplication(),true,"song-samples")
        try {
            val samples = listOf(
                "Shades 0f Light in a TranscendentTf'••Se—" to "ak+q",
                "Tr!cK Ma?tEr" to "Nothing But Requiem",
                "DEINOS PHAINEIN" to "BIack101ita vs Juggernaut.",
                "inkar-usi" to "DIA")
            samples.forEachIndexed { i, (text, artist) ->
                val title = RhythmSongMatcher.Title(text,artist,listOf(text),RhythmSongMatcher.Line(text,.2,.11,.8,.17))
                val match = store.songs.match(title)
                println("sample=${i+1} raw=$text accepted=${match.accepted?.title} candidates=${match.candidates.map { it.song.title to it.score }}")
                if (i == 3) assertEquals("inkar-usi",match.accepted?.title)
                else assertNull("Uncertain or out-of-catalogue titles must remain unbound",match.accepted)
                if (i == 0) assertEquals("Shades of Light in a Transcendent Realm",match.candidates.first().song.title)
            }
            // A bounded matching-only timing, not OCR time or a phone benchmark.
            val text = samples.last()
            val title = RhythmSongMatcher.Title(text.first,text.second,listOf(text.first),RhythmSongMatcher.Line(text.first,.4,.11,.6,.17))
            val began = System.nanoTime()
            repeat(100) { store.songs.match(title) }
            println("JVM warm catalogue matching: ${(System.nanoTime()-began)/1_000_000.0/100} ms/image over 100 iterations")
        } finally { store.close() }
    }

    @Test fun catalogAliasesManualCustomSongsAndReprocessingKeepStableIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val store = RhythmStore(context, true, "song-identity")
        try {
            store.songs.ensureCatalog()
            assertTrue(store.songs.search("光追").any { it["title"] == "Aegleseeker" })
            store.enqueue("content://test/song1","Screenshot.jpg","Arcaea",true)
            store.enqueue("content://test/song2","Screenshot2.jpg","Arcaea",true)
            val rows = store.query()
            rows.forEach { store.update(it["id"] as String,"categoryCode" to "arcaea", "ocrVersion" to 1, "status" to "compressed") }
            val first = rows[0]["id"] as String; val second = rows[1]["id"] as String
            store.songs.confirm(first,0,"My Custom Song","My Artist",listOf("自制测试"))
            val id = (store.get(first)["songId"] as Number).toLong()
            assertTrue(id > 0)
            store.songs.confirm(second,0,"My Custom Song","My Artist",emptyList())
            assertEquals(id,store.get(second)["songId"])
            assertEquals(2L,store.count("songId=?",arrayOf(id.toString())))
            assertEquals(id,store.songs.search("自制测试").single()["id"])
            store.songs.applyRecognition(first,null,"{}")
            assertEquals("My Custom Song",store.get(first)["song"])
            assertEquals("manual",store.get(first)["songState"])
            assertEquals(1L,store.get(first)["ocrVersion"])
            val known = store.songs.search("inkar-usi").first()["id"] as Long
            store.songs.confirm(second,known,"","",listOf("自制测试"))
            assertEquals(2,store.songs.search("自制测试").size)
            store.songs.confirm(second,0,"","",emptyList(),true)
            assertEquals(0L,store.get(second)["songId"])
            assertEquals("not_applicable",store.get(second)["songState"])
        } finally { store.close() }
    }

    @Test fun migrationPreservesManualTitlesAndOcrCompletion() {
        val context = RuntimeEnvironment.getApplication()
        val db = context.openOrCreateDatabase("rhythm-benchmark-song-migration.db",Context.MODE_PRIVATE,null)
        db.execSQL("CREATE TABLE images(id TEXT PRIMARY KEY,song TEXT,ocrVersion INTEGER,sourceRemoved INTEGER,trashed INTEGER,taken INTEGER,categoryCode TEXT)")
        db.execSQL("INSERT INTO images VALUES('existing','User title',1,1,0,123,'phira')")
        db.version = 5
        db.close()
        val store = RhythmStore(context,true,"song-migration")
        try {
            store.readableDatabase.rawQuery("SELECT song,songManual,songState,ocrVersion,sourceRemoved FROM images",null).use {
                assertTrue(it.moveToFirst())
                assertEquals("User title",it.getString(0)); assertEquals(1,it.getInt(1))
                assertEquals("manual",it.getString(2)); assertEquals(1,it.getInt(3)); assertEquals(1,it.getInt(4))
            }
        } finally { store.close() }
    }
}
