package deckers.thibault.aves.rhythm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Experimental AV1 stripes; immutable references, no chains, bounded candidate pool. */
object RhythmCodec {
    val lock = ReentrantLock(true)
    @Volatile var viewing = false
    @Volatile var benchmarking = false
    @Volatile var benchmarkSpeed = 8
    @Volatile var benchmarkFast = false
    private fun run(vararg args: Any) {
        check(RhythmNative.run(args.map { it.toString() }.toTypedArray()) == 0) { "Native codec failed" }
    }
    private fun encode(width: Int, height: Int, input: File, reference: File?, prefix: File, threads: Int) =
        run("encode", width, height, 24, if (benchmarking) benchmarkSpeed else 8, input.path, reference?.path ?: "-", prefix.path, threads)
    private fun decode(width: Int, height: Int, frame: File, bootstrap: File?, reference: File?, output: File) =
        run("decode", width, height, bootstrap?.path ?: "-", reference?.path ?: "-", frame.path, output.path, "android")

    fun compress(store: RhythmStore, row: Map<String, Any>, cancelled: () -> Boolean, progress: (Int, Int) -> Unit) {
        if ((!benchmarking && store.prefs.getString("encodingMode", "fast") == "fast") || benchmarkFast) {
            RhythmJpegCodec.compress(store, row, cancelled, progress)
            return
        }
        val id = row["id"] as String
        val dir = store.directory(id)
        val original = File(dir, "original")
        val width = (row["width"] as Number).toInt()
        val height = (row["height"] as Number).toInt()
        RhythmDiagnostics.event(store, "compress_input", "id" to id, "width" to width,
            "height" to height, "originalBytes" to original.length(), "q" to 24, "speed" to 8,
            "threads" to RhythmPolicy.budget(store.context).getValue("threads"))
        require(width in 1..4096 && height in 1..8192 && width.toLong() * height <= 12_000_000) {
            "初版压缩上限：宽4096、1200万像素；已保留原样副本"
        }
        require(original.exists()) { "Missing managed original" }
        // JPEG/PNG only, ordinary orientation. Other media remain untouched.
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, options)
        require(options.outMimeType in setOf("image/jpeg", "image/png")) { "此格式暂只原样收纳" }
        val orientation = androidx.exifinterface.media.ExifInterface(original).getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
        require(orientation == 1 || orientation == 0) { "带旋转方向的图片暂只原样收纳" }
        val candidates = if ((row["eligible"] as Number).toInt() == 1 && (row["categoryCode"] as String).isNotBlank())
            store.query("anchor=1 AND trashed=0 AND categoryCode=? AND width=? AND height=? AND id!=?",
                arrayOf(row["categoryCode"] as String, "$width", "$height", id), 8) else emptyList()
        val anchor = RhythmDiagnostics.measure(store, id, "reference_select") { closest(store, dir, candidates) }
        val anchorDir = anchor?.let { store.directory(it["id"] as String) }
        val anchorManifest = anchorDir?.let { RhythmStore.manifest(File(it, "manifest.json")) }
        // A previous interrupted attempt is scratch only, never a published object.
        val scratch = File(dir, "scratch")
        check(!File(dir, "manifest.json").exists()) { "Image already published" }
        if (scratch.exists()) scratch.deleteRecursively()
        check(scratch.mkdirs())
        val stripes = JSONArray()
        var anyDelta = false
        val decoder = BitmapRegionDecoder.newInstance(original.path, false) ?: error("Region decoder unavailable")
        try {
            for (y in 0 until height step 512) {
                if (cancelled() || viewing) throw InterruptedException("Paused between stripes")
                val h = minOf(512, height - y)
                val index = y / 512
                val threads = RhythmPolicy.budget(store.context).getValue("threads")
                lock.withLock {
                    if (cancelled() || viewing) throw InterruptedException("Paused between stripes")
                    val bitmap = RhythmDiagnostics.measure(store, id, "stripe_$index.source_decode") {
                        decoder.decodeRegion(Rect(0, y, width, y + h),
                            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                            ?: error("Stripe decode failed")
                    }
                    try {
                        val raw = File(scratch, "input.gbr")
                        RhythmDiagnostics.measure(store, id, "stripe_$index.write_pixels") { RhythmNative.writeStripe(bitmap, raw.path) }
                        val full = File(scratch, "full-$index")
                        RhythmDiagnostics.measure(store, id, "stripe_$index.encode_full") { encode(width, h, raw, null, full, threads) }
                        val fullFrame = File("$full.obu")
                        var frame = fullFrame
                        var reconstruction = File("$full.encoder.gbr")
                        var bootstrap: File? = null
                        var reference: File? = null
                        if (anchorManifest != null && anchorDir != null) {
                            val info = anchorManifest.getJSONArray("stripes").getJSONObject(index)
                            check(!info.has("reference")) { "Reference chains are forbidden" }
                            val ref = checked(anchorDir, info.getString("file"), info.getString("sha256"))
                            val delta = File(scratch, "delta-$index")
                            RhythmDiagnostics.measure(store, id, "stripe_$index.encode_delta") { encode(width, h, raw, ref, delta, threads) }
                            val deltaFrame = File("$delta.obu")
                            val deltaBoot = File("$delta.bootstrap.obu")
                            if (deltaFrame.length() + deltaBoot.length() < fullFrame.length()) {
                                val (base, metric) = RhythmDiagnostics.measure(store, id, "stripe_$index.quality") {
                                    RhythmNative.metrics(bitmap, reconstruction.path) to RhythmNative.metrics(bitmap, "$delta.encoder.gbr")
                                }
                                if (metric[0] >= base[0] - 0.25 && metric[1] <= base[1] * 1.05 + 0.1) {
                                    frame = deltaFrame; bootstrap = deltaBoot; reference = ref
                                    reconstruction = File("$delta.encoder.gbr")
                                }
                            }
                        }
                        val restored = File(scratch, "verified-$index.gbr")
                        RhythmDiagnostics.measure(store, id, "stripe_$index.verify_decode") { decode(width, h, frame, bootstrap, reference, restored) }
                        val pixelHash = RhythmDiagnostics.measure(store, id, "stripe_$index.verify_hash") {
                            RhythmStore.hash(restored).also {
                                check(it == RhythmStore.hash(reconstruction)) { "Reconstruction mismatch" }
                            }
                        }
                        val name = "stripe-$index.obu"
                        val saved = File(scratch, name)
                        check(frame.renameTo(saved))
                        val info = JSONObject().put("file", name).put("sha256", RhythmStore.hash(saved))
                            .put("pixelSha256", pixelHash).put("y", y).put("height", h)
                        if (bootstrap != null && reference != null) {
                            val bootName = "bootstrap-$index.obu"
                            check(bootstrap.renameTo(File(scratch, bootName)))
                            info.put("bootstrap", bootName).put("bootstrapHash", RhythmStore.hash(File(scratch, bootName)))
                            info.put("reference", anchor!!["id"]).put("referenceHash", RhythmStore.hash(reference))
                            anyDelta = true
                        }
                        stripes.put(info)
                        // These are exclusively generated scratch paths, not user data.
                        listOf(raw, restored, File("$full.encoder.gbr"), File("$full.obu"),
                            File(scratch, "delta-$index.obu"), File(scratch, "delta-$index.bootstrap.obu"),
                            File(scratch, "delta-$index.encoder.gbr")).forEach { it.delete() }
                    } finally { bitmap.recycle() }
                }
                progress(index + 1, (height + 511) / 512)
            }
        } finally { decoder.recycle() }
        if (cancelled()) throw InterruptedException("Paused before publish")
        val manifest = JSONObject().put("version", 1).put("width", width).put("height", height)
            .put("codec", "av1-gbr-q24-stripe512").put("stripes", stripes)
        RhythmStore.syncWrite(File(scratch, "manifest.json"), manifest.toString())
        val payload = scratch.listFiles()!!.sumOf { it.length() }
        RhythmDiagnostics.event(store, "compress_output", "id" to id, "payloadBytes" to payload, "delta" to anyDelta)
        if (payload >= original.length()) {
            scratch.deleteRecursively()
            store.update(id, "status" to "ready", "error" to "压缩无收益，已原样保留")
            return
        }
        // Publish immutable objects before the database pointer. On interruption,
        // the original stays present and this attempt is never a usable reference.
        for (file in scratch.listFiles()!!.filter { it.name != "manifest.json" }) {
            FileOutputStream(file, true).use { it.fd.sync() }
        }
        val objects = File(dir, "objects")
        if (objects.exists()) objects.deleteRecursively() // only unpublished retry data
        check(scratch.renameTo(objects)) { "Atomic object publication failed" }
        // The small manifest is the commit marker used by the library.
        RhythmStore.syncWrite(File(dir, "manifest.tmp"), manifest.toString())
        check(File(dir, "manifest.tmp").renameTo(File(dir, "manifest.json")))
        store.update(id, "status" to "compressed", "anchor" to if (anyDelta) 0 else 1,
            "storedBytes" to (payload + File(dir, "manifest.json").length() + File(dir, "thumbnail.jpg").length()), "error" to "")
        // The gallery retains the fresh source provider/pixels for this session.
        // Do not encode an unused preview PNG or invalidate its decoded image.
        // Alpha keeps the private source as a recovery copy, as well as the system
        // original. UI reports codec bytes separately and never claims net savings.
    }

