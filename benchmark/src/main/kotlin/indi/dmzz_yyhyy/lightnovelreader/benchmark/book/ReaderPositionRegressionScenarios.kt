package indi.dmzz_yyhyy.lightnovelreader.benchmark.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderPositionRegressionScenarios : ReaderRegressionTestCase() {
    @Test
    fun pagedReaderResolvesAndTurnsPagesWithFractionalDensityAndMargins() {
        val previousOverride = Regex("Override density: (\\d+)")
            .find(shell("wm density"))?.groupValues?.get(1)
        try {
            // At 1.5 px/dp, rounding the two 1 dp edges together loses one pixel.
            shell("wm density 240")
            val densityOutput = shell("wm density")
            val effectiveDensity = (Regex("Override density: (\\d+)").find(densityOutput)
                ?: Regex("Physical density: (\\d+)").find(densityOutput))?.groupValues?.get(1)?.toInt()
            assertEquals("Test requires an effective density of 240 dpi", 240, effectiveDensity)
            device.waitForIdle()
            openReaderWithControls()
            tapText("Settings")
            tapText("Margins")
            tapText("Auto Margin Adjustment")
            setReaderMargin("Left Margin", 1f)
            setReaderMargin("Right Margin", 1f)
            tapText("Controls")
            tapText("Page Turn Mode")
            pressBack()
            assertTrue(device.wait(Until.gone(By.text("Reader Settings")), UI_TIMEOUT))
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            device.waitForIdle()

            val firstPage = flipPage()
            swipePage(forward = true)
            assertNotEquals(
                "Fractional-density margins must allow pagination and normal page turns",
                firstPage,
                waitForDifferentPage(firstPage),
            )
        } catch (failure: Throwable) {
            // Density restoration can recreate the activity, so preserve the failing UI first.
            val context = InstrumentationRegistry.getInstrumentation().context
            runCatching {
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "reader-fractional-padding.xml"))
            }
            throw failure
        } finally {
            shell("wm density ${previousOverride ?: "reset"}")
            device.waitForIdle()
        }
    }

    @Test
    fun pagedHistoryUsesNewScrollProgressAfterSwitchingModeInAnotherChapter() {
        openBook()
        tapScrolledText("Benchmark Chapter One")
        visibleTextContaining("Benchmark progress paragraph")
        enablePageMode()
        waitForChapter("benchmark-chapter-1")
        val earlyPage = flipPage()
        waitForPersistedChapterLocationHash("benchmark-chapter-1")
        val earlyProgress = persistedChapterProgress()
        val earlyParagraph = paragraphNumber(centeredText("Benchmark progress paragraph"))

        device.click(device.displayWidth / 2, device.displayHeight / 2)
        tapText("Settings")
        tapText("Controls")
        tapText("Page Turn Mode")
        pressBack()
        assertTrue(device.wait(Until.gone(By.text("Reader Settings")), UI_TIMEOUT))
        visibleDescription("Book position")
        swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.38f)
        waitForChapterProgressInRange(0.65f, 0.90f)
        val scrollProgress = persistedChapterProgress()
        assertTrue("The scroll must move well beyond the old page", scrollProgress > earlyProgress + 0.3f)
        assertTrue(
            "The visible text must advance along with persisted progress",
            paragraphNumber(centeredText("Benchmark progress paragraph")) > earlyParagraph + 5,
        )

        // Change modes in another chapter so reopening chapter one must use persisted
        // history. A direct mode switch in chapter one would use an Exact anchor instead.
        tapText("Contents")
        visibleText("Select Chapter")
        tap(scrollToText("Benchmark Bonus Volume"))
        tap(scrollToText("Benchmark Chapter Two"))
        visibleTextContaining("Benchmark chapter two progress paragraph")
        if (!device.hasObject(By.text("Settings"))) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        tapText("Settings")
        tapText("Controls")
        tapText("Page Turn Mode")
        pressBack()
        assertTrue(device.wait(Until.gone(By.text("Reader Settings")), UI_TIMEOUT))
        waitForChapter("benchmark-chapter-2")
        tapText("Contents")
        visibleText("Select Chapter")
        tap(scrollToText("Benchmark Volume"))
        tap(scrollToText("Benchmark Chapter One"))
        waitForChapter("benchmark-chapter-1")

        assertNotEquals("Old page history must not override newer scrolling progress", earlyPage, flipPage())
        waitForChapterProgressInRange(scrollProgress - 0.10f, scrollProgress + 0.10f)
        assertTrue(
            "History restoration must show the later text, not only preserve the saved number",
            paragraphNumber(centeredText("Benchmark progress paragraph")) > earlyParagraph + 5,
        )
    }
}
