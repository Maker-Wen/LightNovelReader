package indi.dmzz_yyhyy.lightnovelreader.benchmark

import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderBenchmarkProbe
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderMarkerFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** In-process control for real repository requests, progress-row draws and image measurements. */
internal object ReaderSeekBenchmarkController {
    private class Gate {
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val active = AtomicInteger()
        val passed = AtomicInteger()
        val cancelled = AtomicInteger()
    }

    private data class Draw(val sequence: Long, val uptime: Long, val frame: ReaderMarkerFrame)
    private data class ImageSize(
        val bookId: String,
        val chapterId: String,
        val componentIndex: Int,
        val heightPx: Int,
        val uptime: Long,
    )

    private val gates = ConcurrentHashMap<String, Gate>()
    private val lock = Any()
    private val draws = ArrayDeque<Draw>()
    private val imageSizes = linkedMapOf<Pair<String, Int>, ImageSize>()
    private var sequence = 0L
    private var dropped = 0

    fun handle(intent: Intent): String = when (val operation = intent.getStringExtra("operation")) {
        "arm" -> {
            reset()
            val bookId = requireNotNull(intent.getStringExtra("bookId"))
            val chapterIds = requireNotNull(intent.getStringExtra("chapterIds"))
                .split(',').filter { it.isNotBlank() }
            require(chapterIds.isNotEmpty())
            chapterIds.forEach { gates[it] = Gate() }
            ReaderBenchmarkProbe.beforeChapterLoad = { requestedBook, chapterId ->
                if (requestedBook == bookId) gates[chapterId]?.let { gate ->
                    gate.entered.incrementAndGet()
                    gate.active.incrementAndGet()
                    try {
                        // Suspension is cancellable; superseding a real seek cancels its waiter.
                        withTimeout(45_000) { gate.release.await() }
                        gate.passed.incrementAndGet()
                    } catch (cancelled: CancellationException) {
                        gate.cancelled.incrementAndGet()
                        throw cancelled
                    } finally {
                        gate.active.decrementAndGet()
                    }
                }
            }
            ReaderBenchmarkProbe.onMarkerFrame = { frame ->
                synchronized(lock) {
                    if (draws.size == 1_024) {
                        draws.removeFirst()
                        dropped++
                    }
                    draws.addLast(Draw(++sequence, SystemClock.uptimeMillis(), frame))
                }
            }
            ReaderBenchmarkProbe.onImageSize = { requestedBook, chapterId, componentIndex, heightPx ->
                if (requestedBook == bookId) synchronized(lock) {
                    val key = chapterId to componentIndex
                    imageSizes.remove(key)
                    if (imageSizes.size == 32) imageSizes.remove(imageSizes.keys.first())
                    imageSizes[key] = ImageSize(bookId, chapterId, componentIndex, heightPx, SystemClock.uptimeMillis())
                }
            }
            "probe=ARMED"
        }
        "release" -> {
            val id = requireNotNull(intent.getStringExtra("chapterId"))
            requireNotNull(gates[id]) { "No armed chapter gate: $id" }.release.complete(Unit)
            "probe=RELEASED"
        }
        "drain" -> drain()
        "reset" -> {
            reset()
            "probe=RESET"
        }
        else -> error("Unsupported seek probe operation: $operation")
    }

    private fun reset() {
        ReaderBenchmarkProbe.beforeChapterLoad = null
        ReaderBenchmarkProbe.onMarkerFrame = null
        ReaderBenchmarkProbe.onImageSize = null
        gates.values.forEach { it.release.complete(Unit) }
        gates.clear()
        synchronized(lock) {
            draws.clear()
            imageSizes.clear()
            sequence = 0
            dropped = 0
        }
    }

    private fun drain(): String {
        val (batch, droppedCount, images) = synchronized(lock) {
            Triple(draws.toList(), dropped, imageSizes.values.toList()).also { draws.clear() }
        }
        val gateJson = JSONObject()
        gates.forEach { (id, gate) ->
            gateJson.put(id, JSONObject()
                .put("entered", gate.entered.get())
                .put("active", gate.active.get())
                .put("passed", gate.passed.get())
                .put("cancelled", gate.cancelled.get())
                .put("released", gate.release.isCompleted))
        }
        val frameJson = JSONArray()
        batch.forEach { draw ->
            val frame = draw.frame
            frameJson.put(JSONObject()
                .put("sequence", draw.sequence)
                .put("uptime", draw.uptime)
                .put("value", frame.value)
                .put("displayedOrigin", frame.displayedOrigin ?: JSONObject.NULL)
                .put("formalOrigin", frame.formalOrigin ?: JSONObject.NULL)
                .put("gestureOrigin", frame.gestureOrigin ?: JSONObject.NULL)
                .put("previewing", frame.isPreviewing)
                .put("enabled", frame.enabled)
                .put("trackWidth", frame.trackWidthPx)
                .put("markerX", frame.markerCenterXPx ?: JSONObject.NULL))
        }
        val imageJson = JSONArray()
        images.forEach { image ->
            imageJson.put(JSONObject().put("bookId", image.bookId).put("chapterId", image.chapterId)
                .put("componentIndex", image.componentIndex).put("heightPx", image.heightPx)
                .put("uptime", image.uptime))
        }
        // Serialize only on the receiver worker, never on the UI draw path.
        val json = JSONObject().put("gates", gateJson).put("frames", frameJson)
            .put("imageSizes", imageJson)
            .put("dropped", droppedCount).toString()
        return "probe-json=" + Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
