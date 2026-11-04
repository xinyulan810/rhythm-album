package deckers.thibault.aves.rhythm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/** Private library only. Never deletes or opens a system content URI for writing. */
class RhythmStore internal constructor(val context: Context, private val isolated: Boolean = false, private val benchmarkRun: String = "default") :
    SQLiteOpenHelper(context, if (isolated) "rhythm-benchmark-$benchmarkRun.db" else "rhythm-library-v1.db", null, 8) {
    val operations = java.util.concurrent.locks.ReentrantLock(true)
    @Volatile var songGeneration = 0L
    @Volatile var songParallel = 0
    val freshIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val root = File(context.filesDir, if (isolated) "rhythm-benchmark-$benchmarkRun" else "rhythm-library").apply { mkdirs() }
    val cacheRoot = if (isolated) File(context.cacheDir, "rhythm-benchmark-$benchmarkRun").apply { mkdirs() } else context.cacheDir
    val prefs = context.getSharedPreferences(if (isolated) "rhythm-benchmark" else "rhythm-library", Context.MODE_PRIVATE)
    val categories by lazy { RhythmCategories(this) }
    val songs by lazy { RhythmSongs(this) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE images(
            id TEXT PRIMARY KEY, source TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
            game TEXT NOT NULL DEFAULT '', song TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '',
            eligible INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'queued',
            error TEXT NOT NULL DEFAULT '', originalBytes INTEGER NOT NULL DEFAULT 0,
            storedBytes INTEGER NOT NULL DEFAULT 0, width INTEGER NOT NULL DEFAULT 0,
            height INTEGER NOT NULL DEFAULT 0, anchor INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX images_queue ON images(status, created)")
        db.execSQL("CREATE INDEX images_reference ON images(anchor, game, width, height)")
        upgradeGallery(db)
        upgradeCategories(db)
        upgradeOcr(db)
        upgradeManaged(db)
        RhythmSongs.migrate(db)
        upgradeSongLayout(db)
        upgradeSongGrams(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeGallery(db)
        if (oldVersion < 3) upgradeCategories(db)
        if (oldVersion < 4) upgradeOcr(db)
        if (oldVersion < 5) upgradeManaged(db)
        if (oldVersion < 6) RhythmSongs.migrate(db)
        if (oldVersion < 7) upgradeSongLayout(db)
        if (oldVersion < 8) upgradeSongGrams(db)
    }
    private fun upgradeSongGrams(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE image_song_grams(imageId TEXT NOT NULL,gram TEXT NOT NULL,PRIMARY KEY(imageId,gram))")
        db.execSQL("CREATE INDEX image_song_gram_lookup ON image_song_grams(gram,imageId)")
        db.rawQuery("SELECT id,song,songRaw FROM images",null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val values = listOf(cursor.getString(1),cursor.getString(2)).flatMap(::songGrams).distinct()
                values.forEach { db.execSQL("INSERT OR IGNORE INTO image_song_grams(imageId,gram) VALUES(?,?)",arrayOf(id,it)) }
            }
        }
    }
    private fun upgradeSongLayout(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE images ADD COLUMN songLayoutCode TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE images SET songLayoutCode=categoryCode")
    }
    private fun upgradeManaged(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE images ADD COLUMN sourceRemoved INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN sourceDeleteError TEXT NOT NULL DEFAULT ''")
    }
    private fun upgradeOcr(db: SQLiteDatabase) {
        for (field in listOf("ocrText", "ocrSearch", "ocrError", "ocrLatin", "achievement", "achievementEvidence"))
            db.execSQL("ALTER TABLE images ADD COLUMN $field TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE images ADD COLUMN ocrVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN achievementOverride TEXT NOT NULL DEFAULT 'auto'")
        db.execSQL("CREATE INDEX images_ocr_queue ON images(ocrVersion,trashed,status)")
    }
    private fun upgradeCategories(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE images ADD COLUMN categoryCode TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE images ADD COLUMN classified INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN manualCategory INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN feature TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE categories(code TEXT PRIMARY KEY,name TEXT NOT NULL,nameKey TEXT NOT NULL,target TEXT NOT NULL)")
        db.execSQL("CREATE TABLE category_history(id INTEGER PRIMARY KEY AUTOINCREMENT,snapshot TEXT NOT NULL)")
        db.execSQL("CREATE INDEX images_category ON images(categoryCode,trashed,status)")
        // Preserve explicitly confirmed old classifications, including on upgrade.
        db.rawQuery("SELECT DISTINCT game FROM images WHERE eligible=1 AND game!=''", null).use { c ->
            while (c.moveToNext()) {
                val game = c.getString(0)
                val code = "legacy-" + UUID.nameUUIDFromBytes(game.toByteArray()).toString()
                db.execSQL("INSERT INTO categories VALUES(?,?,?,?)", arrayOf(code, game, RhythmCategories.nameKey(game), code))
                db.execSQL("UPDATE images SET categoryCode=?,manualCategory=1,classified=1 WHERE eligible=1 AND game=?",
                    arrayOf(code, game))
            }
        }
    }
    private fun upgradeGallery(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE images ADD COLUMN taken INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN sourceKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE images SET taken=created")
        db.rawQuery("SELECT id,source FROM images", null).use { c ->
            while (c.moveToNext()) {
                db.execSQL("UPDATE images SET sourceKey=? WHERE id=?", arrayOf(sourceKey(c.getString(1)), c.getString(0)))
            }
        }
        db.execSQL("CREATE INDEX images_timeline ON images(trashed,taken,id)")
        db.execSQL("CREATE INDEX images_source_key ON images(sourceKey)")
    }
    fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Invalid image id" }
        return File(root, id)
    }
    @Synchronized
    fun enqueue(source: String, name: String, game: String, eligible: Boolean, taken: Long = System.currentTimeMillis()): Boolean {
        val key = sourceKey(source)
        readableDatabase.rawQuery("SELECT 1 FROM images WHERE sourceKey=? OR source=? LIMIT 1", arrayOf(key, source)).use {
            if (it.moveToFirst()) return false
        }
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("source", source)
            put("name", name.take(256)); put("game", game.take(100))
            put("eligible", if (eligible && game.isNotBlank()) 1 else 0)
            put("created", System.currentTimeMillis())
            put("taken", taken.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("sourceKey", key)
        }
        return writableDatabase.insertWithOnConflict("images", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun update(id: String, vararg fields: Pair<String, Any>) {
        val values = ContentValues()
        for ((key, value) in fields) when (value) {
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            else -> error("Invalid database value")
        }
        writableDatabase.update("images", values, "id=?", arrayOf(id))
        if (fields.any { it.first == "song" || it.first == "songRaw" }) {
            val db = writableDatabase
            db.delete("image_song_grams","imageId=?",arrayOf(id))
            val row = db.rawQuery("SELECT song,songRaw FROM images WHERE id=?",arrayOf(id)).use {
                if (it.moveToFirst()) listOf(it.getString(0),it.getString(1)) else emptyList()
            }
            row.flatMap(::songGrams).distinct().forEach {
                db.execSQL("INSERT OR IGNORE INTO image_song_grams(imageId,gram) VALUES(?,?)",arrayOf(id,it))
            }
        }
    }
    private fun songGrams(value: String): Set<String> {
        val compact = RhythmSongMatcher.compact(value).take(300)
        if (compact.isEmpty()) return emptySet()
        return if (compact.length < 3) setOf(compact) else compact.windowed(3).toSet()
    }
    fun query(where: String = "1", args: Array<String> = emptyArray(), limit: Int = 100, offset: Int = 0,
              ascending: Boolean = false): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        val order = if (ascending) "taken ASC, id ASC" else "taken DESC, id DESC"
        readableDatabase.query("images", null, where, args, null, null, order, "$offset,$limit").use { cursor ->
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any>()
                for (i in 0 until cursor.columnCount) {
                    row[cursor.getColumnName(i)] = if (cursor.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER) cursor.getLong(i)
                    else cursor.getString(i) ?: ""
                }
                val dir = directory(row["id"] as String)
                row["thumbnail"] = File(dir, "thumbnail.jpg").takeIf { it.exists() }?.path ?: ""
                rows.add(row)
            }
        }
        return rows
    }
    fun get(id: String) = query("id=?", arrayOf(id), 1).firstOrNull() ?: error("Image not found")
    fun next(): Map<String, Any>? {
        // In managed mode finish each imported image before importing the next,
        // so it becomes a deletion candidate without waiting for the full scan.
        if (prefs.getBoolean("managedScreenshots", false)) {
            query("ocrVersion=0 AND ocrError='' AND classified=1 AND trashed=0 AND status IN ('compressed','ready')",
                limit = 1).firstOrNull()?.let { return it }
            queueFirst("pending")?.let { return it }
        }
        queueFirst("queued")?.let { return it }
        return query("classified=0 AND trashed=0 AND status IN ('pending','compressed','ready')", limit = 1).firstOrNull() ?:
        queueFirst("pending") ?:
        query("ocrVersion=0 AND ocrError='' AND trashed=0 AND status IN ('compressed','ready')", limit = 1).firstOrNull() ?:
        query("songVersion<${RhythmSongs.VERSION} AND songManual=0 AND songError='' AND ocrVersion>0 AND trashed=0 AND status IN ('compressed','ready')", limit = 1).firstOrNull()
    }
    private fun queueFirst(status: String): Map<String, Any>? {
        var id: String? = null
        readableDatabase.rawQuery("SELECT id FROM images WHERE status=? AND trashed=0 ORDER BY created ASC, id ASC LIMIT 1", arrayOf(status)).use {
            if (it.moveToFirst()) id = it.getString(0)
        }
        return id?.let(::get)
    }
    fun stats(): Map<String, Any> {
        val result = mutableMapOf<String, Any>("paused" to prefs.getBoolean("paused", false))
        readableDatabase.rawQuery(
            """SELECT COUNT(*), COALESCE(SUM(originalBytes),0), COALESCE(SUM(storedBytes),0),
               COALESCE(SUM(CASE WHEN status IN ('queued','pending','processing') THEN 1 ELSE 0 END),0),
               COALESCE(SUM(CASE WHEN status='compressed' THEN 1 ELSE 0 END),0),
               COALESCE(SUM(CASE WHEN status='error' THEN 1 ELSE 0 END),0) FROM images WHERE trashed=0""", null
        ).use { c ->
            c.moveToFirst()
            listOf("count", "originalBytes", "storedBytes", "pending", "compressed", "errors").forEachIndexed { i, key -> result[key] = c.getLong(i) }
        }
        result["albums"] = readableDatabase.rawQuery("SELECT DISTINCT album FROM images WHERE album!='' AND trashed=0 ORDER BY album LIMIT 100", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        result["categories"] = categories.albums()
        result["recentThumbnails"] = query("trashed=0", limit = 4).map { it["thumbnail"] as String }
        result["otherThumbnails"] = query("trashed=0 AND NOT (${RhythmCategories.VISIBLE})", limit = 4).map { it["thumbnail"] as String }
        result["otherCount"] = count("trashed=0 AND NOT (${RhythmCategories.VISIBLE})", emptyArray())
        result["classifying"] = count("trashed=0 AND classified=0 AND status IN ('pending','compressed','ready')", emptyArray())
        result["canUndoCategory"] = categories.canUndo()
        result["ocrPending"] = count("ocrVersion=0 AND ocrError='' AND trashed=0 AND status IN ('compressed','ready')", emptyArray())
        result["ocrErrors"] = count("ocrError!='' AND trashed=0", emptyArray())
        result["songPending"] = count("songVersion<${RhythmSongs.VERSION} AND songManual=0 AND songError='' AND ocrVersion>0 AND trashed=0 AND status IN ('compressed','ready')", emptyArray())
        result["songErrors"] = count("songError!='' AND trashed=0", emptyArray())
        result["songProgress"] = songProgress()
        return result
    }
    fun songProgress(): Map<String, Any> = readableDatabase.rawQuery("""
        SELECT COUNT(*),
        COALESCE(SUM(CASE WHEN songVersion>=${RhythmSongs.VERSION} OR songManual=1 OR songError!='' OR ocrError!='' THEN 1 ELSE 0 END),0),
        COALESCE(SUM(CASE WHEN songError!='' OR (ocrError!='' AND songManual=0 AND songVersion<${RhythmSongs.VERSION}) THEN 1 ELSE 0 END),0),
        COALESCE(SUM(CASE WHEN songId>0 THEN 1 ELSE 0 END),0),
        COALESCE(SUM(CASE WHEN songVersion<${RhythmSongs.VERSION} AND songManual=0 AND songError='' AND ocrError='' AND
            (ocrVersion=0 OR status NOT IN ('compressed','ready')) THEN 1 ELSE 0 END),0)
        FROM images WHERE trashed=0 AND status IN ('queued','pending','processing','compressed','ready')
        """, null).use {
        it.moveToFirst()
        mapOf("total" to it.getLong(0), "completed" to it.getLong(1),
            "failed" to it.getLong(2), "matched" to it.getLong(3), "waiting" to it.getLong(4),
            "parallel" to songParallel,
            "paused" to prefs.getBoolean("paused", false))
    }
    fun songQueue(limit: Int) = query(
        "songVersion<${RhythmSongs.VERSION} AND songManual=0 AND songError='' AND ocrVersion>0 AND classified=1 AND trashed=0 AND status IN ('compressed','ready')",
        limit = limit.coerceIn(1,32))
    fun relatedCandidates(title: String, linkedId: Long, imageId: String): List<Map<String, Any>> {
        val normalized = RhythmSongMatcher.compact(title).take(300)
        val grams = songGrams(normalized)
        val candidates = linkedSetOf<String>()
        if (grams.isNotEmpty()) {
            val placeholders = grams.joinToString(",") { "?" }
            readableDatabase.rawQuery("""SELECT g.imageId FROM image_song_grams g JOIN images i ON i.id=g.imageId
                WHERE g.gram IN ($placeholders) AND i.trashed=0 AND i.id!=?
                GROUP BY g.imageId ORDER BY COUNT(*) DESC LIMIT 400""", (grams.toList()+imageId).toTypedArray()).use {
                while (it.moveToNext()) candidates.add(it.getString(0))
            }
        }
        if (linkedId > 0) {
            readableDatabase.rawQuery("SELECT id FROM images WHERE songId=? AND trashed=0 AND id!=? ORDER BY taken DESC,id DESC LIMIT 3000",
                arrayOf(linkedId.toString(),imageId)).use { while(it.moveToNext() && candidates.size < 800) candidates.add(it.getString(0)) }
        }
        if (candidates.isEmpty()) return emptyList()
        return query("id IN (${candidates.joinToString(",") { "?" }}) AND trashed=0",
            candidates.toTypedArray(), candidates.size.coerceAtMost(800))
    }

    fun commitSongResult(id: String, generation: Long, apply: () -> Unit): Boolean {
        operations.lock()
        try {
            val row = query("id=?",arrayOf(id),1).firstOrNull() ?: return false
            if (songGeneration != generation || (row["songManual"] as Number).toInt() != 0 ||
                (row["trashed"] as Number).toInt() != 0) return false
            apply()
            return true
        } finally { operations.unlock() }
    }
    fun count(where: String, args: Array<String>): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM images WHERE $where", args).use { it.moveToFirst(); it.getLong(0) }

    // Logical deletion leaves source tombstones so auto-import never resurrects a deleted photo.
    // Called under operations lock. All live and trashed images retain their immutable anchors.
    fun collectDeleted() {
        val retained = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT id,trashed FROM images", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (c.getInt(1) == 2) deleted.add(id) else {
                    retained.add(id)
                    val marker = File(directory(id), "manifest.json")
                    if (marker.exists()) {
                        val stripes = manifest(marker).getJSONArray("stripes")
                        for (i in 0 until stripes.length()) {
                            stripes.getJSONObject(i).optString("reference").takeIf { it.isNotEmpty() }?.let(retained::add)
                        }
                    }
                }
            }
        }
        for (id in deleted.filterNot(retained::contains)) {
            val dir = directory(id)
            if (dir.exists()) check(dir.deleteRecursively()) { "副本回收未完成，请重试清空回收站" }
        }
        for (id in deleted) {
            freshIds.remove(id)
            for (cache in listOf("rhythm-full-v1", "rhythm-preview-v2", "rhythm-preview")) {
                File(File(context.cacheDir, cache), "$id.png").delete()
            }
            File(File(context.cacheDir, "rhythm-display-v1"), "$id.bmp").delete()
            File(context.cacheDir, "rhythm-share").listFiles()?.filter { it.name.startsWith("Rhythm_${id}_") }?.forEach { it.delete() }
        }
    }
    fun recover() {
        categories.normalizeInitialNames()
        writableDatabase.execSQL("UPDATE images SET status='pending' WHERE status='processing'")
        // Repair only the precisely identified first 0.3 test-build initialization failure.
        for (row in query("status='error' AND error LIKE '%hr2 kq2.a%'", limit = 10000)) {
            val id = row["id"] as String
            val status = if (File(directory(id), "manifest.json").exists()) "compressed"
                else if (File(directory(id), "original").exists()) "pending" else "queued"
            update(id, "status" to status, "error" to "")
        }
    }
    companion object {
        fun sourceKey(source: String): String {
            val uri = android.net.Uri.parse(source)
            val document = uri.lastPathSegment.orEmpty()
            if (uri.authority == "com.android.providers.media.documents" && document.startsWith("image:")) {
                return "media:${document.substringAfter(':')}"
            }
            if (uri.authority == "media" && uri.path.orEmpty().contains("/images/media/")) return "media:$document"
            return source
        }
        @Volatile private var instance: RhythmStore? = null
        fun get(context: Context): RhythmStore = instance ?: synchronized(this) {
            instance ?: RhythmStore(context.applicationContext).also { instance = it }
        }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return hex(digest.digest())
        }
        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            val digits = "0123456789abcdef"
            for (byte in bytes) {
                val value = byte.toInt() and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
        fun syncWrite(file: File, text: String) {
            java.io.FileOutputStream(file).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        }
        fun manifest(file: File): JSONObject {
            require(file.length() in 1..131072) { "Manifest size limit" }
            return JSONObject(file.readText()).also { require(it.getInt("version") in 1..2) }
        }
    }
}
