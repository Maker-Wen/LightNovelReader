package indi.dmzz_yyhyy.lightnovelreader.benchmark.ui

import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Drives actual touch input while benchmark-only repository gates hold chapter responses. */
class ReaderSeekLoadingFixture(
    private val shell: (String) -> String,
    private val evidenceName: String,
) : Closeable {
    data class MarkerDraw(
        val sequence: Long,
        val uptime: Long,
        val value: Float,
        val displayedOrigin: Float?,
        val formalOrigin: Float?,
        val gestureOrigin: Float?,
        val previewing: Boolean,
        val enabled: Boolean,
        val trackWidth: Float,
        val markerX: Float?,
    )

    val frames = mutableListOf<MarkerDraw>()
    private var gates = JSONObject()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val density = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
    private var activePointer: Closeable? = null
    private val inputEvents = JSONArray()
    private var imageSizes = JSONArray()
    private val imageMeasurements = JSONArray()

    init {
        val result = command("arm --es bookId 9999999 --es chapterIds $CHAPTER_A,$CHAPTER_B")
        assertTrue("Failed to arm deterministic chapter loads: $result", result.contains("probe=ARMED"))
    }

    fun release(chapterId: String) {
        recordEvent("release $chapterId")
        val result = command("release --es chapterId $chapterId")
        assertTrue("Failed to release $chapterId: $result", result.contains("probe=RELEASED"))
    }

    fun drain() {
        val output = command("drain")
        val encoded = Regex("probe-json=([A-Za-z0-9+/=]+)").find(output)?.groupValues?.get(1)
            ?: error("Missing seek probe report: $output")
        val report = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        assertEquals("The draw probe must not lose frames", 0, report.getInt("dropped"))
        gates = report.getJSONObject("gates")
        val images = report.getJSONArray("imageSizes")
        if (images.toString() != imageSizes.toString()) {
            imageMeasurements.put(JSONObject().put("observedUptime", SystemClock.uptimeMillis()).put("images", images))
        }
        imageSizes = images
        val batch = report.getJSONArray("frames")
        repeat(batch.length()) { index ->
            val frame = batch.getJSONObject(index)
            fun nullableFloat(key: String): Float? =
                if (frame.isNull(key)) null else frame.getDouble(key).toFloat()
            frames += MarkerDraw(
                frame.getLong("sequence"), frame.getLong("uptime"), frame.getDouble("value").toFloat(),
                nullableFloat("displayedOrigin"), nullableFloat("formalOrigin"), nullableFloat("gestureOrigin"),
                frame.getBoolean("previewing"), frame.getBoolean("enabled"),
                frame.getDouble("trackWidth").toFloat(), nullableFloat("markerX"),
            )
        }
    }

    /** Waits for actual Compose measurements at the current reading position, without another seek. */
    fun awaitStableImageHeight(chapterId: String, componentIndex: Int, minimumExclusive: Int = 0): Int {
        var previousHeight: Int? = null
        var stableSamples = 0
        repeat(100) {
            drain()
            val image = (0 until imageSizes.length()).map { imageSizes.getJSONObject(it) }
                .firstOrNull { it.getString("bookId") == "9999999" &&
                    it.getString("chapterId") == chapterId && it.getInt("componentIndex") == componentIndex }
            val height = image?.getInt("heightPx")
            stableSamples = if (height != null && height > minimumExclusive) {
                if (height == previousHeight) stableSamples + 1 else 1
            } else 0
            if (stableSamples >= 3) {
                recordEvent("image $chapterId/$componentIndex stable height=$height, minimumExclusive=$minimumExclusive")
                return requireNotNull(height)
            }
            previousHeight = height
            SystemClock.sleep(100)
        }
        error("Image $chapterId/$componentIndex did not settle above $minimumExclusive px: $imageSizes")
    }

    fun awaitGate(chapterId: String, field: String, minimum: Int = 1) {
        repeat(100) {
            drain()
            if (gates.getJSONObject(chapterId).getInt(field) >= minimum) return
            SystemClock.sleep(50)
        }
        error("Chapter gate did not reach $field >= $minimum: $chapterId, $gates")
    }

    fun assertHeld(chapterId: String) {
        drain()
        val gate = gates.getJSONObject(chapterId)
        assertTrue("A real $chapterId load must be waiting: $gate", gate.getInt("active") > 0)
        assertTrue("$chapterId must not have been released: $gate", !gate.getBoolean("released"))
        assertEquals("A held request must not deliver content", 0, gate.getInt("passed"))
    }

    fun assertSinglePendingLoad(chapterId: String) {
        drain()
        val gate = gates.getJSONObject(chapterId)
        assertEquals("Retargeting within a pending chapter must reuse its flow: $gate", 1, gate.getInt("entered"))
        assertEquals("Exactly one chapter load must remain active: $gate", 1, gate.getInt("active"))
        assertEquals("Retargeting must not cancel the shared chapter load: $gate", 0, gate.getInt("cancelled"))
        assertEquals("The held chapter must not deliver content", 0, gate.getInt("passed"))
        assertTrue("The chapter gate must still be held", !gate.getBoolean("released"))
    }

    fun awaitDraw(label: String, since: Int = 0, predicate: (MarkerDraw) -> Boolean): MarkerDraw {
        repeat(100) {
            drain()
            frames.drop(since).lastOrNull(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("No Compose draw for $label; recent=${frames.takeLast(8)}")
    }

    fun beginPointer(track: Rect, startFraction: Float): HeldPointer {
        check(activePointer == null)
        return HeldPointer(track, startFraction).also { activePointer = it }
    }

    fun recordEvent(label: String): Long = SystemClock.uptimeMillis().also { uptime ->
        inputEvents.put(JSONObject().put("label", label).put("uptime", uptime))
    }

    fun beginTouch(x: Float, y: Float, syncInput: Boolean = true): HeldTouch {
        check(activePointer == null)
        return HeldTouch(x, y, syncInput).also { activePointer = it }
    }

    fun tapAt(x: Float, y: Float, pressDurationMs: Long = 80, syncInput: Boolean = true): Long =
        beginTouch(x, y, syncInput).use { touch ->
            // This is the real pointer press duration, not a UI-settling delay.
            SystemClock.sleep(pressDurationMs)
            touch.finish()
        }

    fun tapAfter(
        previousUp: Long,
        delayMs: Long,
        x: Float,
        y: Float,
        pressDurationMs: Long = 80,
        syncInput: Boolean = true,
        maximumDelaySlipMs: Long? = null,
    ): Long {
        val remaining = previousUp + delayMs - SystemClock.uptimeMillis()
        if (remaining > 0) SystemClock.sleep(remaining)
        recordEvent("tap scheduled ${delayMs}ms after UP=$previousUp")
        return beginTouch(x, y, syncInput).use { touch ->
            maximumDelaySlipMs?.let { maximumSlip ->
                val actualDelay = touch.downUptime - previousUp
                assertTrue(
                    "Actual DOWN missed the ${delayMs}..${delayMs + maximumSlip}ms window: ${actualDelay}ms",
                    actualDelay in delayMs..(delayMs + maximumSlip),
                )
            }
            SystemClock.sleep(pressDurationMs)
            touch.finish()
        }
    }

    inner class HeldTouch(private val x: Float, private val y: Float, private val syncInput: Boolean) : Closeable {
        private val downTime = SystemClock.uptimeMillis()
        private var held = true
        val downUptime = injectTouch(MotionEvent.ACTION_DOWN, downTime, x, y, syncInput)

        fun finish(): Long {
            check(held)
            val uptime = injectTouch(MotionEvent.ACTION_UP, downTime, x, y, syncInput)
            held = false
            activePointer = null
            return uptime
        }

        override fun close() {
            if (held) {
                try { injectTouch(MotionEvent.ACTION_CANCEL, downTime, x, y, syncInput) } finally {
                    held = false
                    activePointer = null
                }
            }
        }
    }

    inner class HeldPointer(private val track: Rect, startFraction: Float) : Closeable {
        private val downTime = SystemClock.uptimeMillis()
        private var fraction = startFraction
        private var held = true

        init { inject(MotionEvent.ACTION_DOWN, fraction) }

        fun moveTo(target: Float, steps: Int = 8) {
            check(held)
            val start = fraction
            repeat(steps) { index ->
                fraction = start + (target - start) * (index + 1) / steps
                inject(MotionEvent.ACTION_MOVE, fraction)
                // Keep the pointer held across display frames and across repository release.
                SystemClock.sleep(20)
            }
        }

        fun finish(sync: Boolean = true): Long {
            check(held)
            val uptime = inject(MotionEvent.ACTION_UP, fraction, sync)
            held = false
            activePointer = null
            return uptime
        }

        private fun inject(action: Int, progress: Float, sync: Boolean = true): Long {
            val inset = 24f * density
            val x = track.left + inset + (track.width() - inset * 2) * progress
            return injectTouch(action, downTime, x, track.exactCenterY(), sync)
        }

        override fun close() {
            if (held) {
                try { inject(MotionEvent.ACTION_CANCEL, fraction) } finally {
                    held = false
                    activePointer = null
                }
            }
        }
    }

    private fun injectTouch(action: Int, downTime: Long, x: Float, y: Float, sync: Boolean = true): Long {
        val uptime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, uptime, action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            // Async injection only queues ordered events. Timing-critical callers must still
            // verify completion with the repository gate and rendered/semantic state fences.
            assertTrue("Could not inject touch action $action", automation.injectInputEvent(event, sync))
            inputEvents.put(JSONObject().put("action", MotionEvent.actionToString(action))
                .put("uptime", uptime).put("x", x).put("y", y).put("sync", sync))
        } finally {
            event.recycle()
        }
        return uptime
    }

    private fun command(operation: String): String = shell(
        "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
            "-a $TARGET_PACKAGE.benchmark.SEEK_PROBE --es operation $operation"
    )

    override fun close() {
        try {
            activePointer?.close()
            drain()
        } finally {
            try { command("reset") } finally { saveEvidence() }
        }
    }

    private fun saveEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = requireNotNull(context.getExternalFilesDir("seek-marker"))
        val frameJson = JSONArray()
        frames.forEach { frame ->
            frameJson.put(JSONObject()
                .put("sequence", frame.sequence).put("uptime", frame.uptime).put("value", frame.value)
                .put("displayedOrigin", frame.displayedOrigin ?: JSONObject.NULL)
                .put("formalOrigin", frame.formalOrigin ?: JSONObject.NULL)
                .put("gestureOrigin", frame.gestureOrigin ?: JSONObject.NULL)
                .put("previewing", frame.previewing).put("enabled", frame.enabled)
                .put("trackWidth", frame.trackWidth).put("markerX", frame.markerX ?: JSONObject.NULL))
        }
        val file = File(directory, "$evidenceName.json")
        file.writeText(JSONObject().put("gates", gates).put("frames", frameJson).put("inputEvents", inputEvents)
            .put("imageMeasurements", imageMeasurements).toString(2))
        Log.i("ReaderSeekProbe", "Saved ${frames.size} Compose draws: ${file.absolutePath}")
    }

    companion object {
        private const val TARGET_PACKAGE = "indi.dmzz_yyhyy.lightnovelreader"
        const val CHAPTER_A = "benchmark-chapter-6"
        const val CHAPTER_B = "benchmark-chapter-10"
        const val FRACTION_A = 5.5f / 12f
        const val FRACTION_B = 9.5f / 12f
    }
}
