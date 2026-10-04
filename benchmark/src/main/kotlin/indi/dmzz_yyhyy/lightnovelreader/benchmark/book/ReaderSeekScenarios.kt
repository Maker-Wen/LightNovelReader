package indi.dmzz_yyhyy.lightnovelreader.benchmark.book

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.DelayedImageServer
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.ReaderSeekLoadingFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderSeekScenarios : ReaderRegressionTestCase() {
    @Test
    fun scrollingReaderSeeksAcrossVolumesAndReturnsToOriginalPosition() {
        openReaderWithControls()
        assertSeekAndReturn()
    }

    @Test
    fun scrollingReaderSameChapterSeekReturnsToExactPosition() {
        seedSeekFixture(paragraphCount = 30, singleChapter = true)
        openReaderWithControls()
        assertSameChapterSeekAndReturn()
    }

    @Test
    fun scrollingReaderRapidSeeksKeepLastTarget() {
        seedSeekFixture(paragraphCount = 300)
        openReaderWithControls()

        // Establish a visible, persisted reference using the same final gesture. Both chapter
        // weights remain frozen for this reader session, so later seeks have the same target.
        swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.30f)
        waitForChapterProgressInRange(0.40f, 0.85f)
        device.waitForIdle()
        val expectedText = centeredText("Benchmark progress paragraph")
        val expectedBounds = visibleText(expectedText).visibleBounds
        val expectedProgress = persistedChapterProgress()
        val track = visibleDescription("Book position").visibleBounds

        // No accessibility lookup or idle wait between releases: new requests can supersede
        // work from earlier cross-chapter seeks. The final destination is back in chapter one.
        listOf(0.90f, 0.10f, 0.90f, 0.10f, 0.30f).forEach { fraction ->
            swipeBookFraction(track, fraction, steps = 3)
        }

        assertEquals(
            "The final request must determine the visible paragraph",
            expectedText,
            waitForCenteredText("Benchmark progress paragraph", expectedText),
        )
        assertEquals("The final request must restore its target offset", expectedBounds, visibleText(expectedText).visibleBounds)
        waitForChapterProgressInRange(expectedProgress - 0.01f, expectedProgress + 0.01f)
        SystemClock.sleep(1_500)
        assertEquals("A superseded request must not replace the final chapter", expectedText, centeredText("Benchmark progress paragraph"))
        assertEquals("A superseded request must not replace the final offset", expectedBounds, visibleText(expectedText).visibleBounds)
        assertEquals("Only the final target may remain persisted", expectedProgress, persistedChapterProgress(), 0.01f)
    }

    @Test
    fun scrollingReaderSameChapterSeekAfterRotationReturnsToExactPosition() {
        seedSeekFixture(paragraphCount = 300, singleChapter = true)
        try {
            device.setOrientationNatural()
            openReaderWithControls()
            swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.75f)
            waitForChapterProgressInRange(0.55f, 0.95f)

            // Build a portrait geometry cache, then require a new target and exact return using
            // landscape geometry. Comparing anchors within landscape avoids assuming that a
            // paragraph has the same pixel bounds across two different layouts.
            device.setOrientationLeft()
            assertTrue(device.wait(Until.gone(By.desc("Book position")), UI_TIMEOUT))
            visibleTextContaining("Benchmark progress paragraph")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            visibleDescription("Book position")
            assertSameChapterSeekAndReturn()
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test
    fun scrollingReaderKeepsSeekAnchorWhileDelayedImageGrows() {
        DelayedImageServer().use { imageServer ->
            seedSeekFixture(paragraphCount = 80, singleChapter = true, imageUri = imageServer.uri)
            ReaderSeekLoadingFixture(::shell, "delayed-image-seek-anchor").use { fixture ->
                openReaderWithControls()
                imageServer.awaitRequest()
                val placeholderHeight = fixture.awaitStableImageHeight("benchmark-chapter-1", componentIndex = 1)

                swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.45f)
                waitForChapterProgressInRange(0.30f, 0.65f)
                device.waitForIdle()
                val anchorText = centeredText("Benchmark progress paragraph")
                val anchorBounds = visibleText(anchorText).visibleBounds

                imageServer.releaseImage()
                imageServer.awaitServed()
                fixture.awaitStableImageHeight(
                    "benchmark-chapter-1", componentIndex = 1,
                    minimumExclusive = placeholderHeight + placeholderHeight / 4,
                )
                waitForStableTextBounds(anchorText, anchorBounds)
            }
        }
    }

    @Test
    fun scrollingReaderDoesNotRestoreOldSeekAfterUserScrollAndDelayedImageGrowth() {
        DelayedImageServer().use { imageServer ->
            seedSeekFixture(paragraphCount = 80, singleChapter = true, imageUri = imageServer.uri)
            ReaderSeekLoadingFixture(::shell, "delayed-image-after-user-scroll").use { fixture ->
                openReaderWithControls()
                imageServer.awaitRequest()
                val placeholderHeight = fixture.awaitStableImageHeight("benchmark-chapter-1", componentIndex = 1)

                swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.35f)
                waitForChapterProgressInRange(0.20f, 0.50f)
                device.waitForIdle()
                val oldAnchorText = centeredText("Benchmark progress paragraph")
                val oldAnchorNumber = paragraphNumber(oldAnchorText)
                scrollReader(times = 3)
                assertTrue(
                    "The user gesture must move well beyond the old seek anchor before releasing the image",
                    paragraphNumber(centeredText("Benchmark progress paragraph")) > oldAnchorNumber + 3,
                )

                imageServer.releaseImage()
                imageServer.awaitServed()
                fixture.awaitStableImageHeight(
                    "benchmark-chapter-1", componentIndex = 1,
                    minimumExclusive = placeholderHeight + placeholderHeight / 4,
                )
                repeat(3) {
                    // Image growth can naturally move the currently visible text. It must not
                    // reinstate the old seek anchor after an explicit user scroll cleared it.
                    assertTrue(
                        "Delayed image growth must not pull the user back to $oldAnchorText",
                        paragraphNumber(centeredText("Benchmark progress paragraph")) > oldAnchorNumber + 2,
                    )
                    SystemClock.sleep(250)
                }
            }
        }
    }

    @Test
    fun scrollingReaderClearsReturnPositionWhenRotationOrTapHidesMenu() {
        try {
            device.setOrientationNatural()
            openReaderWithControls()
            seekBookPosition(toEnd = true)
            visibleTextContaining("Benchmark chapter two progress paragraph")
            visibleDescription("Return to the position before jumping")

            device.setOrientationLeft()
            assertTrue(
                "Rotation must hide the reader menu",
                device.wait(Until.gone(By.desc("Book position")), UI_TIMEOUT),
            )
            visibleTextContaining("Benchmark chapter two progress paragraph")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            visibleDescription("Book position")
            assertFalse(
                "Reopening the menu after rotation must not revive the old return position",
                device.wait(
                    Until.hasObject(By.desc("Return to the position before jumping")),
                    1_500L,
                ),
            )

            seekBookPosition(toEnd = false)
            visibleTextContaining("Benchmark progress paragraph")
            visibleDescription("Return to the position before jumping")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertTrue(device.wait(Until.gone(By.desc("Book position")), UI_TIMEOUT))
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            visibleDescription("Book position")
            assertFalse(
                "Manually hiding the menu must still clear the return position",
                device.wait(
                    Until.hasObject(By.desc("Return to the position before jumping")),
                    1_500L,
                ),
            )
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test
    fun scrollingReaderRejectsEmptyLastChapterAndCanReturnToOriginalPosition() {
        val fixtureResult = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.EMPTY_LAST_CHAPTER"
        )
        assertTrue(
            "Failed to empty the last chapter: $fixtureResult",
            fixtureResult.contains("empty-last-chapter=SUCCEEDED"),
        )
        openReaderWithControls()
        val originalText = centeredText("Benchmark progress paragraph")
        val originalBounds = visibleText(originalText).visibleBounds
        val originalProgress = persistedChapterProgress()

        seekBookPosition(toEnd = true)
        visibleText("章节为空")
        visibleText("该章节没有可阅读的内容")
        visibleDescription("Return to the position before jumping")
        assertEquals("An empty chapter must not receive reading progress", -1f, persistedChapterProgress("benchmark-chapter-2"))
        assertEquals("A failed seek must retain the last successful progress", originalProgress, persistedChapterProgress())

        tapText("Retry")
        visibleText("章节为空")
        visibleDescription("Book position")
        visibleDescription("Return to the position before jumping")

        tapDescription("Return to the position before jumping")
        assertTrue(
            "The return position should clear after restoring the original chapter",
            device.wait(Until.gone(By.desc("Return to the position before jumping")), UI_TIMEOUT),
        )
        visibleText("Benchmark Chapter One")
        assertEquals(
            originalText,
            waitForCenteredText("Benchmark progress paragraph", originalText),
        )
        assertEquals("Return must restore the exact text position", originalBounds, visibleText(originalText).visibleBounds)
        assertTextNotVisible("章节为空")
    }

    @Test
    fun pagedReaderSeeksAcrossVolumesAndReturnsToOriginalPosition() {
        openReaderWithControls()
        tapText("Settings")
        tapText("Controls")
        tapText("Page Turn Mode")
        visibleText("Volume Key Navigation")
        pressBack()
        visibleTextContaining("Benchmark progress paragraph")
        assertSeekAndReturn(paged = true)
    }

    private fun assertSeekAndReturn(paged: Boolean = false) {
        val originalText = centeredText("Benchmark progress paragraph")
        val originalBounds = visibleText(originalText).visibleBounds
        val originalPage = if (paged) flipPage() else null
        seekBookPosition(toEnd = true)
        visibleText("Benchmark Chapter Two")
        visibleTextContaining("Benchmark chapter two progress paragraph")
        assertCompletedChapterProgress("benchmark-chapter-2")
        tapDescription("Return to the position before jumping")
        assertTrue(
            "Return marker should clear only after the original reading position is restored",
            device.wait(
                Until.gone(By.desc("Return to the position before jumping")),
                UI_TIMEOUT,
            ),
        )
        visibleText("Benchmark Chapter One")
        assertEquals(
            originalText,
            waitForCenteredText("Benchmark progress paragraph", originalText),
        )
        assertEquals("Return must restore the exact text position", originalBounds, visibleText(originalText).visibleBounds)
        if (originalPage != null) assertEquals(originalPage, waitForPage(originalPage))

        tapText("Contents")
        visibleText("Select Chapter")
        visibleText("Benchmark Chapter One")
        assertTextNotVisible("Benchmark Chapter Two")
    }

    private fun seekBookPosition(toEnd: Boolean) {
        val track = visibleDescription("Book position").visibleBounds
        device.swipe(
            track.centerX(), track.centerY(),
            if (toEnd) track.right - 1 else track.left + 1, track.centerY(), 30,
        )
    }

    private fun assertSameChapterSeekAndReturn() {
        swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.10f)
        waitForChapterProgressInRange(0f, 0.20f)
        // Begin a fresh origin session at the warmed target rather than retaining the first
        // setup jump as the return position.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertTrue(device.wait(Until.gone(By.desc("Book position")), UI_TIMEOUT))
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        visibleDescription("Book position")
        val originalText = centeredText("Benchmark progress paragraph")
        val originalBounds = visibleText(originalText).visibleBounds

        swipeBookFraction(visibleDescription("Book position").visibleBounds, 0.75f)
        waitForChapterProgressInRange(0.55f, 0.95f)
        assertNotEquals("The seek must actually move within the chapter", originalText, centeredText("Benchmark progress paragraph"))
        assertEquals("Same-chapter seeks must not update chapter two", -1f, persistedChapterProgress("benchmark-chapter-2"))
        tapDescription("Return to the position before jumping")
        assertTrue(device.wait(Until.gone(By.desc("Return to the position before jumping")), UI_TIMEOUT))
        assertEquals(originalText, waitForCenteredText("Benchmark progress paragraph", originalText))
        assertEquals("Same-chapter return must restore the exact text position", originalBounds, visibleText(originalText).visibleBounds)
        waitForChapterProgressInRange(0f, 0.20f)
    }
}
