package deckers.thibault.aves.rhythm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Replaceable resource governor. No unbounded image jobs or whole-library RAM decoding. */
object RhythmPolicy {
    @Volatile private var learnedThreads = 1
    private var samples = 0
    private var previousMsPerPixel = 0.0
    fun budget(context: Context): Map<String, Int> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val hot = Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val constrained = hot || power.isPowerSaveMode || memory.lowMemory || memory.availMem < 384L * 1024 * 1024
        val ceiling = if (constrained) 1 else minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        val cacheMb = if (constrained) 24 else (manager.memoryClass / 3).coerceIn(32, 128)
        return mapOf("threads" to minOf(learnedThreads, ceiling), "threadCeiling" to ceiling,
            "songJobs" to songJobs(Runtime.getRuntime().availableProcessors(), manager.memoryClass, memory.availMem, constrained),
            "cacheMb" to cacheMb, "prefetch" to if (constrained) 0 else if (cacheMb >= 64) 2 else 1,
            "decodeJobs" to if (constrained) 1 else 2)
    }
    fun songJobs(cpus: Int, heapMb: Int, availableBytes: Long, constrained: Boolean): Int =
        if (constrained) 1 else minOf(4, maxOf(1,cpus/2), maxOf(1,heapMb/256),
            maxOf(1,(availableBytes/(384L*1024*1024)).toInt()))
    @Synchronized
    fun completed(store: RhythmStore, elapsedMs: Long, pixels: Long) {
        val perPixel = elapsedMs.toDouble() / maxOf(1, pixels)
        val cap = budget(store.context).getValue("threadCeiling")
        // Warm up gradually. Back off if recent work becomes slower or resources tighten.
        if (cap == 1 || (previousMsPerPixel > 0 && perPixel > previousMsPerPixel * 1.5)) {
            learnedThreads = maxOf(1, learnedThreads - 1)
            samples = 0
        } else if (++samples >= 2) {
            learnedThreads = minOf(cap, learnedThreads + 1)
            samples = 0
        }
        previousMsPerPixel = if (previousMsPerPixel == 0.0) perPixel else previousMsPerPixel * .7 + perPixel * .3
        RhythmDiagnostics.event(store, "governor", "threads" to minOf(learnedThreads, cap),
            "ceiling" to cap, "msPerMegapixel" to perPixel * 1_000_000)
    }
}
