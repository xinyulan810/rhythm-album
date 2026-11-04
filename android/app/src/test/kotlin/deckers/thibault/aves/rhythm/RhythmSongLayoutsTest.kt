package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

class RhythmSongLayoutsTest {
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
        assertNull(RhythmSongLayouts.game("bandori-gacha","MAX RECALL PURE"))
    }
    @Test fun builtInRecognitionCropsAreBoundedAndKeepArtistsWhenNeeded() {
        val arcaea = requireNotNull(RhythmSongLayouts.crop("arcaea"))
        assertEquals(.06,arcaea.x,0.00001)
        assertEquals(.27,arcaea.bottom,0.00001)
        val astro = requireNotNull(RhythmSongLayouts.crop("astrodx"))
        assertTrue(astro.bottom > requireNotNull(RhythmSongLayouts.crop("phira")).bottom)
        assertNull(RhythmSongLayouts.crop("malody"))
        assertEquals(15,RhythmSongLayouts.supported.size)
    }
}
