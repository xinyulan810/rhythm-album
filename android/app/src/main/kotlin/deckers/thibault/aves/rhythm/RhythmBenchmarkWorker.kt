package deckers.thibault.aves.rhythm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reproducible release-device tests, isolated from the real library and system originals. */
class RhythmBenchmarkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val library = RhythmStore.get(applicationContext)
        val output = File(applicationContext.getExternalFilesDir(null), "diagnostics").apply { mkdirs() }
        fun export() {
            File(output, "library-performance.jsonl").writeText(RhythmDiagnostics.read(library))
            val categories = JSONArray(library.categories.albums())
            val errors = JSONArray()
            library.readableDatabase.rawQuery("SELECT error,COUNT(*) FROM images WHERE status='error' GROUP BY error", null).use {
                while (it.moveToNext()) errors.put(JSONObject().put("error", it.getString(0)).put("count", it.getLong(1)))
            }
            File(output, "library-state.json").writeText(JSONObject(library.stats()).put("categories", categories).put("errorDetails", errors).toString(2))
        }
        if (inputData.getString("mode") in listOf("reviewCheck", "reviewApply")) {
            RhythmReviewLabels.run(library, output, inputData.getString("mode") == "reviewApply")
            export()
            return@withContext Result.success()
        }
        if (inputData.getString("mode") == "reviewOther") {
            RhythmReviewExport.run(library, output) { isStopped }
            export()
            return@withContext Result.success()
        }
        if (inputData.getString("mode") == "cleanup") {
            // Only this tool's isolated fixtures; never the user's library, sources or diagnostics.
            val pattern = Regex("rhythm-benchmark(-[0-9]+)?")
            for (parent in listOf(applicationContext.filesDir, applicationContext.cacheDir)) {
                parent.listFiles()?.filter { pattern.matches(it.name) && it.isDirectory }?.forEach {
                    check(it.canonicalFile.parentFile == parent.canonicalFile)
                    check(it.deleteRecursively())
                }
            }
            applicationContext.databaseList().filter { Regex("rhythm-benchmark(-[0-9]+)?\\.db").matches(it) }
                .forEach { applicationContext.deleteDatabase(it) }
            val input = File(applicationContext.getExternalFilesDir(null), "benchmark-input")
            if (input.exists()) check(input.deleteRecursively())
            listOf("display-probe.png", "display-probe.bmp", "mismatch.png", "mismatch.bmp").forEach {
                File(output, it).delete()
            }
            File(output, "cleanup.json").writeText("""{"complete":true,"libraryUntouched":true}""")
            export()
            return@withContext Result.success()
        }
        if (inputData.getString("mode") != "benchmark") {
            export()
            return@withContext Result.success()
        }
        val runId = System.currentTimeMillis().toString()
        val store = RhythmStore(applicationContext, true, runId)
        val classifier = RhythmClassifier(store)
        val ocr = RhythmOcr(store)
        val results = JSONArray()
        try {
            RhythmCodec.benchmarking = true
            File(output, "benchmark-status.json").writeText(JSONObject().put("complete", false).put("running", true).put("run", runId).toString())
            library.operations.lock()
            library.operations.unlock()
            val input = File(applicationContext.getExternalFilesDir(null), "benchmark-input")
            val spec = JSONObject(File(input, "cases.json").readText())
            RhythmCodec.benchmarkFast = spec.optBoolean("fastProduction")
            val cases = spec.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                if (isStopped) break
                val case = cases.getJSONObject(i)
                val name = case.getString("file")
                require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
                val source = File(input, name)
                require(source.length() in 1..(128L * 1024 * 1024))
                store.enqueue("benchmark:$runId:$name", name, "", false)
                val row = store.query("source=?", arrayOf("benchmark:$runId:$name"), 1).single()
                val id = row["id"] as String
                val dir = store.directory(id).apply { mkdirs() }
                val original = File(dir, "original")
                RhythmDiagnostics.measure(store, id, "bench_import") {
                    source.copyTo(original, overwrite = false)
                    check(RhythmStore.hash(source) == RhythmStore.hash(original))
                }
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(original.path, b)
                val thumb = RhythmClassifier.sampled(original, 256)
                File(dir, "thumbnail.jpg").outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                thumb.recycle()
                store.update(id, "width" to b.outWidth, "height" to b.outHeight, "originalBytes" to original.length(),
                    "storedBytes" to original.length(), "status" to "pending")
                val start = SystemClock.elapsedRealtime()
                classifier.classify(id)
                val result = JSONObject().put("case", name).put("expected", case.optString("expected"))
                    .put("code", store.get(id)["categoryCode"]).put("classifyMs", SystemClock.elapsedRealtime() - start)
                if (case.optBoolean("ocr")) {
                    val pixels = requireNotNull(BitmapFactory.decodeFile(original.path))
                    val fastPng = File(dir, "fast-export.png")
                    val legacyPng = File(dir, "legacy-export.png")
                    try {
                        val began = SystemClock.elapsedRealtimeNanos()
                        RhythmNative.writePng(pixels, fastPng.path)
                        result.put("fastPngMs", (SystemClock.elapsedRealtimeNanos() - began) / 1e6)
                        val legacyStart = SystemClock.elapsedRealtimeNanos()
                        legacyPng.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        result.put("legacyPngMs", (SystemClock.elapsedRealtimeNanos() - legacyStart) / 1e6)
                        val decoded = requireNotNull(BitmapFactory.decodeFile(fastPng.path))
                        try {
                            check(decoded.width == pixels.width && decoded.height == pixels.height)
                            check(RhythmNative.bitmapMetrics(pixels, decoded)[0] >= 160)
                            result.put("fastPngPixelMatch", true).put("fastPngBytes", fastPng.length())
                                .put("legacyPngBytes", legacyPng.length())
                        } finally { decoded.recycle() }
                    } finally { pixels.recycle(); fastPng.delete(); legacyPng.delete() }
                }
                if (case.optBoolean("ocr")) {
                    val began = SystemClock.elapsedRealtime()
                    ocr.recognize(id)
                    val indexed = store.get(id)
                    result.put("ocrMs", SystemClock.elapsedRealtime() - began)
                        .put("ocrText", indexed["ocrText"]).put("achievement", indexed["achievement"])
                    if (case.has("expectedAchievement")) result
                        .put("expectedAchievement", case.getString("expectedAchievement"))
                        .put("achievementMatch", indexed["achievement"] == case.getString("expectedAchievement"))
                    val searches = case.optJSONArray("expectedSearch") ?: JSONArray()
                    val found = JSONObject()
                    for (n in 0 until searches.length()) {
                        val term = searches.getString(n)
                        found.put(term, (indexed["ocrSearch"] as String).contains(RhythmAchievement.searchKey(term)))
                    }
                    result.put("searchChecks", found)
                    result.put("finalCode", indexed["categoryCode"])
                    if (case.has("expected")) result.put("categoryMatch",
                        (indexed["categoryCode"] as String).ifEmpty { "other" } == case.getString("expected"))
                }
                if (case.optBoolean("compress")) {
                    val cpuBefore = android.os.Process.getElapsedCpuTime()
                    val t = SystemClock.elapsedRealtime()
                    RhythmCodec.compress(store, store.get(id), { isStopped }) { _, _ -> }
                    result.put("compressMs", SystemClock.elapsedRealtime() - t)
                        .put("compressCpuMs", android.os.Process.getElapsedCpuTime() - cpuBefore)
                        .put("status", store.get(id)["status"]).put("storedBytes", store.get(id)["storedBytes"])
                    if (store.get(id)["status"] == "compressed") {
                        val rendered = File(dir, "bench.png")
                        RhythmDiagnostics.measure(store, id, "bench_cold_png") { RhythmCodec.render(store, id, rendered, 0) }
                        val bmp = File(dir, "bench.bmp")
                        RhythmDiagnostics.measure(store, id, "bench_cold_bmp") { RhythmCodec.render(store, id, bmp, 0) }
                        val a = BitmapFactory.decodeFile(rendered.path)
                        val bmap = BitmapFactory.decodeFile(bmp.path)
                        check(a != null && bmap != null && a.width == bmap.width && a.height == bmap.height)
                        val pa = IntArray(a.width); val pb = IntArray(a.width)
                        var mismatch = 0
                        for (y in 0 until a.height) {
                            a.getPixels(pa, 0, a.width, 0, y, a.width, 1)
                            bmap.getPixels(pb, 0, a.width, 0, y, a.width, 1)
                            for (x in pa.indices) if (pa[x] != pb[x]) mismatch++
                        }
                        if (mismatch != 0) {
                            rendered.copyTo(File(output, "mismatch.png"), true)
                            bmp.copyTo(File(output, "mismatch.bmp"), true)
                        }
                        check(mismatch == 0) { "Display transfer mismatch: $mismatch pixels; ${a.config}/${bmap.config}" }
                        val originalPixels = requireNotNull(BitmapFactory.decodeFile(original.path))
                        val baselineMetrics = RhythmNative.bitmapMetrics(originalPixels, a)
                        result.put("baselinePsnr", baselineMetrics[0]).put("baselineEdgeMae", baselineMetrics[1])
                        originalPixels.recycle()
                        if (!RhythmCodec.benchmarkFast) {
                        val fastFile = File(dir, "fast.bmp")
                        val fastStart = SystemClock.elapsedRealtimeNanos()
                        RhythmFastRender.render(store, id, fastFile, 0)
                        result.put("fastAv1RenderMs", (SystemClock.elapsedRealtimeNanos() - fastStart) / 1e6)
                        val fastBitmap = requireNotNull(BitmapFactory.decodeFile(fastFile.path))
                        val fastMetrics = RhythmNative.bitmapMetrics(a, fastBitmap)
                        check(fastMetrics[0] >= 160) { "Fast AV1 pixels differ from reference decoder" }
                        result.put("fastAv1PixelMatch", true)
                        fastBitmap.recycle()
                        }
                        if (spec.optBoolean("compareCodecs")) {
                            result.put("candidates", compareCodecs(store, id, original, case, runId))
                        }
                        if (!File(output, "display-probe.bmp").exists()) {
                            rendered.copyTo(File(output, "display-probe.png"), true)
                            bmp.copyTo(File(output, "display-probe.bmp"), true)
                        }
                        a.recycle(); bmap.recycle()
                        result.put("bmpPixelMatch", true)
                        rendered.delete(); bmp.delete()
                        RhythmDiagnostics.measure(store, id, "bench_cache_cold") { RhythmCodec.cachedRender(store, id, true, true) }
                        RhythmDiagnostics.measure(store, id, "bench_cache_warm") { RhythmCodec.cachedRender(store, id, true, true) }
                    }
                    RhythmPolicy.completed(store, SystemClock.elapsedRealtime() - t, b.outWidth.toLong() * b.outHeight)
                }
                result.put("pssKb", Debug.getPss()).put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    result.put("thermalStatus", (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
                results.put(result)
                File(output, "benchmark-results.json").writeText(results.toString(2))
                File(output, "benchmark-performance.jsonl").writeText(RhythmDiagnostics.read(store))
                RhythmDiagnostics.event(store, "bench_case_done", "case" to name, "index" to i, "count" to cases.length())
            }
            File(output, "benchmark-status.json").writeText(JSONObject().put("complete", !isStopped).put("count", results.length()).toString())
            export()
            Result.success()
        } catch (e: Exception) {
            File(output, "benchmark-status.json").writeText(JSONObject().put("complete", false).put("error", e.toString()).toString())
            RhythmDiagnostics.event(store, "benchmark_error", "error" to e.stackTraceToString())
            Result.failure()
        } finally {
            classifier.close()
            ocr.close()
            File(output, "benchmark-performance.jsonl").writeText(RhythmDiagnostics.read(store))
            store.close()
            RhythmCodec.benchmarking = false
            RhythmCodec.benchmarkFast = false
            if (!library.prefs.getBoolean("paused", false)) RhythmWorker.schedule(applicationContext)
        }
    }

    /** Isolated same-input comparison. No production-library records are changed. */
    private fun compareCodecs(store: RhythmStore, id: String, original: File, case: JSONObject, run: String): JSONArray {
        val results = JSONArray()
        for (quality in listOf(90, 95, 98)) {
            val file = File(store.directory(id), "candidate-$quality.jpg")
            val cpu = android.os.Process.getElapsedCpuTime()
            val start = SystemClock.elapsedRealtimeNanos()
            val pixels = requireNotNull(BitmapFactory.decodeFile(original.path))
            RhythmNative.jpegEncode(pixels, file.path, quality)
            java.io.FileOutputStream(file, true).use { it.fd.sync() }
            val verifyStart = SystemClock.elapsedRealtimeNanos()
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
            check(decoded.width == pixels.width && decoded.height == pixels.height)
            val verifiedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val decodeMs = (SystemClock.elapsedRealtimeNanos() - verifyStart) / 1e6
            val metric = RhythmNative.bitmapMetrics(pixels, decoded)
            results.put(JSONObject().put("codec", "jpeg444-q$quality").put("encodeVerifyMs", verifiedMs)
                .put("decodeMs", decodeMs).put("bytes", file.length())
                .put("psnr", metric[0]).put("edgeMae", metric[1])
                .put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu).put("pssKb", Debug.getPss()))
            decoded.recycle(); pixels.recycle()
        }
        val webp = File(store.directory(id), "candidate.webp")
        val start = SystemClock.elapsedRealtimeNanos()
        val pixels = requireNotNull(BitmapFactory.decodeFile(original.path))
        webp.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.WEBP, 95, it)) }
        val decodeStart = SystemClock.elapsedRealtimeNanos()
        val decoded = requireNotNull(BitmapFactory.decodeFile(webp.path))
        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1e6
        val metric = RhythmNative.bitmapMetrics(pixels, decoded)
        results.put(JSONObject().put("codec", "webp-q95").put("encodeVerifyMs", elapsed)
            .put("decodeMs", decodeMs).put("bytes", webp.length()).put("psnr", metric[0]).put("edgeMae", metric[1]))
        decoded.recycle(); pixels.recycle()
        for (speed in listOf(10, 12)) {
            val variant = RhythmStore(applicationContext, true, "$run-speed$speed")
            try {
                val code = store.get(id)["categoryCode"] as String
                variant.categories.ensure(code.ifEmpty { "other" })
                variant.enqueue("compare:${case.getString("file")}", case.getString("file"), "", false)
                val row = variant.query("source=?", arrayOf("compare:${case.getString("file")}"), 1).single()
                val variantId = row["id"] as String
                val dir = variant.directory(variantId).apply { mkdirs() }
                original.copyTo(File(dir, "original"))
                File(store.directory(id), "thumbnail.jpg").copyTo(File(dir, "thumbnail.jpg"))
                val source = store.get(id)
                variant.update(variantId, "width" to source.getValue("width"), "height" to source.getValue("height"),
                    "categoryCode" to code, "eligible" to source.getValue("eligible"), "status" to "pending",
                    "originalBytes" to original.length())
                RhythmCodec.benchmarkSpeed = speed
                val encoding = SystemClock.elapsedRealtimeNanos()
                RhythmCodec.compress(variant, variant.get(variantId), { isStopped }) { _, _ -> }
                val encodeMs = (SystemClock.elapsedRealtimeNanos() - encoding) / 1e6
                val rendering = SystemClock.elapsedRealtimeNanos()
                val bmp = RhythmCodec.render(variant, variantId, File(dir, "candidate.bmp"), 0)
                val renderMs = (SystemClock.elapsedRealtimeNanos() - rendering) / 1e6
                val a = requireNotNull(BitmapFactory.decodeFile(original.path))
                val b = requireNotNull(BitmapFactory.decodeFile(bmp.path))
                val m = RhythmNative.bitmapMetrics(a, b)
                results.put(JSONObject().put("codec", "av1-speed$speed").put("encodeVerifyMs", encodeMs)
                    .put("decodeMs", renderMs).put("bytes", variant.get(variantId)["storedBytes"])
                    .put("psnr", m[0]).put("edgeMae", m[1]))
                a.recycle(); b.recycle()
            } finally { RhythmCodec.benchmarkSpeed = 8; variant.close() }
        }
        return results
    }
}
