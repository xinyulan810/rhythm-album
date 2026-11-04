package deckers.thibault.aves.rhythm

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.withLock

/** Explicit, system-confirmed deletion of exactly verified MediaStore screenshots.
 * Neither the worker nor this class silently deletes a system source. */
object RhythmManaged {
    const val WHERE = "status='compressed' AND trashed=0 AND ocrVersion>0 AND sourceRemoved=0 " +
        "AND sourceDeleteError='' AND LOWER(name) LIKE '%screenshot%' " +
        "AND source LIKE 'content://media/%/images/media/%'"

    fun supported() = Build.VERSION.SDK_INT >= 30
    fun sourceUri(source: String): Uri? = source.takeIf {
        it.matches(Regex("content://media/(external|external_primary|[a-zA-Z0-9_-]+)/images/media/[0-9]+"))
    }?.let(Uri::parse)
    fun count(store: RhythmStore, errors: Boolean = false): Long =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM images WHERE " +
            if (errors) "sourceDeleteError!='' AND sourceRemoved=0 AND trashed=0" else WHERE, null).use {
            it.moveToFirst(); it.getLong(0)
        }

    /** Always decode the published archive, never use the retained original/cache as proof. */
    fun verifyArchive(store: RhythmStore, id: String) {
        val row = store.get(id)
        check(row["status"] == "compressed" && (row["trashed"] as Number).toInt() == 0)
        val proof = File(store.directory(id), "managed-verification.png")
        try {
            RhythmCodec.render(store, id, proof, 0)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(proof.path)) { "压缩副本无法解码" }
            try {
                check(bitmap.width == (row["width"] as Number).toInt() &&
                    bitmap.height == (row["height"] as Number).toInt()) { "压缩副本尺寸不符" }
            } finally { bitmap.recycle() }
        } finally { proof.delete() }
    }

    fun prepare(
        store: RhythmStore,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Pair<String, Uri>> {
        check(supported() && store.prefs.getBoolean("managedScreenshots", false)) { "请先开启代管截图" }
        check(RhythmAutoImport.permission(store.context) == "full") { "删除前请授予全部照片访问权限" }
        val resolver = store.context.contentResolver
        val candidates = store.operations.withLock { store.query(WHERE, limit = 1000) }
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
                    val digest = MessageDigest.getInstance("SHA-256")
                    requireNotNull(resolver.openInputStream(uri)).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(hash == RhythmStore.hash(original)) { "系统图片已改变，保留原图" }
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
        var removed = 0
        var failures = 0
        progress(0, batch.size, 0)
        for ((index, entry) in batch.withIndex()) {
            val (id, uri) = entry
            try {
                store.operations.withLock {
                    val absent = store.context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                        ?.use { !it.moveToFirst() } ?: false
                    check(absent) { "系统未确认原图消失，保留恢复副本" }
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
