package indi.dmzz_yyhyy.lightnovelreader.benchmark.performance

import android.graphics.Rect
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.UiAutomatorTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * Local-fixture reader scenarios, measured independently of startup and navigation.
 *
 * The inherited @Before clears and seeds the app once per test. Iterations only restart it,
 * retaining the compilation produced by one warmup iteration. Override the default three
 * measured iterations with the instrumentation argument readerFrameIterations.
 *
 * Emulator results are diagnostic samples, not physical-device performance thresholds.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderFrameTimingBenchmark : UiAutomatorTest() {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private var trackBounds = Rect()

    @Test
    fun scrollingReader() = measureReader {
        scrollBackAndForth()
        assertTextContains("Benchmark progress paragraph")
    }

    @Test
    fun expandedBookScrollingReader() {
        // This fixture has twelve chapters of thirty paragraphs each, not a longer single chapter.
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.EXTEND_RAPID_CHAPTER_CHAIN"
        )
        assertTrue("Extended fixture was not seeded: $result", result.contains("rapid-chapters=SUCCEEDED"))
        measureReader(
            chapter = "Benchmark Chapter 6",
            paragraphPrefix = "Benchmark rapid chapter 6 paragraph",
        ) {
            scrollBackAndForth()
            assertTextContains("Benchmark rapid chapter 6 paragraph")
        }
    }

    @Test
    fun longChapterScrollingReader() {
        seedLongChapters()
        measureReader {
            scrollBackAndForth()
            assertTextContains("Benchmark progress paragraph")
        }
    }

    @Test
    fun scrollingProgressDragAndRelease() = measureReader(controls = true) {
        dragProgressBackAndForth()
    }

    @Test
    fun longChapterProgressDragAndRelease() {
        seedLongChapters()
        measureReader(controls = true) {
            dragProgressBackAndForth()
        }
    }

    @Test
    fun sameChapterHotSeek() = measureSameChapterHotSeek(paragraphCount = 30)

    @Test
    fun longChapterSameChapterHotSeek() = measureSameChapterHotSeek(paragraphCount = 300)

    @Test
    fun pagedProgressDragAndRelease() {
        launchApp()
        navigateToReader("Benchmark Chapter One", "Benchmark progress paragraph")
        showControls()
        clickText("Settings")
        clickText("Controls")
        clickText("Page Turn Mode")
        assertText("Volume Key Navigation")
        pressBack()
        measureReader(controls = true, paged = true) {
            dragProgressBackAndForth()
        }
    }

    @Test
    fun longChapterPagedProgressWithTextProcessing() {
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $SEED_ACTION --ei paragraphCount 300 --ez simplifiedTraditional true --ez paged true"
        )
        assertTrue("Text processing fixture was not seeded: $result", result.contains("seed=SUCCEEDED"))
        measureReader(
            controls = true,
            paged = true,
            prepareActions = {
                // This sentinel proves the real configured processor ran, independently of locale.
                assertTextContains("漢語測試")
            },
        ) {
            dragProgressBackAndForth()
            assertTextContains("漢語測試")
        }
    }

    @Test
    fun readerMenuOpenAndClose() = measureReader {
        repeat(6) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            SystemClock.sleep(300)
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            SystemClock.sleep(300)
        }
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        SystemClock.sleep(300)
        assertDescription("Book position")
    }

    private fun measureReader(
        chapter: String = "Benchmark Chapter One",
        paragraphPrefix: String = "Benchmark progress paragraph",
        controls: Boolean = false,
        paged: Boolean = false,
        prepareActions: (() -> Unit)? = null,
        actions: () -> Unit,
    ) {
        val iterations = InstrumentationRegistry.getArguments()
            .getString("readerFrameIterations")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 3
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Disable,
                warmupIterations = 1,
            ),
            iterations = iterations,
            setupBlock = {
                killProcess()
                startActivityAndWait()
                navigateToReader(chapter, paragraphPrefix)
                if (paged) {
                    assertTrue(
                        "Page turn mode must be active before measuring",
                        device.hasObject(By.res(Pattern.compile(".*flip-page-.*"))),
                    )
                }
                if (controls) {
                    showControls()
                    trackBounds = assertDescription("Book position").visibleBounds
                    assertTrue("Progress track must have usable bounds", trackBounds.width() > 40)
                } else if (device.hasObject(By.desc("Book position"))) {
                    device.click(device.displayWidth / 2, device.displayHeight / 2)
                }
                // Navigation, text layout and entrance animations are outside the trace.
                device.waitForIdle()
                SystemClock.sleep(500)
                prepareActions?.invoke()
            },
        ) {
            actions()
        }
    }

    private fun navigateToReader(chapter: String, paragraphPrefix: String) {
        // UiAutomatorTest resets the target app to en-US independently of the device locale.
        openBottomNavigation("Bookshelf")
        clickScrolledText("Benchmark Sample Novel")
        clickScrolledText(chapter)
        assertTextContains(paragraphPrefix)
    }

    private fun seedLongChapters() {
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $SEED_ACTION --ei paragraphCount 300"
        )
        assertTrue("Long chapter fixture was not seeded: $result", result.contains("seed=SUCCEEDED"))
    }

    private fun measureSameChapterHotSeek(paragraphCount: Int) {
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $SEED_ACTION --ei paragraphCount $paragraphCount --ez singleChapter true"
        )
        assertTrue("Single chapter fixture was not seeded: $result", result.contains("seed=SUCCEEDED"))
        measureReader(
            controls = true,
            prepareActions = {
                // First navigation and complete geometry collection happen outside the trace.
                dragWithinChapter(0.20f)
                assertTextContains("Benchmark progress paragraph")
                assertDescription("Book position")
                device.waitForIdle()
                // A previous iteration ends near 75%. The setup seek would retain that old
                // position as its origin and a later 75% release could legitimately return.
                // Start a fresh origin session at 20%, after both menu transitions finish.
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                assertTrue(device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                assertDescription("Book position")
                trackBounds = waitForStableSameChapterTrack()
                assertFalse(
                    "Reopening the menu must clear the setup seek's return position",
                    device.hasObject(By.desc("Return to the position before jumping")),
                )
            },
        ) {
            repeat(5) { index ->
                // Neither endpoint overlaps the new origin, so these are seeks rather than
                // alternating seeks and the progress control's deliberate snap-to-return.
                dragWithinChapter(if (index % 2 == 0) 0.75f else 0.35f)
            }
            assertTextContains("Benchmark progress paragraph")
            assertDescription("Return to the position before jumping")
            assertDescription("Book position")
        }
    }

    private fun waitForStableSameChapterTrack(): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var lastBounds: Rect? = null
        var stableSamples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val bounds = try {
                device.findObject(By.desc("Book position"))?.visibleBounds
                    ?.takeIf { it.width() > 40 && it.height() > 0 }
            } catch (_: StaleObjectException) {
                null
            }
            stableSamples = when {
                bounds == null -> 0
                bounds == lastBounds -> stableSamples + 1
                else -> 1
            }
            lastBounds = bounds?.let { Rect(it) }
            if (stableSamples >= 3) return requireNotNull(lastBounds)
            SystemClock.sleep(100)
        }
        error("Progress track did not settle after reopening the menu: $lastBounds")
    }

    private fun dragWithinChapter(destination: Float) {
        device.swipe(
            trackBounds.centerX(),
            trackBounds.centerY(),
            progressX(destination),
            trackBounds.centerY(),
            100,
        )
        SystemClock.sleep(500)
    }

    private fun progressX(fraction: Float): Int {
        val density = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
        val inset = 24f * density
        return (trackBounds.left + inset + (trackBounds.width() - 2 * inset) * fraction).roundToInt()
    }

    private fun showControls() {
        if (!device.hasObject(By.desc("Book position"))) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        assertDescription("Book position")
    }

    private fun scrollBackAndForth() {
        val x = device.displayWidth / 2
        val top = (device.displayHeight * 0.30f).toInt()
        val bottom = (device.displayHeight * 0.75f).toInt()
        repeat(8) { index ->
            val forward = index % 2 == 0
            device.swipe(x, if (forward) bottom else top, x, if (forward) top else bottom, 45)
            SystemClock.sleep(300)
        }
    }

    private fun dragProgressBackAndForth() {
        repeat(5) { index ->
            val destination = if (index % 2 == 0) 0.90f else 0.10f
            device.swipe(
                trackBounds.centerX(),
                trackBounds.centerY(),
                progressX(destination),
                trackBounds.centerY(),
                100,
            )
            // Include the released seek and its text layout, without an accessibility idle wait
            // between gestures. The final assertions reject a benchmark that missed the track.
            SystemClock.sleep(500)
        }
        assertTextContains("Benchmark chapter two progress paragraph")
        assertDescription("Return to the position before jumping")
        assertDescription("Book position")
    }

}
