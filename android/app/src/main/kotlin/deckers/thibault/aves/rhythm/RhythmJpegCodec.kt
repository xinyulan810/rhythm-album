package deckers.thibault.aves.rhythm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Full-resolution 4:4:4 JPEG with per-image quality checks and atomic publication. */
object RhythmJpegCodec {
    fun compress(store: RhythmStore, row: Map<String, Any>, cancelled: () -> Boolean, progress: (Int, Int) -> Unit) {
        val id = row["id"] as String
        val dir = store.directory(id)
        val original = File(dir, "original")
        require(!File(dir, "manifest.json").exists()) { "Image already published" }
        val width = (row["width"] as Number).toInt()
        val height = (row["height"] as Number).toInt()
        require(width in 1..4096 && height in 1..8192 && width.toLong() * height <= 12_000_000)
        val orientation = ExifInterface(original).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        require(orientation == 0 || orientation == 1) { "带旋转方向的图片暂只原样收纳" }
        val pixels = requireNotNull(BitmapFactory.decodeFile(original.path)) { "源图片解码失败" }
        val temp = File(dir, "jpeg-pending.tmp")
        try {
            var chosen = 0
            for (quality in listOf(90, 95, 98)) {
                if (cancelled() || RhythmCodec.viewing) throw InterruptedException("Paused before JPEG encoding")
                temp.delete()
                RhythmDiagnostics.measure(store, id, "jpeg_encode") { RhythmNative.jpegEncode(pixels, temp.path, quality) }
                val decoded = requireNotNull(BitmapFactory.decodeFile(temp.path)) { "JPEG 校验失败" }
                val metric = try {
                    check(decoded.width == width && decoded.height == height)
                    RhythmNative.bitmapMetrics(pixels, decoded)
                } finally { decoded.recycle() }
                RhythmDiagnostics.event(store, "jpeg_quality", "id" to id, "quality" to quality,
                    "psnr" to metric[0], "edgeMae" to metric[1], "bytes" to temp.length())
                // Conservative absolute guard in addition to same-input AV1 comparison in release tests.
                if (metric[0] >= 45 && metric[1] <= 3.5) { chosen = quality; break }
            }
            progress(1, 2)
            if (chosen == 0 || temp.length() >= original.length()) {
                store.update(id, "status" to "ready", "storedBytes" to original.length(),
                    "error" to if (chosen == 0) "未达到画质门槛，已原样保留" else "压缩无收益，已原样保留")
                return
            }
            if (cancelled()) throw InterruptedException("Paused before publish")
            FileOutputStream(temp, true).use { it.fd.sync() }
            val manifest = JSONObject().put("version", 2).put("codec", "jpeg-444")
                .put("quality", chosen).put("width", width).put("height", height)
                .put("file", "image.jpg").put("sha256", RhythmStore.hash(temp)).put("stripes", JSONArray())
            val target = File(dir, "image.jpg")
            check(temp.renameTo(target))
            RhythmStore.syncWrite(File(dir, "manifest.tmp"), manifest.toString())
            check(File(dir, "manifest.tmp").renameTo(File(dir, "manifest.json")))
            store.update(id, "status" to "compressed", "anchor" to 0, "error" to "",
                "storedBytes" to (target.length() + File(dir, "manifest.json").length() + File(dir, "thumbnail.jpg").length()))
            progress(2, 2)
        } finally { pixels.recycle(); temp.delete() }
    }

    fun file(store: RhythmStore, id: String, manifest: JSONObject): File {
        require(manifest.getInt("version") == 2 && manifest.getString("codec") == "jpeg-444")
        require(manifest.getString("file") == "image.jpg")
        return File(store.directory(id), "image.jpg").also {
            check(it.length() in 1..128L * 1024 * 1024 && RhythmStore.hash(it) == manifest.getString("sha256")) { "JPEG integrity check failed" }
        }
    }
    fun render(store: RhythmStore, id: String, output: File, maxDimension: Int): File {
        val manifest = RhythmStore.manifest(File(store.directory(id), "manifest.json"))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file(store, id, manifest).path))
        val scale = if (maxDimension == 0) 1f else minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        val image = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
            maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
        val partial = File(output.parentFile, "${output.name}.tmp")
        try {
            if (output.extension == "bmp") RhythmNative.writeBmp(image, partial.path)
            else partial.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(partial.renameTo(output))
            return output
        } finally { if (image !== bitmap) image.recycle(); bitmap.recycle(); partial.delete() }
    }
}
