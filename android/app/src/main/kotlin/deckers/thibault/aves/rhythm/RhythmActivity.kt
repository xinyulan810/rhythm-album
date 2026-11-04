package deckers.thibault.aves.rhythm

import android.content.Intent
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Screenshot-only launcher; no upstream media navigation or analysis startup. */
class RhythmActivity : FlutterFragmentActivity() {
    private var handler: RhythmHandler? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        handler = RhythmHandler(this).also {
            diagnosticsChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, RhythmHandler.CHANNEL).apply { setMethodCallHandler(it) }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handler?.onRequestPermissionsResult(requestCode)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handler?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onDestroy() {
        diagnosticsChannel = null
        handler?.close()
        super.onDestroy()
    }
    companion object { var diagnosticsChannel: MethodChannel? = null }
}
