package deckers.thibault.aves.rhythm

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlin.concurrent.withLock

/** Deletion candidates are completed, explicitly managed MediaStore screenshots.
 * Deletion uses a system request, with confirmation waived only when authorized. */
object RhythmManaged {
    const val WHERE = "status='compressed' AND trashed=0 AND ocrVersion>0 AND sourceRemoved=0 " +
        "AND sourceDeleteError='' AND LOWER(name) LIKE '%screenshot%' " +
        "AND source LIKE 'content://media/%/images/media/%'"

    fun supported() = Build.VERSION.SDK_INT >= 30
    fun sourceUri(source: String): Uri? = source.takeIf {
        it.matches(Regex("content://media/(external|external_primary|[a-zA-Z0-9_-]+)/images/media/[0-9]+"))
    }?.let(Uri::parse)
    internal fun sourceKey(uri: Uri): String {
        val segments = uri.pathSegments
        check(segments.size == 4 && segments[1] == "images" && segments[2] == "media")
        return "${segments[0]}/${segments[3]}"
    }

    /** Check deleted MediaStore rows with a bounded query per volume, not one IPC per image. */
    private fun existingSources(store: RhythmStore, batch: List<Pair<String, Uri>>): Set<String> {
        val existing = mutableSetOf<String>()
        for ((volume, entries) in batch.groupBy { sourceKey(it.second).substringBefore('/') }) {
            for (chunk in entries.chunked(900)) {
                val ids = chunk.map { sourceKey(it.second).substringAfter('/') }
                val selection = ids.joinToString(",", "_id IN (", ")") { "?" }
                val collection = MediaStore.Images.Media.getContentUri(volume)
                val cursor = store.context.contentResolver.query(
                    collection, arrayOf(MediaStore.Images.Media._ID), selection, ids.toTypedArray(), null
                ) ?: error("无法核实系统原图删除状态，已保留应用内副本")
                cursor.use {
                    while (it.moveToNext()) existing.add("$volume/${it.getLong(0)}")
                }
            }
        }
        return existing
    }

    fun count(store: RhythmStore, errors: Boolean = false): Long =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM images WHERE " +
            if (errors) "sourceDeleteError!='' AND sourceRemoved=0 AND trashed=0" else WHERE, null).use {
            it.moveToFirst(); it.getLong(0)
        }

    /** Check the committed archive exists without re-encoding or hashing image pixels. */
    fun verifyArchive(store: RhythmStore, id: String) {
        val row = store.get(id)
        check(row["status"] == "compressed" && (row["trashed"] as Number).toInt() == 0)
        val dir = store.directory(id)
        val manifest = RhythmStore.manifest(File(dir, "manifest.json"))
        when (manifest.getInt("version")) {
            2 -> check(manifest.getString("file") == "image.jpg" && File(dir, "image.jpg").length() > 0)
            1 -> {
                val stripes = manifest.getJSONArray("stripes")
                check(stripes.length() > 0)
                for (i in 0 until stripes.length()) {
                    val name = stripes.getJSONObject(i).getString("file")
                    check(name.matches(Regex("stripe-[0-9]+\\.obu")) && File(dir, "objects/$name").length() > 0)
                }
            }
            else -> error("未知的归档格式")
        }
    }

    fun prepare(
        store: RhythmStore,
        batchSize: Int = 1000,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Pair<String, Uri>> {
        check(supported() && store.prefs.getBoolean("managedScreenshots", false)) { "请先开启代管截图" }
        check(RhythmAutoImport.permission(store.context) == "full") { "删除前请授予全部照片访问权限" }
        require(batchSize in 1..1000)
        val candidates = store.operations.withLock { store.query(WHERE, limit = batchSize) }
        val verified = mutableListOf<Pair<String, Uri>>()
        var failures = 0
        progress(0, candidates.size, 0)
        for ((index, row) in candidates.withIndex()) {
            if (cancelled()) break
            val id = row["id"] as String
            try {
                val entry = store.operations.withLock {
                    check(store.get(id)["sourceDeleteError"] == "") { "此图片已跳过验证" }
                    val uri = requireNotNull(sourceUri(row["source"] as String)) { "此来源不支持系统批量删除" }
                    val original = File(store.directory(id), "original")
                    check(original.isFile) { "缺少原始校验副本，保留系统原图" }
                    verifyArchive(store, id)
                    id to uri
                }
                verified.add(entry)
            } catch (e: Exception) {
                failures++
                store.operations.withLock {
                    store.update(id, "sourceDeleteError" to (e.message ?: "验证失败").take(300))
                }
            }
            if ((index + 1) % 5 == 0 || index + 1 == candidates.size) {
                progress(index + 1, candidates.size, failures)
            }
        }
        return verified
    }

    /** Called only after RESULT_OK. A cancellation never removes either copy.
     * A crash or failed check retains the private original for recovery. */
    fun finish(store: RhythmStore, batch: List<Pair<String, Uri>>, progress: (Int, Int, Int) -> Unit = { _, _, _ -> }): Int {
        val existing = existingSources(store, batch)
        var removed = 0
        var failures = 0
        progress(0, batch.size, 0)
        for ((index, entry) in batch.withIndex()) {
            val (id, uri) = entry
            try {
                check(sourceKey(uri) !in existing) { "系统原图仍存在，保留应用内恢复副本" }
                store.operations.withLock {
                    verifyArchive(store, id)
                    val original = File(store.directory(id), "original")
                    check(!original.exists() || original.delete()) { "应用内原始副本清理失败" }
                    store.freshIds.remove(id)
                    store.update(id, "sourceRemoved" to 1, "sourceDeleteError" to "")
                }
                removed++
            } catch (e: Exception) {
                failures++
                store.operations.withLock {
                    store.update(id, "sourceDeleteError" to (e.message ?: "清理失败，保留恢复副本").take(300))
                }
            }
            if ((index + 1) % 5 == 0 || index + 1 == batch.size) {
                progress(index + 1, batch.size, failures)
            }
        }
        return removed
    }
}
