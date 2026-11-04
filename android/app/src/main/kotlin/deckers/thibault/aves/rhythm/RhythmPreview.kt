package deckers.thibault.aves.rhythm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

/** Lossless, disposable display derivative built from independently verified pixels. */
class RhythmPreview(width: Int, height: Int) : AutoCloseable {
    private val scale = minOf(1f, 2048f / maxOf(width, height))
    private val bitmap = Bitmap.createBitmap(maxOf(1, (width * scale).toInt()),
        maxOf(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun stripe(width: Int, y: Int, height: Int, raw: File) {
        val stripe = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            RhythmNative.readStripe(stripe, raw.path)
            canvas.drawBitmap(stripe, null, RectF(0f, y * scale, width * scale, (y + height) * scale), paint)
        } finally { stripe.recycle() }
    }

    fun save(output: File) {
        val partial = File(output.parentFile, "${output.name}.tmp")
        try {
            FileOutputStream(partial).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(partial.renameTo(output)) { "Preview publication failed" }
        } finally { partial.delete() }
    }

    override fun close() = bitmap.recycle()

    companion object {
        // Called under the codec lock. Published v1 image IDs are immutable.
        fun file(store: RhythmStore, id: String): File {
            store.directory(id) // validate ID before constructing a cache path
            return File(File(store.cacheRoot, "rhythm-preview-v2").apply { mkdirs() }, "$id.png")
        }
        fun trim(store: RhythmStore, keep: File, limit: Long = 48L * 1024 * 1024) {
            val files = keep.parentFile?.listFiles()?.filter { it.extension in setOf("png", "bmp") }?.sortedByDescending { it.lastModified() }.orEmpty()
            var bytes = keep.length()
            for (file in files) {
                if (file == keep) continue
                bytes += file.length()
                if (bytes > limit) file.delete()
            }
            RhythmDiagnostics.event(store, "preview_cache", "bytesLimit" to limit)
        }
    }
}
