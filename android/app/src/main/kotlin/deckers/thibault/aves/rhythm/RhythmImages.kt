package deckers.thibault.aves.rhythm

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.concurrent.withLock

/** Gallery IO boundary: a future pixel/region backend can replace this implementation. */
object RhythmImages {
    fun full(store: RhythmStore, id: String): File {
        val row = store.get(id)
        require((row["trashed"] as Number).toInt() != 2) { "照片已永久删除" }
        return if (row["status"] == "compressed") RhythmCodec.cachedRender(store, id, full = true)
        else File(store.directory(id), "original").also { require(it.isFile) { "照片尚未收纳完成" } }
    }
    fun mime(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outMimeType ?: "image/png"
    }
    fun shareCopy(store: RhythmStore, id: String): File = store.operations.withLock {
        val source = full(store, id)
        val ext = when (mime(source)) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        val dir = File(store.context.cacheDir, "rhythm-share").apply { mkdirs() }
        // Retain recently shared files long enough for receivers to finish reading.
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        File(dir, "Rhythm_${id}_${System.currentTimeMillis()}.$ext").also { source.copyTo(it) }
    }
    fun export(store: RhythmStore, id: String): String = store.operations.withLock {
        require(Build.VERSION.SDK_INT >= 29) { "此系统请用“导出到文件夹”选择保存位置" }
        val source = full(store, id)
        val mime = mime(source)
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Rhythm_${System.currentTimeMillis()}_${id.take(8)}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RhythmAlbum")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = store.context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri).use { out -> requireNotNull(out); source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // only our unpublished export, never a source photo
            throw e
        }
    }
    /** Crop in normalized source coordinates, then rotate. Save as a new gallery photo. */
    fun editCopy(store: RhythmStore, id: String, crop: List<Double>, turns: Int): Map<String, Any> = store.operations.withLock {
        require(crop.size == 4 && crop.all { it.isFinite() && it in 0.0..1.0 })
        require(crop[2] > crop[0] && crop[3] > crop[1])
        val row = store.get(id)
        val source = full(store, id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth.toLong() * bounds.outHeight in 1..20_000_000) { "编辑上限为 2000 万像素" }
        val original = requireNotNull(BitmapFactory.decodeFile(source.path)) { "图片解码失败" }
        var cropped: Bitmap? = null
        var rotated: Bitmap? = null
        val temp = File(store.context.cacheDir, "rhythm-edit-${UUID.randomUUID()}.png")
        try {
            val left = (crop[0] * original.width).toInt().coerceIn(0, original.width - 1)
            val top = (crop[1] * original.height).toInt().coerceIn(0, original.height - 1)
            val right = (crop[2] * original.width).toInt().coerceIn(left + 1, original.width)
            val bottom = (crop[3] * original.height).toInt().coerceIn(top + 1, original.height)
            cropped = Bitmap.createBitmap(original, left, top, right - left, bottom - top)
            rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate((turns.mod(4) * 90).toFloat()) }, true)
            FileOutputStream(temp).use { check(rotated.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
            val sourceKey = "edit:${UUID.randomUUID()}"
            check(store.enqueue(sourceKey, "Edited_${System.currentTimeMillis()}.png", row["game"] as String, false,
                (row["taken"] as Number).toLong()))
            val added = store.query("source=?", arrayOf(sourceKey), 1).first()
            val newId = added["id"] as String
            val dir = store.directory(newId).apply { mkdirs() }
            check(temp.renameTo(File(dir, "original")))
            val thumb = Bitmap.createScaledBitmap(rotated,
                maxOf(1, rotated.width * 320 / maxOf(rotated.width, rotated.height)),
                maxOf(1, rotated.height * 320 / maxOf(rotated.width, rotated.height)), true)
            try { FileOutputStream(File(dir, "thumbnail.jpg")).use { check(thumb.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
            finally { if (thumb !== rotated) thumb.recycle() }
            store.update(newId, "status" to if (rotated.width <= 4096 && rotated.height <= 8192 &&
                rotated.width.toLong() * rotated.height <= 12_000_000) "pending" else "ready", "width" to rotated.width, "height" to rotated.height,
                "originalBytes" to File(dir, "original").length(), "storedBytes" to File(dir, "original").length(),
                "song" to row["song"] as String, "album" to row["album"] as String, "tags" to row["tags"] as String)
            store.freshIds.add(newId)
            RhythmWorker.schedule(store.context)
            store.get(newId)
        } finally {
            if (rotated !== cropped && rotated !== original) rotated?.recycle()
            if (cropped !== original) cropped?.recycle()
            original.recycle()
            temp.delete()
        }
    }
}
