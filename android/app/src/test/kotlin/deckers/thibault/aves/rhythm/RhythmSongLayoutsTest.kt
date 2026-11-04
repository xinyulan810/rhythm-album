package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

class RhythmSongLayoutsTest {
    @Test fun comboPanelUsesJudgmentGeometryAndIsSeparateFromCenteredResults() {
        val row = mapOf<String,Any>("id" to "combo","songLayoutCode" to "arcaea")
        for (combined in listOf(false,true)) {
            val original = listOf(
                RhythmSongMatcher.Line(if (combined) "TRACK COMPLETE MAX COMBO 355" else "MAX COMBO 424",
                    if (combined) .050 else .230, if (combined) .371 else .374,.309,.390),
                RhythmSongMatcher.Line("PURE",.051,.559,.086,.571),
                RhythmSongMatcher.Line("FAR",.236,.559,.259,.571),
                RhythmSongMatcher.Line("LOST",.420,.559,.454,.571),
                RhythmSongMatcher.Line("Calamity Tempest",.152,.225,.331,.256),
                RhythmSongMatcher.Line("Diceros Bicornis",.152,.270,.249,.286),
                RhythmSongMatcher.Line("Future 12+ Chart author",.159,.301,.321,.319),
                RhythmSongMatcher.Line("Decorative duplicate",.40,.05,.65,.12),
                RhythmSongMatcher.Line("09872432 EX",.052,.419,.381,.475))
            // Changes in panel size and horizontal placement do not use device-specific rules.
            for (factor in listOf(1.0,.85)) {
                val lines = original.map { it.copy(x=it.x*factor+.02,right=it.right*factor+.02,
                    y=it.y*factor+.02,bottom=it.bottom*factor+.02) }
                val detection = RhythmSongLayouts.detect(row,lines)
                assertEquals("arcaea:combo-left-v1",detection.key)
                val title = RhythmSongLayouts.locate(detection.game,lines,detection.anchor)
                assertEquals("Calamity Tempest",title?.text)
                assertEquals("Diceros Bicornis",title?.artist)
                assertNull(RhythmSongLayouts.locate(detection.game,lines.filter { it.text != "Calamity Tempest" },detection.anchor))
                val crop = requireNotNull(RhythmSongLayouts.crop(detection.game,detection.anchor))
                assertTrue(title!!.line.x > crop.x && title.line.bottom < crop.bottom)
            }
        }
    }
    @Test fun arcaeaAnchorsSeparatePositionsAndExcludeArtistOnlyOcr() {
        val row = mapOf<String,Any>("id" to "sample", "songLayoutCode" to "arcaea", "categoryCode" to "renamed")
        val cases = listOf(
            listOf(.326,.349,.107,.182,.186,.227),
            listOf(.326,.349,.113,.166,.186,.228),
            listOf(.344,.365,.154,.200,.217,.255),
            listOf(.326,.349,.112,.166,.189,.218),
            listOf(.361,.382,.191,.234,.250,.282),
            listOf(.344,.365,.183,.240,.250,.280))
        val keys = mutableListOf<String>()
        for (b in cases) {
            val anchor = RhythmSongMatcher.Line("MAX RECALL 1234",.08,b[0],.22,b[1])
            val title = RhythmSongMatcher.Line("Unlisted fanmade song",.33,b[2],.67,b[3])
            val artist = RhythmSongMatcher.Line("Composer",.43,b[4],.57,b[5])
            val lines = listOf(anchor,title,artist,RhythmSongMatcher.Line("Username",.35,.02,.65,.06))
            val detection = RhythmSongLayouts.detect(row,lines)
            keys.add(detection.key)
            assertEquals("Unlisted fanmade song",RhythmSongLayouts.locate(detection.game,lines,detection.anchor)?.text)
            assertNull("Artist alone must stay unrecognized",RhythmSongLayouts.locate(detection.game,lines-title,detection.anchor))
            assertEquals(detection.key,RhythmSongLayouts.detect(row,lines.map {
                if (it === title) it.copy(text="A different title") else it
            }).key)
        }
        assertEquals(keys[0],keys[1])
        assertNotEquals(keys[0],keys[4])
        val unknown = RhythmSongLayouts.detect(mapOf("id" to "unknown", "categoryCode" to "arcaea"),emptyList())
        assertEquals("image:unknown",unknown.key)
        assertEquals("",unknown.game)
        assertEquals("image:sample",RhythmSongLayouts.detect(row,emptyList()).key)
        assertNull(RhythmSongLayouts.locate("arcaea",emptyList()))
    }
    @Test fun batchRunnerUsesBoundedConcurrentLanes() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = ConcurrentHashMap.newKeySet<Int>()
        val gate = CountDownLatch(2)
        RhythmSongBatch.run((0 until 12).toList(),2,{false}) { _, value ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it,now) }
            try {
                gate.countDown()
                assertTrue("Both OCR lanes must start concurrently",gate.await(5,java.util.concurrent.TimeUnit.SECONDS))
                completed.add(value)
            } finally { active.decrementAndGet() }
        }
        assertTrue(gate.await(1,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(12,completed.size)
        assertEquals(2,peak.get())
    }
    @Test fun batchStopsClaimingWorkAfterPause() = runBlocking {
        val completed = AtomicInteger()
        RhythmSongBatch.run((0 until 12).toList(),1,{completed.get() > 0}) { _, _ ->
            completed.incrementAndGet()
        }
        assertEquals(1,completed.get())
    }
    @Test fun adaptiveParallelismIsBoundedByResources() {
        assertEquals(2,RhythmPolicy.songJobs(8,512,1024L*1024*1024,false))
        assertEquals(1,RhythmPolicy.songJobs(8,512,512L*1024*1024,false))
        assertEquals(1,RhythmPolicy.songJobs(8,128,1024L*1024*1024,false))
        assertEquals(1,RhythmPolicy.songJobs(1,512,1024L*1024*1024,false))
        assertEquals(1,RhythmPolicy.songJobs(8,512,1024L*1024*1024,true))
    }
    @Test fun measuredTitleRegionsExcludeScoreAndPlayer() {
        val fixtures = listOf(
            Triple("phira", "Zettai Reido", listOf(.432,.046,.58,.073)),
            Triple("phigros", "Abso uTe?disoÄdeR", listOf(.08,.66,.34,.70)),
            Triple("pjsekai", "初音ミクの消失", listOf(.198,.030,.312,.051)),
            Triple("astrodx", "QZKago Requiem[SD]", listOf(.065,.103,.275,.133)),
            Triple("bandori", "V.I.P MONSTER", listOf(.200,.134,.353,.157)),
            Triple("dynamix", "Lucid Trigger", listOf(.639,.119,.909,.173)),
            Triple("dynamite", "Trrricksters!!", listOf(.379,.237,.617,.276)),
            Triple("cytus2", "Hydra", listOf(.463,.055,.539,.096)),
            Triple("musedash", "Exit This Earth's Atomosphere", listOf(.298,.030,.702,.068)),
            Triple("worlddaistar", "六兆年と一夜物語", listOf(.139,.137,.373,.160)),
            Triple("paradigm", "Sample song", listOf(.421,.126,.600,.152)),
            Triple("sonolus", "Sample chart song", listOf(.091,.253,.902,.315)),
            Triple("overrapid", "The King's RetL", listOf(.088,.621,.292,.678)),
            Triple("holodreams", "FIRE BIRD", listOf(.171,.801,.253,.820))
        )
        for ((game,text,b) in fixtures) {
            val line = RhythmSongMatcher.Line(text,b[0],b[1],b[2],b[3])
            val lines = listOf(line, RhythmSongMatcher.Line("Player",.02,.02,.09,.05),
                RhythmSongMatcher.Line("0999999",.5,.4,.7,.5),
                RhythmSongMatcher.Line("PERFECT",.5,.6,.6,.63))
            assertEquals(game,text,RhythmSongLayouts.locate(game,lines)?.text)
        }
        assertNull(RhythmSongLayouts.locate("bandori-gacha",emptyList()))
        assertNull(RhythmSongLayouts.locate("malody",emptyList()))
    }
    @Test fun customAreaUsesOneEngineAndDoesNotFollowAlbumName() {
        val box = RhythmSongLayouts.Box(.2,.1,.8,.2)
        val lines = listOf(
            RhythmSongMatcher.Line("Custom Song",.3,.12,.6,.16),
            RhythmSongMatcher.Line("Custom Song",.3,.12,.6,.16,"japanese"),
            RhythmSongMatcher.Line("PERFECT",.3,.5,.6,.55))
        assertEquals("Custom Song",RhythmSongLayouts.custom(box,lines)?.text)
        assertEquals("phira",RhythmSongLayouts.code(mapOf("songLayoutCode" to "phira","categoryCode" to "my-album")))
        assertEquals("bandori-gacha:preset-v1",RhythmSongLayouts.detect(
            mapOf("id" to "gacha","songLayoutCode" to "bandori-gacha"),emptyList()).key)
    }
    @Test fun builtInRecognitionCropsAreBoundedAndKeepArtistsWhenNeeded() {
        assertNull(RhythmSongLayouts.crop("arcaea"))
        val arcaea = requireNotNull(RhythmSongLayouts.crop("arcaea",RhythmSongMatcher.Line("MAX RECALL",.08,.36,.22,.38)))
        assertEquals(.06,arcaea.x,0.00001)
        assertEquals(.325,arcaea.bottom,0.00001)
        val astro = requireNotNull(RhythmSongLayouts.crop("astrodx"))
        assertTrue(astro.bottom > requireNotNull(RhythmSongLayouts.crop("phira")).bottom)
        assertNull(RhythmSongLayouts.crop("malody"))
        assertEquals(15,RhythmSongLayouts.supported.size)
    }
}
