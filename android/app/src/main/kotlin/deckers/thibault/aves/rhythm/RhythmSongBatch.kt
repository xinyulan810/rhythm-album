package deckers.thibault.aves.rhythm

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Fixed lanes reuse their own recognizers. No unbounded coroutine/image fan-out. */
object RhythmSongBatch {
    suspend fun <T> run(items: List<T>, lanes: Int, stop: () -> Boolean,
                       process: suspend (Int, T) -> Unit) = coroutineScope {
        val next = AtomicInteger()
        List(minOf(lanes.coerceIn(1,4),items.size)) { lane ->
            async(Dispatchers.IO) {
                while (!stop()) {
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    process(lane,items[index])
                }
            }
        }.awaitAll()
    }
}
