package deckers.thibault.aves.rhythm

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Shared song identity, game/provider mappings and scoped searchable names. No chart equivalence. */
class RhythmSongs(private val store: RhythmStore) {
    private val db get() = store.writableDatabase
    private var loaded = false
    private val indexes = mutableMapOf<String, RhythmSongMatcher.Index>()

    @Synchronized fun ensureCatalog() {
        if (loaded) return
        val pack = JSONObject(store.context.assets.open("rhythm-arcaea.json").bufferedReader().use { it.readText() })
        val revision = pack.getString("revision")
        val installed = db.rawQuery("SELECT value FROM song_meta WHERE key='arcaeaRevision'", null).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }
        if (installed != revision) {
            db.beginTransaction()
            try {
                val songs = pack.getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    val sourceId = song.getString("sourceId")
                    val existing = db.rawQuery("SELECT songId FROM song_sources WHERE game='arcaea' AND provider='arcaea-toolbelt' AND sourceId=?",
                        arrayOf(sourceId)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    val title = song.getString("title")
                    val artist = song.optString("artist")
                    val id = if (existing != 0L) existing else db.insertOrThrow("songs", null, ContentValues().apply {
                        put("title", title); put("artist", artist); put("origin", "arcaea-toolbelt")
                    })
                    db.execSQL("INSERT OR IGNORE INTO song_sources(game,provider,sourceId,songId) VALUES('arcaea','arcaea-toolbelt',?,?)",
                        arrayOf<Any>(sourceId, id))
                    db.execSQL("UPDATE songs SET title=?,artist=? WHERE id=?", arrayOf<Any>(title, artist, id))
                    // Names are additive: historical titles and personal aliases survive updates.
                    addName(id, "arcaea", title, "display")
                    for (field in listOf("names", "aliases")) {
                        val names = song.optJSONArray(field) ?: JSONArray()
                        for (j in 0 until names.length()) addName(id, "arcaea", names.getString(j),
                            if (field == "names") "display" else "alias")
                    }
                }
                db.execSQL("INSERT OR REPLACE INTO song_meta(key,value) VALUES('arcaeaRevision',?)", arrayOf(revision))
                db.execSQL("INSERT OR REPLACE INTO song_meta(key,value) VALUES('arcaeaVersion',?)", arrayOf(pack.optString("sourceVersion")))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        loaded = true
    }
    private fun addName(id: Long, game: String, name: String, kind: String) {
        val value = name.trim().take(300)
        if (value.isNotEmpty()) db.execSQL("INSERT OR IGNORE INTO song_names(songId,game,name,nameKey,kind) VALUES(?,?,?,?,?)",
            arrayOf<Any>(id, game, value, RhythmSongMatcher.key(value), kind))
    }
    @Synchronized fun match(title: RhythmSongMatcher.Title, game: String = "arcaea"): RhythmSongMatcher.Match {
        ensureCatalog()
        val matcher = indexes[game] ?: run {
            val songs = db.rawQuery("""SELECT DISTINCT s.id,s.title,s.artist FROM songs s
                JOIN song_sources g ON g.songId=s.id WHERE g.game=?""", arrayOf(game)).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val names = db.rawQuery("SELECT name FROM song_names WHERE songId=? AND game=? AND kind='display'",
                            arrayOf(id.toString(), game)).use { n -> buildList { while (n.moveToNext()) add(n.getString(0)) } }
                        add(RhythmSongMatcher.Song(id, c.getString(1), c.getString(2), names.ifEmpty { listOf(c.getString(1)) }))
                    }
                }
            }
            RhythmSongMatcher.Index(songs).also { indexes[game] = it }
        }
        return matcher.match(title)
    }
    fun applyRecognition(id: String, title: RhythmSongMatcher.Title?, evidence: String, game: String = "arcaea") {
        if ((store.get(id)["songManual"] as Number).toInt() != 0) return
        if (title == null) {
            store.update(id, "songVersion" to VERSION, "songState" to "unmatched", "songEvidence" to evidence,
                "songId" to 0L, "song" to "", "songRaw" to "", "songArtist" to "", "songCandidates" to "[]", "songError" to "")
            return
        }
        val match = match(title, game)
        val candidates = JSONArray()
        match.candidates.forEach { c -> candidates.put(JSONObject().put("id", c.song.id).put("title", c.song.title)
            .put("artist", c.song.artist).put("score", c.score)) }
        store.update(id, "song" to (match.accepted?.title ?: title.text), "songRaw" to title.text,
            "songArtist" to title.artist, "songId" to (match.accepted?.id ?: 0L),
            "songState" to if (match.accepted != null) "matched" else if (match.candidates.isNotEmpty()) "candidate" else "unmatched",
            "songCandidates" to candidates.toString(), "songEvidence" to evidence, "songError" to "",
            "songVersion" to VERSION)
    }
    @Synchronized fun confirm(imageId: String, selected: Long, title: String, artist: String, aliases: List<String>, disabled: Boolean = false) {
        ensureCatalog()
        require(aliases.size <= 30)
        val row = store.get(imageId)
        val game = RhythmSongLayouts.code(row).ifBlank { "local" }
        db.beginTransaction()
        try {
            if (disabled || (selected == 0L && title.isBlank())) {
                store.update(imageId, "song" to "", "songId" to 0L, "songManual" to 1,
                    "songState" to "not_applicable", "songVersion" to VERSION, "songCandidates" to "[]", "songError" to "")
            } else {
                val id = if (selected > 0) selected else {
                    require(title.trim().length in 1..300)
                    // New local identities are only created by explicit user confirmation.
                    val source = RhythmSongMatcher.key(title) + "\u001f" + RhythmSongMatcher.key(artist)
                    val previous = db.rawQuery("SELECT songId FROM song_sources WHERE game=? AND provider='local' AND sourceId=?",
                        arrayOf(game, source)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    if (previous > 0) previous else {
                        val localId = db.insertOrThrow("songs", null, ContentValues().apply {
                            put("title", title.trim()); put("artist", artist.trim().take(300)); put("origin", "local")
                        })
                        db.execSQL("INSERT INTO song_sources(game,provider,sourceId,songId) VALUES(?,'local',?,?)", arrayOf<Any>(game, source, localId))
                        addName(localId, game, title, "display")
                        localId
                    }
                }
                val song = db.rawQuery("SELECT title,artist FROM songs WHERE id=?", arrayOf(id.toString())).use {
                    require(it.moveToFirst()) { "曲目不存在，请重新选择" }; it.getString(0) to it.getString(1)
                }
                aliases.forEach { addName(id, game, it, "personal") }
                store.update(imageId, "song" to song.first, "songId" to id, "songArtist" to song.second,
                    "songManual" to 1, "songState" to "manual", "songVersion" to VERSION, "songError" to "")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        indexes.clear()
    }
    fun search(query: String): List<Map<String, Any>> {
        ensureCatalog()
        val key = RhythmSongMatcher.key(query)
        if (key.isBlank()) return emptyList()
        return db.rawQuery("""SELECT DISTINCT s.id,s.title,s.artist FROM songs s JOIN song_names n ON n.songId=s.id
            WHERE instr(n.nameKey,?)>0 ORDER BY s.title LIMIT 30""", arrayOf(key)).use {
            buildList { while (it.moveToNext()) add(mapOf("id" to it.getLong(0), "title" to it.getString(1), "artist" to it.getString(2))) }
        }
    }
    /** Discovery only: never changes identities or confirms a fuzzy result. */
    fun relatedIds(imageId: String): List<String> {
        val source = store.get(imageId)
        val songId = (source["songId"] as Number).toLong()
        val titles = listOf(source["song"] as String, source["songRaw"] as String)
            .map(RhythmSongMatcher::compact).filter(String::isNotBlank).distinct()
        if (songId == 0L && titles.isEmpty()) return emptyList()
        val candidates = store.relatedCandidates(titles.maxByOrNull(String::length).orEmpty(),songId,imageId)
        return candidates.mapNotNull { row ->
            val linked = songId > 0 && (row["songId"] as Number).toLong() == songId
            val values = listOf(row["song"] as String,row["songRaw"] as String).map(RhythmSongMatcher::compact).filter(String::isNotBlank)
            val score = if (linked) 2.0 else titles.maxOfOrNull { a -> values.maxOfOrNull { b -> relatedScore(a,b) } ?: 0.0 } ?: 0.0
            if (score >= .42) (row["id"] as String) to score else null
        }.sortedByDescending { it.second }.take(400).map { it.first }
    }
    private fun relatedScore(a: String, b: String): Double {
        if (a == b) return 1.0
        if (minOf(a.length,b.length) < 3) return 0.0
        if ((a.contains(b) || b.contains(a)) && minOf(a.length,b.length).toDouble()/maxOf(a.length,b.length) >= .35)
            return .8
        // Cheap character overlap before bounded edit distance.
        val short = if (a.length < b.length) a else b
        val other = if (a.length < b.length) b else a
        if (short.toSet().count { it in other } < short.toSet().size * .5) return 0.0
        return RhythmSongMatcher.similarity(a,b)
    }
    companion object {
        const val VERSION = 2
        fun migrate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE songs(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,artist TEXT NOT NULL,origin TEXT NOT NULL)")
            db.execSQL("CREATE TABLE song_sources(game TEXT NOT NULL,provider TEXT NOT NULL,sourceId TEXT NOT NULL,songId INTEGER NOT NULL,PRIMARY KEY(game,provider,sourceId))")
            db.execSQL("CREATE TABLE song_names(songId INTEGER NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,nameKey TEXT NOT NULL,kind TEXT NOT NULL,UNIQUE(songId,game,nameKey,kind))")
            db.execSQL("CREATE INDEX song_names_key ON song_names(nameKey)")
            db.execSQL("CREATE TABLE song_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            for (field in listOf("songRaw", "songArtist", "songState", "songEvidence", "songError"))
                db.execSQL("ALTER TABLE images ADD COLUMN $field TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE images ADD COLUMN songCandidates TEXT NOT NULL DEFAULT '[]'")
            for (field in listOf("songId", "songManual", "songVersion"))
                db.execSQL("ALTER TABLE images ADD COLUMN $field INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE images SET songManual=1,songState='manual',songVersion=1 WHERE song!=''")
            db.execSQL("CREATE INDEX images_song ON images(songId,trashed,taken)")
            db.execSQL("CREATE INDEX images_song_queue ON images(songVersion,songManual,trashed)")
        }
    }
}
