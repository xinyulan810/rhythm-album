package deckers.thibault.aves.rhythm

import android.content.Context
import android.app.ActivityManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.withLock

class RhythmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = RhythmStore.get(context)
    private var resourceDeferred = false
    private val classifierHolder = lazy { RhythmClassifier(store) }
    private val classifier get() = classifierHolder.value
    private val ocrHolder = lazy { RhythmOcr(store) }
    private var ocrDone = 0
    private var batchDone = 0
    private var batchTotal = 0
    private var lastNotification = 0L
    private var classificationDone = 0
    private var classificationTotal = 0
    private fun paused() = isStopped || RhythmCodec.benchmarking || store.prefs.getBoolean("paused", false)
    private fun shouldYield(): Boolean {
        if (paused()) return true
        val memory = ActivityManager.MemoryInfo()
        (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        resourceDeferred = memory.lowMemory ||
            (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
        return resourceDeferred
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
        if (paused()) return@withContext Result.success()
        store.recover()
        val manager = NotificationManagerCompat.from(applicationContext)
        RhythmDiagnostics.event(store, "worker_start", "notificationsEnabled" to manager.areNotificationsEnabled(),
            "sdk" to Build.VERSION.SDK_INT, "model" to Build.MODEL, "codecThreads" to RhythmPolicy.budget(applicationContext).getValue("threads"),
            "channelImportance" to (if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(CHANNEL)?.importance ?: -1 else -1))
        try { setForeground(notification("准备收纳", 0, 0)) }
        catch (e: Exception) {
            store.prefs.edit().putString("workerError", "后台服务启动失败：${e.javaClass.simpleName}").apply()
            RhythmDiagnostics.event(store, "foreground_error", "type" to e.javaClass.simpleName)
            return@withContext Result.retry()
        }
        store.prefs.edit().remove("workerError").apply()
        batchTotal = (store.stats()["pending"] as Number).toInt()
        classificationTotal = (store.stats()["classifying"] as Number).toInt()
        while (!paused() && !resourceDeferred) {
            val row = store.next() ?: break
            if (RhythmCodec.viewing && row["status"] != "queued") break
            val id = row["id"] as String
            try {
                store.operations.withLock {
                if ((store.get(id)["trashed"] as Number).toInt() != 0) return@withLock
                if (row["status"] == "queued") {
                    RhythmDiagnostics.measure(store, id, "import_total") { import(row) }
                    classificationTotal++
                } else {
                    if ((row["classified"] as Number).toInt() == 0) {
                        // Classification failure must never invalidate an existing archive.
                        try { classifier.classify(id) }
                        catch (e: Exception) {
                            android.util.Log.e("RhythmPerf", "Classification unavailable", e)
                            store.prefs.edit().putString("workerError", "分类暂不可用，请重启后重试").apply()
                            throw ClassificationUnavailable(e)
                        }
                        classificationDone++
                        classificationTotal = maxOf(classificationTotal, classificationDone)
                        notify("识别音游图片", classificationDone, classificationTotal)
                        return@withLock
                    }
                    if (row["status"] in listOf("compressed", "ready") && (row["ocrVersion"] as Number).toInt() == 0) {
                        if (shouldYield()) return@withLock
                        try {
                            RhythmDiagnostics.measure(store, id, "ocr_total") { ocrHolder.value.recognize(id) }
                            ocrDone++
                            notify("文字识别 · 已完成 $ocrDone 张", 0, 0, true)
                        } catch (e: Exception) {
                            store.update(id, "ocrError" to (e.message ?: "识别失败").take(500))
                        } catch (_: OutOfMemoryError) {
                            store.update(id, "ocrError" to "内存不足，请稍后重试文字识别")
                            resourceDeferred = true
                        }
                        return@withLock
                    }
                    if (row["status"] !in listOf("pending", "processing")) return@withLock
                    store.update(id, "status" to "processing", "error" to "")
                    if (File(store.directory(id), "manifest.json").exists()) {
                        RhythmCodec.recoverPublished(store, id)
                    } else {
                        val start = android.os.SystemClock.elapsedRealtime()
                        RhythmDiagnostics.measure(store, id, "compress_total") {
                            RhythmCodec.compress(store, store.get(id), { shouldYield() || (store.get(id)["trashed"] as Number).toInt() != 0 }) { done, total ->
                                notify("正在压缩", done, total)
                            }
                        }
                        RhythmPolicy.completed(store, android.os.SystemClock.elapsedRealtime() - start,
                            (row["width"] as Number).toLong() * (row["height"] as Number).toLong())
                    }
                    batchDone++
                    val remaining = (store.stats()["pending"] as Number).toInt()
                    batchTotal = maxOf(batchTotal, batchDone + remaining)
                    notify("已处理 $batchDone / $batchTotal 张", 0, 0, true)
                }
                }
            } catch (_: ClassificationUnavailable) {
                return@withContext Result.retry()
            } catch (_: InterruptedException) {
                store.update(id, "status" to if (row["status"] == "queued") "queued" else "pending",
                    "error" to if (resourceDeferred) "等待系统内存或温度恢复" else "")
                break
            } catch (error: Exception) {
                android.util.Log.e("RhythmPerf", "Image processing failed", error)
                RhythmDiagnostics.event(store, "worker_error", "id" to id, "type" to error.javaClass.simpleName)
                val transparent = error.message == "Transparent images remain original"
                store.update(id, "status" to if (transparent) "ready" else "error",
                    "error" to if (transparent) "透明图片已原样保存" else (error.message ?: error.javaClass.simpleName).take(500))
            } catch (_: OutOfMemoryError) {
                store.update(id, "status" to "error", "error" to "内存不足，已保留原件；可暂停后台任务后重试")
                break
            }
        }
        if (resourceDeferred) Result.retry() else Result.success()
        } finally {
            if (classifierHolder.isInitialized()) classifier.close()
            if (ocrHolder.isInitialized()) ocrHolder.value.close()
        }
    }
    private fun import(row: Map<String, Any>) {
        val id = row["id"] as String
        val dir = store.directory(id).apply { mkdirs() }
        val original = File(dir, "original")
        if (!original.exists()) {
            val partial = File(dir, "import.tmp")
            var total = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            applicationContext.contentResolver.openInputStream(Uri.parse(row["source"] as String)).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                FileOutputStream(partial).use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        if (paused()) throw InterruptedException()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= 128L * 1024 * 1024) { "单张超过初版128MiB限制" }
                        output.write(bytes, 0, count)
                        digest.update(bytes, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total > 0) { "空文件" }
            require(RhythmStore.hash(partial) == digest.digest().joinToString("") { "%02x".format(it) }) { "收纳副本校验失败" }
            check(partial.renameTo(original)) { "收纳文件提交失败" }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "暂不支持此图片格式" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val thumb = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: error("缩略图生成失败")
        val thumbnail = File(dir, "thumbnail.jpg")
        try {
            FileOutputStream(thumbnail).use { output ->
                check(thumb.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.fd.sync()
            }
        } finally { thumb.recycle() }
        val orientation = try { androidx.exifinterface.media.ExifInterface(original).getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }
        store.update(id, "originalBytes" to original.length(), "storedBytes" to (original.length() + thumbnail.length()),
            "width" to bounds.outWidth, "height" to bounds.outHeight, "error" to "",
            "status" to if (orientation in 0..1 && bounds.outMimeType in setOf("image/jpeg", "image/png") &&
                bounds.outWidth <= 4096 && bounds.outHeight <= 8192 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 12_000_000) "pending" else "ready")
        store.freshIds.add(id)
        notify("正在收纳截图", 0, 0)
    }
    private fun notification(title: String, done: Int, total: Int): ForegroundInfo {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("音游相册后台处理").build())
        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        val text = if (title == "识别结算图") "识别 $percent% · $done / $total 张"
            else if (total > 0) "本张 $percent% · 已完成 $batchDone / $batchTotal 张"
            else "已完成 $batchDone / $batchTotal 张 · 可在设置中暂停"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title.take(64))
            .setContentText(text)
            .setSubText(if (total > 0) "$percent%" else "后台收纳")
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent, total == 0).build()
        return if (Build.VERSION.SDK_INT >= 35) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION, notification)
    }
    private fun notify(title: String, done: Int, total: Int, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotification < 250 && done != total) return
        lastNotification = now
        RhythmDiagnostics.event(store, "worker_progress", "phase" to title, "done" to done, "total" to total,
            "percent" to if (total > 0) (100 * done / total).coerceIn(0, 100) else -1)
        try { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION, notification(title, done, total).notification) }
        catch (e: SecurityException) {
            RhythmDiagnostics.event(store, "notification_denied", "type" to e.javaClass.simpleName)
        }
    }
    companion object {
        const val CHANNEL = "rhythm-library"
        private const val NOTIFICATION = 8172
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("rhythm-library-serial", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RhythmWorker>().build())
        }
    }
    private class ClassificationUnavailable(cause: Exception) : Exception(cause)
}
