package deckers.thibault.aves.rhythm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** ADB-only diagnostics entry point: protected by platform DUMP permission. */
class RhythmBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: "export"
        if (mode == "display") {
            val directory = java.io.File(context.getExternalFilesDir(null), "diagnostics")
            RhythmActivity.diagnosticsChannel?.invokeMethod("benchmarkDisplay", mapOf(
                "png" to java.io.File(directory, "display-probe.png").path,
                "bmp" to java.io.File(directory, "display-probe.bmp").path),
                object : io.flutter.plugin.common.MethodChannel.Result {
                    override fun success(result: Any?) {
                        java.io.File(directory, "flutter-display.json").writeText(org.json.JSONObject(result as Map<*, *>).toString(2))
                    }
                    override fun error(code: String, message: String?, details: Any?) {
                        java.io.File(directory, "flutter-display.json").writeText(org.json.JSONObject().put("error", "$code: $message").toString())
                    }
                    override fun notImplemented() {}
                })
            return
        }
        if (mode !in listOf("export", "benchmark", "cleanup", "reviewOther", "reviewCheck", "reviewApply")) return
        WorkManager.getInstance(context).enqueueUniqueWork("rhythm-benchmark", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RhythmBenchmarkWorker>().setInputData(workDataOf("mode" to mode)).build())
    }
}
