package deckers.thibault.aves.rhythm

import org.junit.Assert.*
import org.junit.Test

class RhythmSongMatcherTest {
    private fun line(t: String, x: Int, y: Int, r: Int, b: Int, w: Int, h: Int) =
        RhythmSongMatcher.Line(t, x.toDouble()/w, y.toDouble()/h, r.toDouble()/w, b.toDouble()/h)

    @Test fun replaysFourUserScreenshotOcrLayouts() {
        // Coordinates measured with Windows OCR on the four supplied JPEGs.
        // This validates geometry only; it does not claim Android ML Kit accuracy.
        val fixtures = listOf(
            listOf(line("Shades 0f Light in a TranscendentTf'••Se—",425,92,1605,156,1920,859),
                line("ak+q",925,160,997,195,1920,859),line("FUTURE",313,249,405,268,1920,859)),
            listOf(line("Tr!cK Ma?tEr",791,99,1131,145,1920,873),
                line("Nothing But Requiem",795,162,1128,199,1920,873),line("ETERNAL",303,254,410,272,1920,873)),
            listOf(line("DEINOS PHAINEIN",445,123,840,160,1280,800),
                line("BIack101ita vs Juggernaut.",476,174,807,204,1280,800),line("INSCRIBED",98,249,203,265,1280,800)),
            listOf(line("inkar-usi",849,96,1074,143,1920,859),
                line("DIA",936,162,986,187,1920,859),line("PURE MEMORY",722,244,1194,292,1920,859)))
        fixtures.forEach {
            val located = requireNotNull(RhythmSongMatcher.locate(it))
            assertEquals(it[0].text, located.text)
            assertEquals(it[1].text, located.artist)
        }
    }
    private fun title(t: String, artist: String = "") = RhythmSongMatcher.Title(t, artist, listOf(t),
        RhythmSongMatcher.Line(t,.3,.11,.7,.16))

    @Test fun exactMatchRejectsAmbiguousIdentityAndArtistConflict() {
        val a = RhythmSongMatcher.Song(1,"inkar-usi","DIA",listOf("inkar-usi"))
        assertEquals(1L, RhythmSongMatcher.Index(listOf(a)).match(title("inkar-usi","DIA")).accepted?.id)
        assertNull(RhythmSongMatcher.Index(listOf(a,a.copy(id=2,artist="Another artist"))).match(title("inkar-usi")).accepted)
        assertNull(RhythmSongMatcher.Index(listOf(a)).match(title("inkar-usi","Totally different")).accepted)
    }
    @Test fun fuzzyNeedsArtistAndPreservesUnknownCustomTitle() {
        val song = RhythmSongMatcher.Song(1,"Shades of Light in a Transcendent Realm","ak+q",
            listOf("Shades of Light in a Transcendent Realm"))
        val index = RhythmSongMatcher.Index(listOf(song))
        assertEquals(1L,index.match(title("Shades 0f Light in a Transcendent Realm","ak+q")).accepted?.id)
        assertNull(index.match(title("Shades 0f Light in a Transcendent Realm")).accepted)
        assertNull(index.match(title("Shades of Light in a Transcendent Realms","Custom Artist")).accepted)
        assertNull(index.match(title("Unknown custom title")).accepted)
        assertNull(index.match(title("Shades")).accepted)
    }
    @Test fun unknownAndNonResultScreensCannotSupplyTitleIdentity() {
        assertFalse(RhythmSongMatcher.isArcaea("招募结果 PERFECT Song title"))
        assertTrue(RhythmSongMatcher.isArcaea("INSCRIBED MAX RECALL 293 TRACK COMPLETE"))
        assertNull(RhythmSongMatcher.locate(listOf(RhythmSongMatcher.Line("POTENTIAL",.45,.01,.55,.04))))
        val song = RhythmSongMatcher.Song(1,"123","Composer",listOf("123"))
        assertEquals(1L,RhythmSongMatcher.Index(listOf(song)).match(title("123")).accepted?.id)
        assertNull(RhythmSongMatcher.Index(listOf(song)).match(title("124")).accepted)
    }
}
