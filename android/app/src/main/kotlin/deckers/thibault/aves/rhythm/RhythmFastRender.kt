package deckers.thibault.aves.rhythm

import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Exact v1 reconstruction, bounded stripe parallelism and no planar scratch files. */
object RhythmFastRender {
    fun render(store: RhythmStore, id: String, output: File, maxDimension: Int): File {
        val dir = store.directory(id)
        val manifest = RhythmStore.manifest(File(dir, "manifest.json"))
        val width = manifest.getInt("width")
        val height = manifest.getInt("height")
        require(width in 1..4096 && height in 1..8192 && width.toLong() * height <= 12_000_000)
        val stripes = manifest.getJSONArray("stripes")
        require(stripes.length() == (height + 511) / 512)
        val jobs = if (RhythmPolicy.budget(store.context).getValue("threadCeiling") == 1) 1 else minOf(4, stripes.length())
        val pool = Executors.newFixedThreadPool(jobs)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val partial = File(output.parentFile, "${output.name}.tmp")
        try {
            // At most `jobs` decoded stripes resident, even for tall images.
            for (start in 0 until stripes.length() step jobs) {
                val tasks = (start until minOf(stripes.length(), start + jobs)).map { index -> Callable {
                    val info = stripes.getJSONObject(index)
                    val y = index * 512
                    val h = minOf(512, height - y)
                    require(info.getInt("y") == y && info.getInt("height") == h)
                    val frame = checked(dir, info.getString("file"), info.getString("sha256"))
                    var reference: File? = null
                    var bootstrap: File? = null
                    if (info.has("reference")) {
                        val anchor = store.directory(info.getString("reference"))
                        val metadata = RhythmStore.manifest(File(anchor, "manifest.json"))
                        require(metadata.getInt("width") == width && metadata.getInt("height") == height)
                        val stripe = metadata.getJSONArray("stripes").getJSONObject(index)
                        require(!stripe.has("reference") && stripe.getString("sha256") == info.getString("referenceHash"))
                        reference = checked(anchor, stripe.getString("file"), info.getString("referenceHash"))
                        bootstrap = checked(dir, info.getString("bootstrap"), info.getString("bootstrapHash"))
                    }
                    val began = android.os.SystemClock.elapsedRealtimeNanos()
                    val fast = RhythmNative.decodeInto(bitmap, y, h, frame.path, bootstrap?.path, reference?.path, info.getString("pixelSha256"))
                    if (RhythmCodec.benchmarking) RhythmDiagnostics.event(store, "fast_stripe",
                        "id" to id, "index" to index, "dav1d" to fast,
                        "ms" to (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1e6)
                } }
                pool.invokeAll(tasks).forEach { it.get() }
            }
            val scale = if (maxDimension == 0) 1f else minOf(1f, maxDimension.toFloat() / maxOf(width, height))
            val image = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
                maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), true)
            try {
                if (output.extension == "bmp") RhythmNative.writeBmp(image, partial.path)
                else RhythmNative.writePng(image, partial.path)
                check(partial.renameTo(output)) { "Cache publication failed" }
            } finally { if (image !== bitmap) image.recycle() }
            return output
        } finally {
            pool.shutdown()
            bitmap.recycle()
            partial.delete()
        }
    }
    private fun checked(dir: File, name: String, hash: String): File {
        require(name.matches(Regex("(stripe|bootstrap)-[0-9]+\\.obu")))
        return File(File(dir, "objects"), name).also {
            check(it.length() in 1..16 * 1024 * 1024 && RhythmStore.hash(it) == hash) { "Archive integrity check failed" }
        }
    }
}
