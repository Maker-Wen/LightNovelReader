package indi.dmzz_yyhyy.lightnovelreader.benchmark.book

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import indi.dmzz_yyhyy.lightnovelreader.benchmark.framework.ReaderTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/** Progress and exact-position probes retained from the branch's reader regressions. */
abstract class ReaderRegressionTestCase : ReaderTestCase() {
    protected fun persistedChapterProgress(
        chapterId: String = "benchmark-chapter-1",
        maximum: Boolean = false,
    ): Float {
        val output = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.REPORT_PROGRESS --es chapterId $chapterId"
        )
        val field = if (maximum) "maxProgress" else "progress"
        return Regex("$field=([0-9.-]+)").find(output)
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
            ?: error("Progress result was missing from: $output")
    }

    protected fun assertCompletedChapterProgress(chapterId: String, maximum: Boolean = false) {
        repeat(40) {
            if (persistedChapterProgress(chapterId, maximum) == 1f) return
            SystemClock.sleep(250)
        }
        assertEquals("Chapter completion must be persisted", 1f, persistedChapterProgress(chapterId, maximum))
    }

    protected fun waitForPersistedChapterLocationHash(chapterId: String): Int {
        repeat(40) {
            val output = shell(
                "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                    "-a $TARGET_PACKAGE.benchmark.REPORT_PROGRESS --es chapterId $chapterId"
            )
            Regex("locationHash=(-?\\d+)").find(output)?.groupValues?.get(1)?.toInt()?.let {
                return it
            }
            SystemClock.sleep(250)
        }
        error("No page location hash was persisted for $chapterId")
    }

    protected fun setReaderMargin(title: String, value: Float) {
        scrollToText(title)
        fun findSliders(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (node.rangeInfo != null &&
                node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { addAll(findSliders(it)) }
            }
        }
        fun containsTitle(node: AccessibilityNodeInfo, text: String): Boolean {
            if (node.text?.toString() == text) return true
            return (0 until node.childCount).any { index ->
                node.getChild(index)?.let { containsTitle(it, text) } == true
            }
        }
        fun slider(): AccessibilityNodeInfo {
            // UiAutomator walks Compose's virtual nodes; Android's text lookup is not
            // implemented by every virtual AccessibilityNodeProvider.
            var node: AccessibilityNodeInfo? = visibleText(title).accessibilityNodeInfo
            while (node != null) {
                val container = node
                val candidates = findSliders(container)
                if (candidates.isNotEmpty()) {
                    assertEquals("$title must have its own slider container", 1, candidates.size)
                    val otherMargins = listOf("Top Margin", "Bottom Margin", "Left Margin", "Right Margin") - title
                    assertTrue(
                        "$title slider lookup must not include another margin setting",
                        otherMargins.none { containsTitle(container, it) },
                    )
                    return candidates.single()
                }
                node = container.parent
            }
            error("No adjustable slider found for $title")
        }
        assertTrue(
            "Could not set $title to $value dp",
            slider().performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) },
            ),
        )
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            if (slider().rangeInfo.current == value) return
            SystemClock.sleep(50)
        }
        assertEquals("Unexpected $title value", value, slider().rangeInfo.current, 0f)
    }

    protected fun openReaderWithControls() {
        openBook()
        tapScrolledText("Benchmark Chapter One")
        visibleTextContaining("Benchmark progress paragraph")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        visibleDescription("Book position")
    }

    protected fun seedSeekFixture(
        paragraphCount: Int,
        singleChapter: Boolean = false,
        imageUri: String? = null,
    ) {
        // URI is generated by the loopback fixture and contains no shell metacharacters.
        val imageArgument = imageUri?.let { " --es imageUri $it" }.orEmpty()
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.SEED --ei paragraphCount $paragraphCount --ez singleChapter $singleChapter$imageArgument"
        )
        assertTrue("Seek fixture was not seeded: $result", result.contains("seed=SUCCEEDED"))
    }

    protected fun swipeBookFraction(track: Rect, fraction: Float, steps: Int = 30) {
        device.swipe(
            track.centerX(), track.centerY(),
            track.left + (track.width() * fraction).toInt(), track.centerY(), steps,
        )
    }

    protected fun waitForChapterProgressInRange(minimum: Float, maximum: Float) {
        repeat(40) {
            if (persistedChapterProgress() in minimum..maximum) return
            SystemClock.sleep(250)
        }
        assertTrue(
            "Expected chapter-one progress in $minimum..$maximum, got ${persistedChapterProgress()}",
            persistedChapterProgress() in minimum..maximum,
        )
    }

    protected fun waitForStableTextBounds(text: String, expected: Rect) {
        var stableSamples = 0
        var actual: Rect? = null
        repeat(40) {
            actual = try {
                device.findObjects(By.text(text))
                    .map { it.visibleBounds }
                    .firstOrNull { it.height() > 0 }
            } catch (_: StaleObjectException) {
                null
            }
            val bounds = actual
            val matches = bounds != null &&
                abs(bounds.top - expected.top) <= 2 && abs(bounds.bottom - expected.bottom) <= 2 &&
                abs(bounds.left - expected.left) <= 2 && abs(bounds.right - expected.right) <= 2
            stableSamples = if (matches) stableSamples + 1 else 0
            if (stableSamples >= 3) return
            SystemClock.sleep(100)
        }
        assertTrue("Image growth lost the seek anchor $text: expected=$expected, actual=$actual", false)
    }

    protected fun paragraphNumber(text: String): Int =
        Regex("Benchmark progress paragraph (\\d+)\\.").find(text)
            ?.groupValues?.get(1)?.toInt()
            ?: error("Missing fixture paragraph number: $text")
}
