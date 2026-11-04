package deckers.thibault.aves.rhythm

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Stable visual identity is separate from user-visible names and merge mappings. */
class RhythmCategories(private val store: RhythmStore) {
    private val db get() = store.writableDatabase
    fun ensure(code: String, name: String = "") {
        require(code.isNotBlank() && code.length <= 100)
        val key = nameKey(name)
        val target = if (key.isBlank()) code else db.rawQuery(
            "SELECT code FROM categories WHERE code=target AND nameKey=? LIMIT 1", arrayOf(key)
        ).use { if (it.moveToFirst()) it.getString(0) else code }
        db.execSQL("INSERT OR IGNORE INTO categories(code,name,nameKey,target) VALUES(?,?,?,?)",
            arrayOf(code, name, key, target))
    }
    /** Distinct codec/reference identity, same visible album as its parent.
     * Existing user mappings are never overwritten. */
    fun ensureScreenCategory(code: String, name: String) {
        if (code != RhythmScreenRules.BANDORI_GACHA) {
            ensure(code, name)
            return
        }
        ensure("bandori", "BanG Dream!")
        val parent = db.rawQuery("SELECT target FROM categories WHERE code='bandori'", null).use {
            check(it.moveToFirst()); it.getString(0)
        }
        db.execSQL("INSERT OR IGNORE INTO categories(code,name,nameKey,target) VALUES(?,?,?,?)",
            arrayOf(code, "BanG Dream! · 抽卡", nameKey("BanG Dream! · 抽卡"), parent))
    }
    fun albums(): List<Map<String, Any>> = db.rawQuery(
        """SELECT c.target,t.name,COUNT(*),MAX(i.taken),SUM(CASE WHEN ${RhythmStore.SONG_MISSING} THEN 1 ELSE 0 END) FROM images i
           JOIN categories c ON c.code=i.categoryCode JOIN categories t ON t.code=c.target
           WHERE i.trashed=0 AND c.target!='other' AND (i.status='compressed' OR i.manualCategory=1)
           GROUP BY c.target,t.name ORDER BY (t.name=''),MAX(i.taken) DESC,c.target""", null).use { c ->
        buildList {
            while (c.moveToNext()) {
                val code = c.getString(0)
                val id = ids(listOf(code), 1).firstOrNull()
                add(mapOf("code" to c.getString(0), "name" to c.getString(1),
                    "count" to c.getLong(2), "latest" to c.getLong(3),
                    "songMissing" to c.getLong(4),
                    "thumbnail" to (id?.let { java.io.File(store.directory(it), "thumbnail.jpg").path } ?: "")))
            }
        }
    }
    /** Resolve complete albums, not the currently loaded UI page. */
    fun ids(codes: List<String>, limit: Int? = null): List<String> {
        val selected = codes.distinct()
        if (selected.isEmpty()) return emptyList()
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if ("" in selected) clauses.add("1")
        if ("other" in selected) clauses.add("NOT ($VISIBLE)")
        selected.filter { it.isNotEmpty() && it != "other" }.forEach {
            clauses.add("(($VISIBLE) AND categoryCode IN (SELECT code FROM categories WHERE target=?))")
            args.add(it)
        }
        require(limit == null || limit > 0)
        return db.rawQuery("SELECT id FROM images WHERE trashed=0 AND (${clauses.joinToString(" OR ")}) ORDER BY taken DESC,id DESC" +
            (limit?.let { " LIMIT $it" } ?: ""),
            args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }
    fun suggestions(): List<Map<String, Any>> {
        val actual = albums().filter { (it["name"] as String).isNotBlank() }
        val known = actual.map { nameKey(it["name"] as String) }.toSet()
        val names = JSONObject(store.context.assets.open("rhythm-classifier.json").bufferedReader().use { it.readText() }).getJSONObject("names")
        return listOf(mapOf("code" to "other", "name" to "其他", "count" to ids(listOf("other")).size)) + actual + names.keys().asSequence().map { defaultName(it, names.getString(it)) }.distinct()
            .filter { nameKey(it) !in known }.map { mapOf("name" to it, "count" to 0) }.toList()
    }
    @Synchronized fun merge(codes: List<String>, destination: String) {
        require(destination.isNotBlank()) { "请选择目标相册" }
        db.beginTransaction()
        try {
            val target = if (destination == "other") "other" else db.rawQuery("SELECT target FROM categories WHERE code=?", arrayOf(destination)).use {
                require(it.moveToFirst()) { "目标相册不存在" }; it.getString(0)
            }
            require(codes.isNotEmpty() && codes.none { it.isBlank() || it == "other" }) { "系统相册不能合并" }
            db.execSQL("INSERT INTO category_history(snapshot) VALUES(?)", arrayOf(snapshot()))
            for (code in codes.distinct()) {
                val source = db.rawQuery("SELECT target FROM categories WHERE code=?", arrayOf(code)).use {
                    require(it.moveToFirst()) { "来源相册不存在" }; it.getString(0)
                }
                if (source != target) db.execSQL("UPDATE categories SET target=? WHERE target=?", arrayOf(target, source))
            }
            db.execSQL("DELETE FROM category_history WHERE id NOT IN (SELECT id FROM category_history ORDER BY id DESC LIMIT 30)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun canUndo() = db.rawQuery("SELECT 1 FROM category_history LIMIT 1", null).use { it.moveToFirst() }
    fun normalizeInitialNames() {
        if (store.prefs.getBoolean("categoryNamesNormalized", false)) return
        val names = mutableMapOf<String, String>()
        val duplicates = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT code,name,nameKey FROM categories WHERE code=target AND nameKey!='' ORDER BY code", null).use {
            while (it.moveToNext()) {
                val previous = names.putIfAbsent(it.getString(2), it.getString(0))
                if (previous != null) duplicates.add(it.getString(0) to it.getString(1))
            }
        }
        duplicates.forEach { (code, name) -> rename(code, name) }
        store.prefs.edit().putBoolean("categoryNamesNormalized", true).apply()
    }
    private fun snapshot(): String = db.rawQuery("SELECT code,name,nameKey,target FROM categories", null).use { c ->
        val rows = JSONArray()
        while (c.moveToNext()) rows.put(JSONObject().put("code", c.getString(0)).put("name", c.getString(1))
            .put("key", c.getString(2)).put("target", c.getString(3)))
        rows.toString()
    }
    @Synchronized fun rename(code: String, value: String): String {
        val name = value.trim().take(100)
        val key = nameKey(name)
        if (key == nameKey("其他")) {
            merge(listOf(code), "other")
            return "other"
        }
        db.beginTransaction()
        try {
            val target = db.rawQuery("SELECT target FROM categories WHERE code=?", arrayOf(code)).use {
                require(it.moveToFirst()) { "分类不存在" }; it.getString(0)
            }
            db.execSQL("INSERT INTO category_history(snapshot) VALUES(?)", arrayOf(snapshot()))
            val existing = if (key.isEmpty()) null else db.rawQuery(
                "SELECT code FROM categories WHERE code=target AND nameKey=? AND code!=? LIMIT 1",
                arrayOf(key, target)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != null) {
                db.execSQL("UPDATE categories SET target=? WHERE target=?", arrayOf(existing, target))
            } else db.execSQL("UPDATE categories SET name=?,nameKey=? WHERE code=?", arrayOf(name, key, target))
            db.execSQL("DELETE FROM category_history WHERE id NOT IN (SELECT id FROM category_history ORDER BY id DESC LIMIT 30)")
            db.setTransactionSuccessful()
            return existing ?: target
        } finally { db.endTransaction() }
    }
    @Synchronized fun undo() {
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id,snapshot FROM category_history ORDER BY id DESC LIMIT 1", null).use { c ->
                require(c.moveToFirst()) { "没有可撤销的命名" }
                val rows = JSONArray(c.getString(1))
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    db.execSQL("UPDATE categories SET name=?,nameKey=?,target=? WHERE code=?",
                        arrayOf(r.getString("name"), r.getString("key"), r.getString("target"), r.getString("code")))
                }
                db.execSQL("DELETE FROM category_history WHERE id=?", arrayOf(c.getLong(0)))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun correct(ids: List<String>, name: String, other: Boolean) {
        db.beginTransaction()
        try {
            val code = if (other) "" else {
                val key = nameKey(name)
                val existing = if (key.isEmpty()) null else db.rawQuery(
                    "SELECT code FROM categories WHERE code=target AND nameKey=? LIMIT 1", arrayOf(key)
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                existing ?: ("local-" + UUID.randomUUID()).also { ensure(it, name.trim().take(100)) }
            }
            for (id in ids) {
                val row = store.get(id)
                require((row["trashed"] as Number).toInt() != 2)
                store.update(id, "categoryCode" to code, "classified" to 1, "manualCategory" to 1,
                    "game" to name.trim().take(100), "eligible" to if (other) 0 else 1)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    companion object {
        // User-reviewed names; existing device names are never overwritten.
        fun defaultName(code: String, fallback: String) = when (code) {
            RhythmScreenRules.BANDORI_GACHA, "bandori" -> "BanG Dream!"
            "milthm" -> "Milthm"
            "cytus2" -> "Cytus"
            "worlddaistar" -> "其他偶像音游"
            else -> fallback
        }
        const val VISIBLE = "categoryCode!='' AND categoryCode NOT IN (SELECT code FROM categories WHERE target='other') AND (status='compressed' OR manualCategory=1)"
        fun nameKey(name: String) = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }
}