    fun cachedRender(store: RhythmStore, id: String, full: Boolean = false, display: Boolean = false): File {
        val started = android.os.SystemClock.elapsedRealtime()
        return lock.withLock {
            val metadata = RhythmStore.manifest(File(store.directory(id), "manifest.json"))
            if (metadata.getInt("version") == 2) return@withLock RhythmJpegCodec.file(store, id, metadata)
            RhythmDiagnostics.event(store, "preview_lock", "id" to id,
                "ms" to android.os.SystemClock.elapsedRealtime() - started)
            val cached = if (display) {
                store.directory(id)
                File(File(store.cacheRoot, "rhythm-display-v1").apply { mkdirs() }, "$id.bmp")
            } else if (full) {
                store.directory(id)
                File(File(store.cacheRoot, "rhythm-full-v1").apply { mkdirs() }, "$id.png")
            } else RhythmPreview.file(store, id)
            val hit = cached.length() > 0
            if (!hit) RhythmDiagnostics.measure(store, id, "preview_cold_decode") { render(store, id, cached, if (full || display) 0 else 2048) }
            cached.setLastModified(System.currentTimeMillis())
            RhythmPreview.trim(store, cached, (if (display) 192L else 48L) * 1024 * 1024)
            RhythmDiagnostics.event(store, "preview_lookup", "id" to id, "hit" to hit)
            cached
        }
    }

