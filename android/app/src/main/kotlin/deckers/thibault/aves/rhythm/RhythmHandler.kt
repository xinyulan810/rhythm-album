package deckers.thibault.aves.rhythm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.content.ClipData
import androidx.core.content.FileProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.concurrent.withLock

class RhythmHandler(private val activity: Activity) : MethodChannel.MethodCallHandler {
    private val store = RhythmStore.get(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pickerResult: MethodChannel.Result? = null
    private var notificationResult: MethodChannel.Result? = null
    private var mediaResult: MethodChannel.Result? = null
    private var exportResult: MethodChannel.Result? = null
    private var managedResult: MethodChannel.Result? = null
    private var managedBatch = emptyList<Pair<String, Uri>>()
    private var managedRequestStarted = 0L
    @Volatile private var managedCancelled = false
    private fun managedProgress(phase: String, done: Int, total: Int, failed: Int) {
        Handler(Looper.getMainLooper()).post {
            RhythmActivity.diagnosticsChannel?.invokeMethod("managedProgress",
                mapOf("phase" to phase, "completed" to done, "total" to total, "failed" to failed))
        }
    }
    private var exportIds = emptyList<String>()
    @Volatile private var transferCancelled = false
    @Volatile private var transferActive = false
    private fun transferIds(call: MethodCall) = call.argument<List<String>>("ids").orEmpty().distinct()
    private fun beginTransfer() {
        check(!transferActive) { "已有批量任务正在运行" }
        transferActive = true
        transferCancelled = false
    }
    private fun transferProgress(done: Int, total: Int, failed: Int) {
        Handler(Looper.getMainLooper()).post {
            RhythmActivity.diagnosticsChannel?.invokeMethod("transferProgress",
                mapOf("completed" to done, "total" to total, "failed" to failed))
        }
    }
    private fun transfer(ids: List<String>, operation: (String) -> Unit): Map<String, Any> {
        var completed = 0
        val failures = mutableListOf<Map<String, String>>()
        transferProgress(0, ids.size, 0)
        for (id in ids) {
            if (transferCancelled) break
            try { operation(id); completed++ }
            catch (e: Exception) { failures.add(mapOf("id" to id, "error" to (e.message ?: "失败"))) }
            transferProgress(completed, ids.size, failures.size)
        }
        return mapOf("completed" to completed, "total" to ids.size, "failed" to failures.size,
            "failures" to failures, "cancelled" to transferCancelled)
    }
    private var scanJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { requestScan() }
    }
    private var pickedGame = ""
    private var pickedEligible = false
    init {
        try { activity.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer) }
        catch (_: SecurityException) { /* Permission may be granted later. Resume always scans. */ }
        RhythmAutoImport.configure(activity)
        requestScan()
        scope.launch(Dispatchers.IO) {
            if (!store.prefs.getBoolean("paused", false) && ((store.stats()["pending"] as Number).toInt() > 0 ||
                    (store.stats()["classifying"] as Number).toInt() > 0 || (store.stats()["ocrPending"] as Number).toInt() > 0)) {
                RhythmWorker.schedule(activity)
            }
        }
    }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "managedPermissionSettings") {
            if (Build.VERSION.SDK_INT < 31) {
                result.error("unsupported", "自动代管需要 Android 12 或更新版本", null)
                return
            }
            try {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
                    .setData(Uri.parse("package:${activity.packageName}")))
                result.success(null)
            } catch (e: Exception) { result.error("permission", e.message, null) }
            return
        }
        if (call.method == "cancelManaged") {
            managedCancelled = true
            result.success(null)
            return
        }
        if (call.method == "deleteManagedSources") {
            if (managedResult != null) { result.error("busy", "正在等待系统删除确认", null); return }
            managedCancelled = false
            managedResult = result
            scope.launch {
                try {
                    val started = SystemClock.elapsedRealtime()
                    managedBatch = withContext(Dispatchers.IO) {
                        RhythmManaged.prepare(store, 1000, { managedCancelled }) { done, total, failed ->
                            managedProgress("准备清理", done, total, failed)
                        }
                    }
                    RhythmDiagnostics.event(store, "managed_prepare", "count" to managedBatch.size,
                        "ms" to (SystemClock.elapsedRealtime() - started))
                    if (managedCancelled || managedBatch.isEmpty()) {
                        val cancelled = managedCancelled
                        managedBatch = emptyList()
                        managedResult = null
                        result.success(mapOf("removed" to 0, "cancelled" to cancelled))
                    } else if (Build.VERSION.SDK_INT >= 30) {
                        val automatic = Build.VERSION.SDK_INT >= 31 &&
                            store.prefs.getBoolean("managedAutomatic", false) && MediaStore.canManageMedia(activity)
                        managedProgress(if (automatic) "系统批量清理" else "等待系统确认",
                            0, managedBatch.size, 0)
                        val request = MediaStore.createDeleteRequest(activity.contentResolver, managedBatch.map { it.second })
                        managedRequestStarted = SystemClock.elapsedRealtime()
                        activity.startIntentSenderForResult(request.intentSender, MANAGED_DELETE, null, 0, 0, 0)
                    }
                } catch (e: Exception) {
                    store.prefs.edit().putBoolean("managedAutomatic", false).apply()
                    managedResult = null; managedBatch = emptyList()
                    result.error("managed", e.message, null)
                } catch (_: OutOfMemoryError) {
                    managedResult = null; managedBatch = emptyList()
                    result.error("managed", "验证内存不足，已保留原图，请稍后重试", null)
                }
            }
            return
        }
        if (call.method == "cancelTransfer") { transferCancelled = true; result.success(null); return }
        if (call.method == "enableAuto") {
            val enabled = call.argument<Boolean>("enabled") == true
            store.prefs.edit().putBoolean("autoImport", enabled).apply()
            if (enabled && RhythmAutoImport.permission(activity) != "full") {
                if (mediaResult != null) { result.error("busy", "正在请求图片权限", null); return }
                mediaResult = result
                val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                else if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
                else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                ActivityCompat.requestPermissions(activity, permissions, MEDIA_PERMISSION)
            } else {
                RhythmAutoImport.configure(activity); requestScan()
                result.success(RhythmAutoImport.permission(activity))
            }
            return
        }
        if (call.method == "appSettings") {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            result.success(null); return
        }
        if (call.method == "exportDirectory") {
            if (transferActive) { result.error("busy", "正在导出或准备分享", null); return }
            beginTransfer()
            exportIds = transferIds(call)
            exportResult = result
            try { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), EXPORT_DIRECTORY) }
            catch (e: Exception) { exportResult = null; transferActive = false; result.error("export", e.message, null) }
            return
        }
        if (call.method == "share") {
            if (transferActive) { result.error("busy", "正在导出或准备分享", null); return }
            beginTransfer()
            scope.launch {
                try {
                    val uris = mutableListOf<Uri>()
                    val summary = withContext(Dispatchers.IO) {
                        transfer(transferIds(call)) {
                            uris.add(FileProvider.getUriForFile(activity, "${activity.packageName}.file_provider", RhythmImages.shareCopy(store, it)))
                        }
                    }
                    if (transferCancelled || uris.isEmpty()) { result.success(summary); return@launch }
                    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/*"
                        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        clipData = ClipData.newUri(activity.contentResolver, "音游截图", uris.first()).apply {
                            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "分享截图"))
                    result.success(summary)
                } catch (e: Exception) { result.error("share", e.message, null) }
                finally { transferActive = false }
            }
            return
        }
        if (call.method == "pick") {
            if (pickerResult != null) { result.error("busy", "选择器已打开", null); return }
            pickedGame = call.argument<String>("game")?.trim().orEmpty()
            pickedEligible = call.argument<Boolean>("eligible") == true
            if (pickedEligible && pickedGame.isBlank()) { result.error("game", "请先填写游戏名", null); return }
            pickerResult = result
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, PICK)
            } catch (e: Exception) { pickerResult = null; result.error("picker", e.message, null) }
            return
        }
        if (call.method == "notifications") {
            if (notificationResult != null) { result.error("busy", "正在请求通知权限", null); return }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationResult = result
                ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS)
            } else result.success(notificationState())
            return
        }
        if (call.method == "notificationSettings") {
            activity.startActivity(if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            })
            result.success(null); return
        }
        // Set before dispatch so a quick Back cannot be overtaken by a queued IO call.
        if (call.method == "viewerStart") { RhythmCodec.viewing = true; result.success(null); return }
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { dispatch(call) }
                result.success(value)
            } catch (e: Exception) { result.error("rhythm", e.message ?: "操作失败", null) }
            catch (_: OutOfMemoryError) { result.error("memory", "内存不足，请关闭其他应用后重试", null) }
        }
    }
    private fun dispatch(call: MethodCall): Any? = when (call.method) {
        "managedMode" -> {
            val enabled = call.argument<Boolean>("enabled") == true
            require(!enabled || RhythmManaged.supported()) { "代管删除需要 Android 11 或更新版本" }
            store.prefs.edit().putBoolean("managedChoiceMade", true).putBoolean("managedScreenshots", enabled)
                .putBoolean("managedAutomatic", if (enabled) store.prefs.getBoolean("managedAutomatic", false) else false).commit()
            null
        }
        "managedAutomatic" -> {
            val enabled = call.argument<Boolean>("enabled") == true
            require(!enabled || (Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(activity))) {
                "请先在系统设置中授予媒体管理权限"
            }
            store.prefs.edit().putBoolean("managedAutomatic", enabled).commit()
            null
        }
        "retryManaged" -> {
            store.writableDatabase.execSQL("UPDATE images SET sourceDeleteError='' WHERE sourceRemoved=0")
            null
        }
        "list", "selectionIds" -> {
            val search = call.argument<String>("search")?.take(200).orEmpty()
            val album = call.argument<String>("album").orEmpty()
            val category = call.argument<String>("category").orEmpty()
            val where = mutableListOf("trashed=${if (call.argument<Boolean>("trash") == true) 1 else 0}")
            val args = mutableListOf<String>()
            if (search.isNotBlank()) {
                val terms = java.text.Normalizer.normalize(search, java.text.Normalizer.Form.NFKC).trim().split(Regex("\\s+")).take(16)
                for (term in terms) {
                    val badge = term.uppercase(java.util.Locale.ROOT)
                    if (badge in listOf("AP", "FC")) {
                        where.add("(${RhythmAchievement.EFFECTIVE_SQL}=? OR instr(' '||upper(replace(tags,',',' '))||' ',?)>0)")
                        args.add(badge); args.add(" $badge ")
                    } else {
                        where.add("(instr(lower(name),lower(?))>0 OR instr(lower(game),lower(?))>0 OR instr(lower(song),lower(?))>0 OR instr(lower(tags),lower(?))>0 OR categoryCode IN (SELECT c.code FROM categories c JOIN categories t ON t.code=c.target WHERE instr(lower(t.name),lower(?))>0) OR instr(ocrSearch,?)>0)")
                        repeat(5) { args.add(term) }
                        args.add(RhythmAchievement.searchKey(term))
                    }
                }
            }
            val achievement = call.argument<String>("achievement").orEmpty()
            if (achievement in listOf("AP", "FC")) { where.add("${RhythmAchievement.EFFECTIVE_SQL}=?"); args.add(achievement) }
            if (album.isNotBlank()) { where.add("album=?"); args.add(album) }
            if (category == "other") where.add("NOT (${RhythmCategories.VISIBLE})")
            else if (category.isNotBlank()) {
                where.add("(${RhythmCategories.VISIBLE}) AND categoryCode IN (SELECT code FROM categories WHERE target=?)")
                args.add(category)
            }
            if (call.argument<Boolean>("favorite") == true) where.add("favorite=1")
            val snapshot = call.argument<Number>("snapshot")?.toLong()
            if (snapshot != null) { where.add("created<=?"); args.add(snapshot.toString()) }
            val offset = (call.argument<Number>("offset")?.toInt() ?: 0).coerceIn(0, 1_000_000)
            val limit = (call.argument<Number>("limit")?.toInt() ?: 60).coerceIn(1, 240)
            if (call.method == "selectionIds") store.readableDatabase.rawQuery(
                "SELECT id FROM images WHERE ${where.joinToString(" AND ")} ORDER BY taken DESC,id DESC", args.toTypedArray()
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            else mapOf("items" to store.query(where.joinToString(" AND "), args.toTypedArray(), limit, offset,
                    call.argument<Boolean>("ascending") == true),
                "total" to store.count(where.joinToString(" AND "), args.toTypedArray()),
                "stats" to (store.stats() + mapOf("notifications" to notificationState(),
                    "workerError" to store.prefs.getString("workerError", "").orEmpty(),
                    "autoImport" to store.prefs.getBoolean("autoImport", false),
                    "mediaAccess" to RhythmAutoImport.permission(activity),
                    "lastScan" to store.prefs.getLong("lastScan", 0),
                    "scanError" to store.prefs.getString("scanError", "").orEmpty(),
                    "encodingMode" to store.prefs.getString("encodingMode", "fast").orEmpty(),
                    "managedChoiceMade" to store.prefs.getBoolean("managedChoiceMade", false),
                    "managedScreenshots" to store.prefs.getBoolean("managedScreenshots", false),
                    "managedAutomatic" to store.prefs.getBoolean("managedAutomatic", false),
                    "managedPermission" to (Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(activity)),
                    "managedSupported" to RhythmManaged.supported(),
                    "managedPending" to RhythmManaged.count(store),
                    "managedErrors" to RhythmManaged.count(store, errors = true),
                    "budget" to RhythmPolicy.budget(activity))))
        }
        "scan" -> {
            RhythmAutoImport.configure(activity)
            val added = RhythmAutoImport.scan(activity)
            if ((store.stats()["pending"] as Number).toInt() > 0) RhythmWorker.schedule(activity)
            added
        }
        "budget" -> RhythmPolicy.budget(activity)
        "encodingMode" -> {
            val mode = call.argument<String>("mode")
            require(mode in listOf("fast", "compact"))
            store.prefs.edit().putString("encodingMode", mode).apply()
            null
        }
        "retryOcr" -> {
            store.writableDatabase.execSQL("UPDATE images SET ocrError='',ocrVersion=0 WHERE ocrError!='' AND trashed=0")
            RhythmWorker.schedule(activity)
            null
        }
        "categorySuggestions" -> store.categories.suggestions()
        "categoryIds" -> store.categories.ids(call.argument<List<String>>("codes").orEmpty())
        "mergeCategories" -> { store.categories.merge(call.argument<List<String>>("codes").orEmpty(), requireNotNull(call.argument<String>("target"))); null }
        "renameCategory" -> store.categories.rename(requireNotNull(call.argument<String>("code")), call.argument<String>("name").orEmpty())
        "undoCategory" -> { store.categories.undo(); null }
        "correctCategory" -> {
            store.categories.correct(transferIds(call),
                call.argument<String>("name").orEmpty(), call.argument<Boolean>("other") == true)
            null
        }
        "batch" -> {
            val ids = transferIds(call)
            val operation = call.argument<String>("operation")
            if (operation == "purge") store.operations.withLock {
                for (id in ids) {
                    require((store.get(id)["trashed"] as Number).toInt() == 1) { "请先移入回收站" }
                    store.update(id, "trashed" to 2)
                }
                store.collectDeleted()
            } else {
                for (id in ids) {
                    require((store.get(id)["trashed"] as Number).toInt() != 2)
                    when (operation) {
                        "trash" -> store.update(id, "trashed" to 1)
                        "restore" -> store.update(id, "trashed" to 0)
                        "favorite" -> store.update(id, "favorite" to if (call.argument<Boolean>("value") == true) 1 else 0)
                        "album" -> store.update(id, "album" to call.argument<String>("value").orEmpty().trim().take(100))
                        "achievement" -> {
                            val tag = call.argument<String>("value")
                            require(tag in listOf("auto", "", "AP", "FC"))
                            store.update(id, "achievementOverride" to tag!!)
                        }
                        else -> error("未知批量操作")
                    }
                }
            }
            if (operation == "restore") RhythmWorker.schedule(activity)
            ids.size
        }
        "export" -> {
            beginTransfer()
            try { transfer(transferIds(call)) { RhythmImages.export(store, it) } }
            finally { transferActive = false }
        }
        "editImage" -> RhythmImages.editCopy(store, requireNotNull(call.argument<String>("id")),
            call.argument<List<Number>>("crop")?.map { it.toDouble() } ?: listOf(0.0, 0.0, 1.0, 1.0),
            call.argument<Number>("turns")?.toInt() ?: 0)
        "pause" -> {
            store.prefs.edit().putBoolean("paused", true).commit(); null
        }
        "resume" -> {
            store.prefs.edit().putBoolean("paused", false).commit()
            RhythmWorker.schedule(activity); null
        }
        "retry" -> {
            val id = requireNotNull(call.argument<String>("id"))
            val row = store.get(id)
            require(row["status"] == "error")
            require(!File(store.directory(id), "manifest.json").exists()) { "已发布实验包，请保留数据并反馈错误" }
            store.update(id, "status" to if (File(store.directory(id), "original").exists()) "pending" else "queued", "error" to "")
            RhythmWorker.schedule(activity); null
        }
        "edit" -> {
            val id = requireNotNull(call.argument<String>("id"))
            val row = store.get(id)
            val game = call.argument<String>("game")?.trim().orEmpty().take(100)
            val eligible = call.argument<Boolean>("eligible") == true
            require(!eligible || game.isNotEmpty() || (row["categoryCode"] as String).isNotEmpty()) { "确认结算前请填写游戏名" }
            store.update(id, "game" to game, "song" to call.argument<String>("song").orEmpty().take(500),
                "name" to call.argument<String>("name").orEmpty().trim().take(256).ifEmpty { row["name"] as String },
                "album" to call.argument<String>("album").orEmpty().trim().take(100),
                "tags" to call.argument<String>("tags").orEmpty().take(1000),
                "eligible" to if (eligible) 1 else 0)
            call.argument<String>("achievementOverride")?.let {
                require(it in listOf("auto", "", "AP", "FC"))
                store.update(id, "achievementOverride" to it)
            }
            if (game != row["game"] || eligible != ((row["eligible"] as Number).toInt() == 1))
                store.categories.correct(listOf(id), game, !eligible)
            if (eligible && row["status"] == "ready") {
                store.update(id, "status" to "pending")
                RhythmWorker.schedule(activity)
            }
            null
        }
        "view" -> {
            val id = requireNotNull(call.argument<String>("id"))
            val row = store.get(id)
            val original = File(store.directory(id), "original")
            require((row["trashed"] as Number).toInt() != 2) { "照片已永久删除" }
            require(original.exists() || row["status"] == "compressed") { "图片尚未收纳完成" }
            RhythmDiagnostics.measure(store, id, "view_native") {
                if (row["status"] == "compressed" && (!original.exists() || !store.freshIds.contains(id))) RhythmCodec.cachedRender(store, id, full = true, display = true).path else original.path
            }
        }
        "diagnostics" -> RhythmDiagnostics.read(store)
        "viewTiming" -> {
            RhythmDiagnostics.event(store, "view_flutter",
                "id" to call.argument<String>("id").orEmpty(),
                "ms" to (call.argument<Number>("ms")?.toLong() ?: -1),
                "clickToClearMs" to (call.argument<Number>("clickToClearMs")?.toLong() ?: -1),
                "frame" to call.argument<String>("frame").orEmpty())
            null
        }
        "uiTiming" -> {
            RhythmDiagnostics.event(store, "ui_timing",
                "kind" to call.argument<String>("kind").orEmpty(),
                "frames" to (call.argument<Number>("frames")?.toInt() ?: 0),
                "slowFrames" to (call.argument<Number>("slowFrames")?.toInt() ?: 0),
                "over60Frames" to (call.argument<Number>("over60Frames")?.toInt() ?: 0),
                "p95BuildMs" to (call.argument<Number>("p95BuildMs")?.toDouble() ?: 0.0),
                "p95RasterMs" to (call.argument<Number>("p95RasterMs")?.toDouble() ?: 0.0),
                "maxBuildMs" to (call.argument<Number>("maxBuildMs")?.toDouble() ?: 0.0),
                "maxRasterMs" to (call.argument<Number>("maxRasterMs")?.toDouble() ?: 0.0),
                "ms" to (call.argument<Number>("ms")?.toInt() ?: 0))
            null
        }
        "viewEnd" -> {
            RhythmCodec.viewing = false
            if (!store.prefs.getBoolean("paused", false)) RhythmWorker.schedule(activity)
            null
        }
        else -> error("Unknown library operation")
    }
    private fun notificationState(): Map<String, Any> {
        val manager = NotificationManagerCompat.from(activity)
        val permission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = Build.VERSION.SDK_INT < 26 ||
            manager.getNotificationChannel(RhythmWorker.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        return mapOf("enabled" to (permission && manager.areNotificationsEnabled() && channelEnabled),
            "permission" to permission, "channelEnabled" to channelEnabled)
    }
    fun onRequestPermissionsResult(requestCode: Int): Boolean {
        if (requestCode == MEDIA_PERMISSION) {
            RhythmAutoImport.configure(activity); requestScan()
            mediaResult?.success(RhythmAutoImport.permission(activity)); mediaResult = null
            return true
        }
        if (requestCode != NOTIFICATIONS) return false
        notificationResult?.success(notificationState())
        notificationResult = null
        return true
    }
    fun onActivityResult(request: Int, resultCode: Int, data: Intent?): Boolean {
        if (request == MANAGED_DELETE) {
            val pending = managedResult
            val batch = managedBatch
            managedResult = null; managedBatch = emptyList()
            RhythmDiagnostics.event(store, "managed_system_delete", "count" to batch.size,
                "ms" to (SystemClock.elapsedRealtime() - managedRequestStarted), "ok" to (resultCode == Activity.RESULT_OK))
            if (resultCode != Activity.RESULT_OK) {
                store.prefs.edit().putBoolean("managedAutomatic", false).apply()
                pending?.success(mapOf("removed" to 0, "cancelled" to true))
            } else scope.launch {
                try {
                    val removed = withContext(Dispatchers.IO) {
                        val started = SystemClock.elapsedRealtime()
                        RhythmManaged.finish(store, batch) { done, total, failed ->
                            managedProgress("清理副本", done, total, failed)
                        }.also { RhythmDiagnostics.event(store, "managed_finish", "count" to batch.size,
                            "removed" to it, "ms" to (SystemClock.elapsedRealtime() - started)) }
                    }
                    pending?.success(mapOf("removed" to removed, "cancelled" to false))
                } catch (e: Exception) {
                    store.prefs.edit().putBoolean("managedAutomatic", false).apply()
                    pending?.error("managed", e.message, null)
                }
            }
            return true
        }
        if (request == EXPORT_DIRECTORY) {
            val pending = exportResult
            val ids = exportIds
            exportResult = null; exportIds = emptyList()
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) { transferActive = false; pending?.success(0); return true }
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                        transfer(ids) { id -> store.operations.withLock {
                            val source = RhythmImages.full(store, id)
                            val mime = RhythmImages.mime(source)
                            val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
                            val target = requireNotNull(DocumentsContract.createDocument(activity.contentResolver, parent, mime,
                                "Rhythm_$id.$ext"))
                            try {
                                activity.contentResolver.openOutputStream(target).use { output ->
                                    requireNotNull(output); source.inputStream().use { it.copyTo(output) }
                                }
                            } catch (e: Exception) {
                                DocumentsContract.deleteDocument(activity.contentResolver, target)
                                throw e
                            }
                        } }
                    }
                    pending?.success(count)
                } catch (e: Exception) { pending?.error("export", "部分文件可能已导出：${e.message}", null) }
                finally { transferActive = false }
            }
            return true
        }
        if (request != PICK) return false
        val pending = pickerResult
        pickerResult = null
        if (resultCode != Activity.RESULT_OK || data == null) { pending?.success(0); return true }
        val uris = linkedSetOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip -> for (i in 0 until minOf(clip.itemCount, 2000)) uris.add(clip.getItemAt(i).uri) }
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    var added = 0
                    for (uri in uris) {
                        require(uri.scheme == "content") { "仅支持系统文件选择器的内容URI" }
                        activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "Screenshot"
                        val game = pickedGame.ifEmpty {
                            when {
                                name.contains("com.Reflektone.AstroDX", true) -> "AstroDX"
                                name.contains("com.PigeonGames.Phigros", true) -> "Phigros"
                                else -> ""
                            }
                        }
                        if (store.enqueue(uri.toString(), name, game, pickedEligible)) added++
                    }
                    RhythmWorker.schedule(activity)
                    added
                }
                pending?.success(count)
            } catch (e: Exception) {
                // Successfully queued predecessors still run if a later URI fails.
                RhythmWorker.schedule(activity)
                pending?.error("import", e.message, null)
            }
        }
        return true
    }
    fun close() {
        activity.contentResolver.unregisterContentObserver(observer)
        pickerResult?.error("closed", "页面已关闭，请重新选择", null); pickerResult = null
        notificationResult?.error("closed", "页面已关闭", null); notificationResult = null
        mediaResult?.error("closed", "页面已关闭", null); mediaResult = null
        exportResult?.error("closed", "页面已关闭", null); exportResult = null
        RhythmCodec.viewing = false; scope.cancel()
    }
    private fun requestScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(600)
            withContext(Dispatchers.IO) {
                try {
                    RhythmAutoImport.scan(activity)
                    if ((store.stats()["pending"] as Number).toInt() > 0) RhythmWorker.schedule(activity)
                } catch (e: Exception) {
                    store.prefs.edit().putString("scanError", "自动扫描失败，请检查照片访问权限").apply()
                    RhythmDiagnostics.event(store, "scan_error", "type" to e.javaClass.simpleName)
                }
            }
        }
    }
    companion object {
        const val CHANNEL = "io.rhythmalbum/library"
        const val PICK = 8173
        const val NOTIFICATIONS = 8174
        const val MEDIA_PERMISSION = 8175
        const val EXPORT_DIRECTORY = 8176
        const val MANAGED_DELETE = 8177
    }
}
