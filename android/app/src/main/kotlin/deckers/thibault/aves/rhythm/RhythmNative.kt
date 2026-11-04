package deckers.thibault.aves.rhythm

import android.graphics.Bitmap

object RhythmNative {
    external fun writePng(bitmap: android.graphics.Bitmap, path: String)
    init { System.loadLibrary("rhythm_codec") }
    external fun run(args: Array<String>): Int
    external fun writeStripe(bitmap: Bitmap, path: String)
    external fun readStripe(bitmap: Bitmap, path: String)
    external fun metrics(bitmap: Bitmap, path: String): DoubleArray
    external fun jpegEncode(bitmap: Bitmap, path: String, quality: Int)
    external fun bitmapMetrics(source: Bitmap, decoded: Bitmap): DoubleArray
    external fun decodePixels(width: Int, height: Int, frame: String, bootstrap: String?, reference: String?, directReference: Boolean): ByteArray
    external fun putPixels(bitmap: Bitmap, pixels: ByteArray, y: Int, height: Int)
    external fun writeBmp(bitmap: Bitmap, path: String)
    external fun decodeInto(bitmap: Bitmap, y: Int, height: Int, frame: String, bootstrap: String?, reference: String?, expectedHash: String): Boolean
}