    /** Decode the actual persisted archive, not its retained recovery original. */
    fun render(store: RhythmStore, id: String, output: File, maxDimension: Int = 2048): File = lock.withLock {
        val metadata = RhythmStore.manifest(File(store.directory(id), "manifest.json"))
        if (metadata.getInt("version") == 2) return@withLock RhythmJpegCodec.render(store, id, output, maxDimension)
        if (!benchmarking) return@withLock RhythmFastRender.render(store, id, output, maxDimension)
        val dir = store.directory(id)
        val manifest = RhythmStore.manifest(File(dir, "manifest.json"))
        val width = manifest.getInt("width")
        val height = manifest.getInt("height")
        require(width in 1..4096 && height in 1..8192 && width.toLong() * height <= 12_000_000)
        val stripes = manifest.getJSONArray("stripes")
        require(stripes.length() == (height + 511) / 512)
        val scale = if (maxDimension == 0) 1f else minOf(1f, maxDimension.toFloat() / maxOf(width, height))
        val preview = Bitmap.createBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val raw = File(output.parentFile, "decode-stripe.tmp")
        try {
            for (i in 0 until stripes.length()) {
                val info = stripes.getJSONObject(i)
                val y = i * 512
                val h = minOf(512, height - y)
                require(info.getInt("y") == y && info.getInt("height") == h)
                val frame = checked(dir, info.getString("file"), info.getString("sha256"))
                var reference: File? = null
                var bootstrap: File? = null
                if (info.has("reference")) {
                    val anchorDir = store.directory(info.getString("reference"))
                    val anchorManifest = RhythmStore.manifest(File(anchorDir, "manifest.json"))
                    require(anchorManifest.getInt("width") == width && anchorManifest.getInt("height") == height)
                    val anchorStripe = anchorManifest.getJSONArray("stripes").getJSONObject(i)
                    require(!anchorStripe.has("reference")) { "Reference chain rejected" }
                    require(anchorStripe.getString("sha256") == info.getString("referenceHash"))
                    reference = checked(anchorDir, anchorStripe.getString("file"), info.getString("referenceHash"))
                    bootstrap = checked(dir, info.getString("bootstrap"), info.getString("bootstrapHash"))
                }
                raw.delete()
                RhythmDiagnostics.measure(store, id, "render_$i.decode") { decode(width, h, frame, bootstrap, reference, raw) }
                RhythmDiagnostics.measure(store, id, "render_$i.hash") {
                    check(RhythmStore.hash(raw) == info.getString("pixelSha256")) { "Stored reconstruction mismatch" }
                }
                val stripe = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                try {
                    RhythmNative.readStripe(stripe, raw.path)
                    canvas.drawBitmap(stripe, null, RectF(0f, y * scale, width * scale, (y + h) * scale), paint)
                } finally { stripe.recycle() }
            }
            val partial = File(output.parentFile, "${output.name}.tmp")
            try {
                RhythmDiagnostics.measure(store, id, if (output.extension == "bmp") "render_bmp" else "render_png") {
                    if (output.extension == "bmp") writeBitmap(preview, partial)
                    else FileOutputStream(partial).use { stream -> check(preview.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                    check(partial.renameTo(output))
                }
            } finally { partial.delete() }
        } finally { preview.recycle(); raw.delete() }
        output
    }

    /** Lossless 32-bit BI_RGB display transfer. No PNG compression on the first-frame path. */
    private fun writeBitmap(bitmap: Bitmap, file: File) {
        val width = bitmap.width
        val height = bitmap.height
        val header = java.nio.ByteBuffer.allocate(54).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + width * height * 4)
            .putInt(0).putInt(54).putInt(40).putInt(width).putInt(height)
            .putShort(1).putShort(32).putInt(0).putInt(width * height * 4)
            .putInt(0).putInt(0).putInt(0).putInt(0)
        file.outputStream().buffered(64 * 1024).use { out ->
            out.write(header.array())
            val pixels = IntArray(width)
            val bytes = ByteArray(width * 4)
            for (y in height - 1 downTo 0) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val p = pixels[x]
                    bytes[x * 4] = p.toByte()
                    bytes[x * 4 + 1] = (p shr 8).toByte()
                    bytes[x * 4 + 2] = (p shr 16).toByte()
                    bytes[x * 4 + 3] = 0xff.toByte()
                }
                out.write(bytes)
            }
        }
    }

    fun recoverPublished(store: RhythmStore, id: String) {
        val dir = store.directory(id)
        val marker = File(dir, "manifest.json")
        val manifest = RhythmStore.manifest(marker)
        if (manifest.getInt("version") == 2) {
            val image = RhythmJpegCodec.file(store, id, manifest)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(image.path))
            try { check(bitmap.width == manifest.getInt("width") && bitmap.height == manifest.getInt("height")) }
            finally { bitmap.recycle() }
            store.update(id, "status" to "compressed", "anchor" to 0,
                "storedBytes" to (image.length() + marker.length() + File(dir, "thumbnail.jpg").length()), "error" to "")
            return
        }
        val previewDir = File(store.context.cacheDir, "rhythm-recovery").apply { mkdirs() }
        val preview = render(store, id, File(previewDir, "verified.png"))
        preview.delete()
        val stripes = manifest.getJSONArray("stripes")
        val anchor = (0 until stripes.length()).none { stripes.getJSONObject(it).has("reference") }
        val bytes = File(dir, "objects").listFiles()!!.sumOf { it.length() } + marker.length() + File(dir, "thumbnail.jpg").length()
        store.update(id, "status" to "compressed", "anchor" to if (anchor) 1 else 0, "storedBytes" to bytes, "error" to "")
    }

    private fun closest(store: RhythmStore, dir: File, candidates: List<Map<String, Any>>): Map<String, Any>? {
        fun pixels(file: File): IntArray? {
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
            val small = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
            val values = IntArray(1024)
            small.getPixels(values, 0, 32, 0, 0, 32, 32)
            if (small !== bitmap) small.recycle()
            bitmap.recycle()
            return values
        }
        val target = pixels(File(dir, "thumbnail.jpg")) ?: return null
        return candidates.mapNotNull { candidate ->
            val other = pixels(File(store.directory(candidate["id"] as String), "thumbnail.jpg")) ?: return@mapNotNull null
            var distance = 0L
            for (i in target.indices) for (shift in intArrayOf(0, 8, 16)) {
                distance += kotlin.math.abs(((target[i] shr shift) and 255) - ((other[i] shr shift) and 255))
            }
            candidate to distance
        }.minByOrNull { it.second }?.takeIf { it.second < 1024L * 3 * 45 }?.first
    }
    private fun checked(dir: File, name: String, hash: String): File {
        require(name.matches(Regex("(stripe|bootstrap)-[0-9]+\\.obu")))
        val file = File(File(dir, "objects"), name)
        check(file.length() in 1..(16 * 1024 * 1024) && RhythmStore.hash(file) == hash) { "Reference integrity check failed" }
        return file
    }
}
