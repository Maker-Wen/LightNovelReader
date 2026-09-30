package indi.dmzz_yyhyy.lightnovelreader.benchmark.book

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.ReaderSeekLoadingFixture
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.UiAutomatorTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

/** Measures the native popup while an injected pointer stays down across chapter titles. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderPopupTest : UiAutomatorTest() {
    @Test fun lightPopupKeepsBoundsAcrossTitlesAndPercentages() = checkPopup("light")

    @Test fun darkPopupKeepsBoundsAcrossTitlesAndPercentages() = checkPopup("dark", dark = true)

    @Test fun largeTextPopupKeepsBoundsAcrossTitlesAndPercentages() =
        checkPopup("large-text", fontScale = 2f)

    @Test fun narrowDarkLargeTextPopupKeepsBoundsAcrossTitlesAndPercentages() =
        checkPopup("narrow-dark-large-text", dark = true, fontScale = 2f, widthDp = 320)

    @Test fun smallWindowPopupShrinksAndKeepsBoundsAcrossTitlesAndPercentages() =
        checkPopup("small-window-large-text", fontScale = 2f, widthDp = 280)

    @Test fun landscapeLargeTextPopupKeepsBoundsAcrossTitlesAndPercentages() =
        checkPopup("landscape-large-text", fontScale = 2f, landscape = true)

    private fun checkPopup(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        widthDp: Int? = null,
        landscape: Boolean = false,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFontScale = shell("settings get system font_scale").trim()
        val previousNight = shell("cmd uimode night").substringAfter("Night mode: ").trim()
        require(previousNight.matches(Regex("[a-z_]+"))) { "Unrecognized night mode: $previousNight" }
        val previousSize = Regex("Override size: (\\d+x\\d+)")
            .find(shell("wm size"))?.groupValues?.get(1)
        val previousAutoRotate = shell("settings get system accelerometer_rotation").trim()
        val previousUserRotation = shell("settings get system user_rotation").trim()
        val previousRotation = device.displayRotation
        val densityOutput = shell("wm density")
        val densityDpi = (Regex("Override density: (\\d+)").find(densityOutput)
            ?: Regex("Physical density: (\\d+)").find(densityOutput))
            ?.groupValues?.get(1)?.toInt() ?: error("Missing density: $densityOutput")
        val density = densityDpi / 160f
        val context = InstrumentationRegistry.getInstrumentation().context
        val evidence = File(requireNotNull(context.getExternalFilesDir("reader-popup")), name).apply { mkdirs() }
        val samples = JSONArray()
        val report = JSONObject().put("scenario", name).put("fontScale", fontScale)
            .put("dark", dark).put("widthDp", widthDp ?: JSONObject.NULL).put("landscape", landscape)
            .put("densityDpi", densityDpi).put("samples", samples)
        var primaryFailure: Throwable? = null
        try {
            shell("settings put system font_scale $fontScale")
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            if (widthDp != null) shell("wm size ${(widthDp * density).roundToInt()}x${(720 * density).roundToInt()}")
            // Enter the reader in portrait: the bookshelf's large-text landscape layout is
            // a separate usability case and must not prevent measuring the reader popup.
            assertTrue("Could not set the initial portrait orientation",
                automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
            shell("am force-stop $TARGET_PACKAGE")
            val seed = shell(
                "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                    "-a $SEED_ACTION --ez popupTitles true",
            )
            assertTrue("Popup title fixture was not seeded: $seed", seed.contains("seed=SUCCEEDED"))
            launchApp()
            openBottomNavigation("Bookshelf")
            clickScrolledText("Benchmark Sample Novel")
            clickScrolledText(SHORT_TITLE)
            assertTextContains("Benchmark progress paragraph")
            if (landscape) {
                assertTrue("Could not rotate the reader to landscape",
                    automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                val rotationDeadline = SystemClock.uptimeMillis() + TIMEOUT
                while (device.displayWidth <= device.displayHeight && SystemClock.uptimeMillis() < rotationDeadline) {
                    SystemClock.sleep(50)
                }
                assertTrue("Reader did not enter landscape", device.displayWidth > device.displayHeight)
                assertTextContains("Benchmark progress paragraph")
                assertTrue("Rotation must close the previous reader menu",
                    device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
            }
            if (!device.hasObject(By.desc("Book position"))) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
            }
            val track = stableBounds("Book position", description = true)
            val anchor = stableBounds(ANCHOR_TAG)
            report.put("track", track.toJson()).put("anchor", anchor.toJson())
                .put("displayWidth", device.displayWidth).put("displayHeight", device.displayHeight)
            assertEquals("Requested font scale was not applied", fontScale,
                shell("settings get system font_scale").trim().toFloat(), 0.001f)
            if (widthDp != null) assertTrue("Viewport must be no wider than $widthDp dp",
                device.displayWidth / density <= widthDp + 1f)
            assertEquals("Orientation must match the scenario", landscape, device.displayWidth > device.displayHeight)

            // The fixture's chapter 6/10 gates do not match either title-fixture chapter.
            ReaderSeekLoadingFixture(::shell, "popup-$name").use { fixture ->
                // Start away from the initial thumb so its grab offset cannot shift 100%.
                fixture.beginPointer(track, 0.5f).use { pointer ->
                    pointer.moveTo(0.2f)
                    awaitPopupPreview(0.2f)
                    val initialFrame = fixture.awaitDraw("popup preview with chapter-start origin") { it.previewing }
                    report.put("gestureOrigin", initialFrame.gestureOrigin ?: JSONObject.NULL)
                        .put("formalOrigin", initialFrame.formalOrigin ?: JSONObject.NULL)
                    assertEquals("Popup fixture must start at the beginning of the book", 0f,
                        requireNotNull(initialFrame.gestureOrigin), 0.001f)
                    assertTrue("First popup drag must not have a formal return position", initialFrame.formalOrigin == null)
                    val baseline = stableBounds(POPUP_TAG)
                    if (widthDp == 280) assertTrue("Small-window popup must shrink below its 256 dp cap",
                        baseline.width() / density < 256f)
                    val seenTitles = mutableSetOf<String>()
                    val seenPercentages = mutableSetOf<String>()
                    // 5% is beyond the origin's snap radius, including the smallest viewport.
                    val fractions = listOf(0f, 0.05f, 0.099f, 0.1f, 0.25f, 0.499f, 0.5f,
                        0.51f, 0.75f, 0.999f, 1f, 0.9f, 0.7f, 0.5f, 0.1f, 0.05f, 0f, 0.6f)
                    fractions.forEachIndexed { index, fraction ->
                        pointer.moveTo(fraction, steps = 1)
                        val preview = awaitPopupPreview(fraction)
                        val bounds = preview.bounds
                        val titleText = preview.title
                        val percentageText = preview.percentage
                        seenTitles += titleText
                        seenPercentages += percentageText
                        samples.put(JSONObject().put("fraction", fraction).put("uptime", SystemClock.uptimeMillis())
                            .put("popup", bounds.toJson()).put("title", titleText).put("percentage", percentageText))
                        assertRectNear("Popup must keep all four edges while dragging", baseline, bounds)
                        assertTrue("Popup must remain centered over the bottom bar: $bounds / $anchor",
                            abs(bounds.exactCenterX() - anchor.exactCenterX()) <= 1f)
                        assertTrue("Popup must retain a 14 dp gap above the bar: $bounds / $anchor",
                            abs(anchor.top - bounds.bottom - (14 * density).roundToInt()) <= 1)
                        assertTrue("Popup must stay within the display", bounds.left >= 0 && bounds.top >= 0 &&
                            bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
                        assertTrue("Title must fit within the popup", bounds.contains(preview.titleBounds))
                        assertTrue("Percentage must fit within the popup", bounds.contains(preview.percentageBounds))
                        if (index == 0 || fraction == 1f) {
                            assertTrue("Could not save popup screenshot", device.takeScreenshot(
                                File(evidence, if (index == 0) "short-title.png" else "long-title.png"),
                            ))
                        }
                    }
                    assertTrue("Drag must cover the short title", SHORT_TITLE in seenTitles)
                    assertTrue("Drag must cover the long title", LONG_TITLE in seenTitles)
                    assertTrue("Drag must cover both percentage endpoints: $seenPercentages",
                        "0.0%" in seenPercentages && "100.0%" in seenPercentages)
                    pointer.finish()
                }
                assertTrue("UP must dismiss the popup", device.wait(Until.gone(By.res(POPUP_TAG)), TIMEOUT))
                // close() injects ACTION_CANCEL rather than committing the preview.
                fixture.beginPointer(track, 0.9f).use { pointer ->
                    pointer.moveTo(0.25f)
                    awaitPopupPreview(0.25f)
                }
                assertTrue("CANCEL must dismiss the popup", device.wait(Until.gone(By.res(POPUP_TAG)), TIMEOUT))
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { report.put("passed", false).put("failure", failure.stackTraceToString()) }
                .onFailure { failure.addSuppressed(it) }
            runCatching { assertTrue("Could not save failure screenshot", device.takeScreenshot(File(evidence, "failure.png"))) }
                .onFailure { failure.addSuppressed(it) }
            runCatching { device.dumpWindowHierarchy(File(evidence, "failure.xml")) }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                }
            }
            cleanup {
                shell("wm size ${previousSize ?: "reset"}")
                val restoredSize = Regex("Override size: (\\d+x\\d+)")
                    .find(shell("wm size"))?.groupValues?.get(1)
                assertEquals("Display size override was not restored", previousSize, restoredSize)
            }
            cleanup { restoreSystemSetting("font_scale", previousFontScale) }
            cleanup {
                shell("cmd uimode night $previousNight")
                assertEquals("Night mode was not restored", previousNight,
                    shell("cmd uimode night").substringAfter("Night mode: ").trim())
            }
            cleanup { assertTrue("Could not restore rotation", automation.setRotation(previousRotation)) }
            cleanup {
                if (previousAutoRotate == "1") assertTrue("Could not unfreeze rotation",
                    automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
            }
            cleanup { restoreSystemSetting("accelerometer_rotation", previousAutoRotate) }
            cleanup { restoreSystemSetting("user_rotation", previousUserRotation) }
            cleanup { device.waitForIdle() }
            cleanup {
                cleanupFailure?.let { report.put("passed", false).put("cleanupFailure", it.stackTraceToString()) }
                File(evidence, "bounds.json").writeText(report.toString(2))
            }
            cleanupFailure?.let { failure ->
                val primary = primaryFailure
                if (primary == null) throw failure else primary.addSuppressed(failure)
            }
        }
    }

    private data class PopupPreview(
        val bounds: Rect,
        val title: String,
        val titleBounds: Rect,
        val percentage: String,
        val percentageBounds: Rect,
    )

    private fun awaitPopupPreview(fraction: Float): PopupPreview {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var lastPercentage: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val popup = device.findObject(By.res(POPUP_TAG))
                val percentage = popup?.findObject(By.text(Pattern.compile("[0-9]+\\.[0-9]%")))
                lastPercentage = percentage?.text
                val value = lastPercentage?.removeSuffix("%")?.toFloatOrNull()
                if (value != null && abs(value - fraction * 100) <= 0.2f) {
                    val title = popup.findObject(By.text(Pattern.compile("短章|这是用于验证.*")))
                    if (title != null && percentage != null) {
                        return PopupPreview(Rect(popup.visibleBounds), title.text, Rect(title.visibleBounds),
                            requireNotNull(lastPercentage), Rect(percentage.visibleBounds))
                    }
                }
            } catch (_: StaleObjectException) {
                // Each retry finds fresh nodes after the preview's recomposition.
            }
            SystemClock.sleep(25)
        }
        error("Popup did not show ${fraction * 100}% within 0.2 percentage points; last=$lastPercentage")
    }

    private fun stableBounds(value: String, description: Boolean = false): Rect {
        val selector = if (description) By.desc(value) else By.res(value)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var previous: Rect? = null
        var samples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val current = try { device.findObject(selector)?.visibleBounds?.takeUnless { it.isEmpty } }
                catch (_: StaleObjectException) { null }
            samples = if (current != null && current == previous) samples + 1 else 0
            previous = current?.let { Rect(it) }
            if (samples >= 2) return requireNotNull(previous)
            SystemClock.sleep(100)
        }
        error("No stable visible bounds for $value: $previous")
    }

    private fun assertRectNear(message: String, expected: Rect, actual: Rect) {
        assertTrue("$message: expected=$expected actual=$actual",
            abs(expected.left - actual.left) <= 1 && abs(expected.top - actual.top) <= 1 &&
                abs(expected.right - actual.right) <= 1 && abs(expected.bottom - actual.bottom) <= 1)
    }

    private fun restoreSystemSetting(key: String, previous: String) {
        if (previous == "null") shell("settings delete system $key")
        else {
            require(previous.matches(Regex("[0-9.]+"))) { "Unrecognized saved $key: $previous" }
            shell("settings put system $key $previous")
        }
        assertEquals("System setting $key was not restored", previous,
            shell("settings get system $key").trim())
    }

    private fun Rect.toJson() = JSONArray(listOf(left, top, right, bottom))

    companion object {
        private const val POPUP_TAG = "reader-progress-popup"
        private const val ANCHOR_TAG = "reader-progress-popup-anchor"
        private const val SHORT_TITLE = "短章"
        private const val LONG_TITLE = "这是用于验证阅读进度浮窗宽高与居中位置在超长章节名称下保持稳定的第二章标题"
    }
}
