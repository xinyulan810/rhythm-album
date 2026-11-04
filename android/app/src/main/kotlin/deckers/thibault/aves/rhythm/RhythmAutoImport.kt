package deckers.thibault.aves.rhythm

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

/** MediaStore index scan only. Never crawls directories or deletes system photos. */
object RhythmAutoImport {
    fun permission(context: Context): String {
        fun granted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
            else -> "none"
        }
    }
    @Synchronized
    fun scan(context: Context): Int {
        val store = RhythmStore.get(context)
        if (!store.prefs.getBoolean("autoImport", false) || permission(context) == "none") return 0
        val fields = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        val selection = buildString {
            append("LOWER(${MediaStore.Images.Media.DISPLAY_NAME}) LIKE ?")
            if (Build.VERSION.SDK_INT >= 29) append(" AND ${MediaStore.Images.Media.IS_PENDING}=0")
            if (Build.VERSION.SDK_INT >= 30) append(" AND ${MediaStore.Images.Media.IS_TRASHED}=0")
            if (Build.VERSION.SDK_INT >= 29) append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE 'Pictures/RhythmAlbum/%'")
        }
        var added = 0
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fields,
            selection, arrayOf("%screenshot%"), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            synchronized(store) {
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    val date = c.getLong(2).takeIf { it > 0 } ?: c.getLong(3) * 1000
                    val name = c.getString(1).orEmpty()
                    val game = when {
                        name.contains("AstroDX", true) -> "AstroDX"
                        name.contains("Phigros", true) -> "Phigros"
                        else -> ""
                    }
                    if (store.enqueue(uri.toString(), name, game, false, date)) added++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            }
        }
        store.prefs.edit().putLong("lastScan", System.currentTimeMillis()).remove("scanError").apply()
        RhythmDiagnostics.event(store, "auto_scan", "added" to added, "access" to permission(context))
        return added
    }
    fun configure(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!RhythmStore.get(context).prefs.getBoolean("autoImport", false)) {
            manager.cancelUniqueWork("rhythm-auto-periodic")
            manager.cancelUniqueWork("rhythm-auto-watch")
            return
        }
        manager.enqueueUniquePeriodicWork("rhythm-auto-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RhythmScanWorker>(15, TimeUnit.MINUTES).build())
        watch(context, ExistingWorkPolicy.KEEP)
    }
    fun watch(context: Context, policy: ExistingWorkPolicy) {
        if (!RhythmStore.get(context).prefs.getBoolean("autoImport", false)) return
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("rhythm-auto-watch", policy,
            OneTimeWorkRequestBuilder<RhythmScanWorker>().setConstraints(constraints)
                .setInputData(workDataOf("watch" to true)).build())
    }
}

class RhythmScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val store = RhythmStore.get(applicationContext)
        try {
            RhythmAutoImport.scan(applicationContext)
            if ((store.stats()["pending"] as Number).toInt() > 0) RhythmWorker.schedule(applicationContext)
            if (inputData.getBoolean("watch", false)) RhythmAutoImport.watch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            Result.success()
        } catch (e: Exception) {
            store.prefs.edit().putString("scanError", "扫描失败：${e.javaClass.simpleName}，请检查图片权限").apply()
            RhythmDiagnostics.event(store, "scan_error", "type" to e.javaClass.simpleName)
            Result.retry()
        }
    }
}
