package indi.dmzz_yyhyy.lightnovelreader.benchmark.book

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.ReaderSeekLoadingFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.math.abs

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderPendingSeekScenarios : ReaderRegressionTestCase() {
    @Test
    fun scrollingReaderMenuTapImmediatelyAfterSeekIsHandled() {
        assertEarlyMenuTapAfterSeek(delayMs = 0)
    }

    @Test
    fun scrollingReaderMenuTap50msAfterSeekIsHandled() {
        assertEarlyMenuTapAfterSeek(delayMs = 50)
    }

    @Test
    fun scrollingReaderMenuTap100msAfterSeekIsHandled() {
        assertEarlyMenuTapAfterSeek(delayMs = 100)
    }

    private fun extendSeekFixture() {
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.EXTEND_RAPID_CHAPTER_CHAIN"
        )
        assertTrue("Expected the local 12-chapter fixture: $result", result.contains("rapid-chapters=SUCCEEDED"))
    }

    private fun assertEarlyMenuTapAfterSeek(delayMs: Long) {
        extendSeekFixture()
        ReaderSeekLoadingFixture(::shell, "menu-tap-after-seek-${delayMs}ms").use { fixture ->
            openReaderWithControls()
            val track = stableBookPositionBounds()
            val initial = fixture.awaitDraw("initial menu") { it.enabled && !it.previewing }
            val centerX = device.displayWidth / 2f
            val centerY = device.displayHeight / 2f
            val seekUp = fixture.beginPointer(track, initial.value).use { pointer ->
                pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_A)
                pointer.finish(sync = false)
            }

            // No accessibility lookup, gate report, or idle wait is allowed between seek UP
            // and this tap. The recorded input timestamps preserve the actual tested interval.
            val tapUp = fixture.tapAfter(
                seekUp, delayMs, centerX, centerY,
                syncInput = false, maximumDelaySlipMs = 40,
            )
            assertTrue("The first tap at ${delayMs}ms must hide the menu", device.wait(Until.gone(By.desc("Book position")), 2_000))
            fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "entered")
            fixture.assertHeld(ReaderSeekLoadingFixture.CHAPTER_A)
            assertSingleMenuExit(fixture, tapUp)
        }
    }

    @Test
    fun scrollingReaderMenuTapSpansLoadingAndContent() {
        assertMenuTapSpansLoadingAndContent(paged = false)
    }

    @Test
    fun pagedReaderMenuTapSpansLoadingAndContentAndPreservesGestures() {
        assertMenuTapSpansLoadingAndContent(paged = true)
    }

    private fun assertMenuTapSpansLoadingAndContent(paged: Boolean) {
        extendSeekFixture()
        ReaderSeekLoadingFixture(::shell, if (paged) "paged-menu-tap-across-content" else "scroll-menu-tap-across-content").use { fixture ->
            if (paged) {
                openBook()
                tapScrolledText("Benchmark Chapter One")
                visibleTextContaining("Benchmark progress paragraph")
                enablePageMode(tapZones = true, noAnimation = true)
                fixture.tapAt(device.displayWidth / 2f, device.displayHeight / 2f)
            } else {
                openReaderWithControls()
            }
            val track = stableBookPositionBounds()
            val initial = fixture.awaitDraw("menu before loading") { it.enabled && !it.previewing }
            fixture.beginPointer(track, initial.value).use { pointer ->
                pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_A)
                pointer.finish()
            }
            fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "entered")
            fixture.assertHeld(ReaderSeekLoadingFixture.CHAPTER_A)
            val loading = By.pkg(TARGET_PACKAGE).clazz("android.widget.ProgressBar")
            assertTrue("A real Loading node must exist before DOWN", device.wait(Until.hasObject(loading), UI_TIMEOUT))
            assertEquals(-1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A))

            val tapUp = fixture.beginTouch(device.displayWidth / 2f, device.displayHeight / 2f).use { touch ->
                fixture.release(ReaderSeekLoadingFixture.CHAPTER_A)
                fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "passed")
                awaitPositionedFixtureChapter(ReaderSeekLoadingFixture.CHAPTER_A, "Benchmark rapid chapter 6 paragraph")
                assertTrue("The Loading branch must be removed before UP", device.wait(Until.gone(loading), UI_TIMEOUT))
                fixture.recordEvent("content positioned and Loading semantics removed while pointer held")
                touch.finish()
            }
            assertTrue("A tap spanning Loading and content must hide the menu once", device.wait(Until.gone(By.desc("Book position")), 2_000))
            assertSingleMenuExit(fixture, tapUp)

            // A normal center tap must open exactly once after the cross-branch gesture.
            fixture.tapAt(device.displayWidth / 2f, device.displayHeight / 2f)
            stableBookPositionBounds()
            fixture.tapAt(device.displayWidth / 2f, device.displayHeight / 2f)
            assertTrue(device.wait(Until.gone(By.desc("Book position")), 2_000))
            if (paged) {
                val beforePage = flipPage()
                fixture.tapAt(device.displayWidth * 5f / 6, device.displayHeight / 2f)
                assertNotEquals("A side tap must still turn the page", beforePage, waitForDifferentPage(beforePage))
                assertFalse("A side tap must not toggle the menu", device.hasObject(By.desc("Book position")))
                waitForPagerIdle()
                val selectedPage = flipPage()
                // A paragraph continuation on the next page need not repeat its prefix.
                // Select actual visible body text scoped to the resolved current page.
                val bodyText = requireNotNull(device.findObject(By.res(selectedPage)))
                    .findObjects(By.text(Pattern.compile("(?s).{20,}")))
                    .filter { it.visibleBounds.height() > 0 }
                    .minByOrNull { abs(it.visibleBounds.centerY() - device.displayHeight / 2) }
                    ?: error("The resolved page has no visible body text: $selectedPage")
                bodyText.longClick()
                assertTrue("Long press must still select text", device.wait(Until.hasObject(By.text(Pattern.compile("Copy|复制"))), UI_TIMEOUT))
                assertFalse("Text selection must not toggle the menu", device.hasObject(By.desc("Book position")))
                assertEquals("Text selection must not turn the page", selectedPage, flipPage())
            }
        }
    }

    private fun assertSingleMenuExit(fixture: ReaderSeekLoadingFixture, afterUp: Long) {
        fixture.drain()
        val afterTap = fixture.frames.filter { it.uptime >= afterUp }
        assertTrue("The menu must render its disabled exit state", afterTap.any { !it.enabled })
        assertFalse("One body tap must not reopen the menu", afterTap.dropWhile { it.enabled }.any { it.enabled })
    }

    @Test
    fun readerToolsAreDisabledDuringMenuExit() {
        ReaderSeekLoadingFixture(::shell, "menu-tools-during-exit").use { fixture ->
            openReaderWithControls()
            val originalText = centeredText("Benchmark progress paragraph")
            listOf("Settings", "Contents", "Next Chapter").forEach { tool ->
                stableBookPositionBounds()
                var target = visibleText(tool)
                while (!target.isClickable && target.parent != null) target = target.parent
                assertTrue("The tool must be interactive before menu exit: $tool", target.isClickable && target.isEnabled)
                val bounds = target.visibleBounds
                val centerX = device.displayWidth / 2f
                val centerY = device.displayHeight / 2f
                fixture.recordEvent("close menu then hit retiring tool $tool")
                val closeUp = fixture.tapAt(centerX, centerY, syncInput = false)
                val toolUp = fixture.tapAfter(
                    closeUp, 0, bounds.exactCenterX(), bounds.exactCenterY(),
                    pressDurationMs = 24, syncInput = false, maximumDelaySlipMs = 40,
                )
                assertTrue("$tool tap missed the 150ms exit window: ${toolUp - closeUp}ms", toolUp - closeUp < 150)
                assertTrue("The retiring tool must not reopen the menu: $tool", device.wait(Until.gone(By.desc("Book position")), 2_000))
                assertTextNotVisible("Reader Settings", timeout = 500)
                assertTextNotVisible("Select Chapter", timeout = 500)
                assertEquals("The retiring tool must not navigate: $tool", originalText, centeredText("Benchmark progress paragraph"))
                assertEquals("An exiting Next Chapter tool must not publish chapter two", -1f, persistedChapterProgress("benchmark-chapter-2"))
                fixture.tapAt(device.displayWidth / 2f, device.displayHeight / 2f)
                visibleDescription("Book position")
            }
        }
    }

    @Test
    fun scrollingReaderPendingSameChapterSeekReusesLoadAndKeepsLatestTarget() {
        extendSeekFixture()
        ReaderSeekLoadingFixture(::shell, "pending-same-chapter-retarget").use { fixture ->
            openReaderWithControls()
            val track = stableBookPositionBounds()
            val initial = fixture.awaitDraw("initial menu") { it.enabled && !it.previewing }
            fixture.beginPointer(track, initial.value).use { pointer ->
                pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_A)
                pointer.finish()
            }
            fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "entered")
            fixture.assertSinglePendingLoad(ReaderSeekLoadingFixture.CHAPTER_A)
            val origin = fixture.awaitDraw("first pending origin") {
                !it.previewing && it.formalOrigin != null && it.markerX != null
            }
            fixture.drain()
            val retargetStart = fixture.frames.size
            // Stay inside chapter 6, but cross touch slop so this is a real second drag.
            val latestTarget = ReaderSeekLoadingFixture.FRACTION_A + 0.035f
            fixture.beginPointer(track, ReaderSeekLoadingFixture.FRACTION_A).use { pointer ->
                pointer.moveTo(latestTarget)
                fixture.awaitDraw("same-chapter preview", retargetStart) { it.previewing }
                val beforeUp = fixture.frames.size
                pointer.finish()
                fixture.awaitDraw("same-chapter retarget committed", beforeUp) {
                    !it.previewing && abs(it.value - latestTarget) < 0.003f
                }
            }
            fixture.assertSinglePendingLoad(ReaderSeekLoadingFixture.CHAPTER_A)
            assertEquals("Held A must not publish an intermediate position", -1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A))
            assertMarkerDrawsKeepOrigin(fixture, fixture.frames[retargetStart].sequence, origin)
            fixture.release(ReaderSeekLoadingFixture.CHAPTER_A)
            fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "passed")
            awaitPositionedFixtureChapter(ReaderSeekLoadingFixture.CHAPTER_A, "Benchmark rapid chapter 6 paragraph")
            fixture.drain()
            val completedFraction = persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A)
            // Relative seeks resolve to real page boundaries. This short chapter rounds the
            // new 92% target to its last page; the superseded 50% target stays in the middle.
            assertTrue("Completion must use the latest end-region target, not the old middle target",
                completedFraction in 0.8f..1f)
            // This is the last page of chapter 6 in the frozen equal-weight 12-chapter map.
            // Navigation reaches the chapter endpoint; continuous reading history still
            // measures the viewport against the chapter center and must remain independent.
            assertEquals("Displayed navigation must reach the end of chapter 6",
                6f / 12f, fixture.frames.last().value, 0.003f)
            assertMarkerDrawsKeepOrigin(fixture, fixture.frames[retargetStart].sequence, origin)
            // The floating menu covers the final paragraph without changing the reader layout.
            // Check its marker first, then uncover the same page to verify the actual body endpoint.
            fixture.tapAt(device.displayWidth / 2f, device.displayHeight / 2f)
            assertTrue(device.wait(Until.gone(By.desc("Book position")), UI_TIMEOUT))
            visibleTextContaining("Benchmark rapid chapter 6 paragraph 30.")
        }
    }

    @Test
    fun scrollingReaderPendingSeekKeepsMarkerAcrossConsecutiveSeeks() {
        assertPendingSeekOriginLifecycle(reopenMenu = false)
    }

    @Test
    fun scrollingReaderPendingSeekAfterMenuReopensKeepsOriginAbsent() {
        assertPendingSeekOriginLifecycle(reopenMenu = true)
    }

    @Test
    fun scrollingReaderCompletedSeekAfterMenuReopensCreatesNewOrigin() {
        assertPendingSeekOriginLifecycle(reopenMenu = true, completeBeforeSecondDown = true)
    }

    private fun assertPendingSeekOriginLifecycle(reopenMenu: Boolean, completeBeforeSecondDown: Boolean = false) {
        extendSeekFixture()
        val evidenceName = when {
            completeBeforeSecondDown -> "completed-seek-reopen-new-origin"
            reopenMenu -> "pending-seek-reopen-without-origin"
            else -> "pending-seek-consecutive"
        }
        ReaderSeekLoadingFixture(::shell, evidenceName).use { fixture ->
            openReaderWithControls()
            var track = stableBookPositionBounds()
            val originalText = centeredText("Benchmark progress paragraph")
            val originalBounds = visibleText(originalText).visibleBounds
            val originalProgress = persistedChapterProgress()
            val initial = fixture.awaitDraw("initial reader row") { it.enabled && !it.previewing }
            val firstSeekStart = fixture.frames.size
            fixture.beginPointer(track, initial.value).use { pointer ->
                pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_A)
                pointer.finish()
            }
            fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "entered")
            fixture.assertHeld(ReaderSeekLoadingFixture.CHAPTER_A)
            assertEquals("A must not be positioned before its load is released", -1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A))
            val origin = fixture.awaitDraw("first pending seek's formal origin", firstSeekStart) {
                !it.previewing && it.formalOrigin != null && it.markerX != null
            }
            assertTrue("A and the captured return anchor must be distinct", abs(origin.value - origin.formalOrigin!!) > 0.2f)

            var returnPrefix = "Benchmark progress paragraph"
            var returnChapter = "benchmark-chapter-1"
            var returnText = originalText
            var returnBounds = originalBounds
            var returnProgress = originalProgress
            var secondStartProgress = ReaderSeekLoadingFixture.FRACTION_A
            var expectedOriginProgress = requireNotNull(origin.formalOrigin)
            var expectedOriginFrame = origin
            val secondGestureHasOrigin = !reopenMenu || completeBeforeSecondDown

            if (reopenMenu) {
                // Let the body-to-loading transition settle before a separate menu tap.
                device.waitForIdle()
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                assertTrue(device.wait(Until.gone(By.desc("Book position")), UI_TIMEOUT))
                val reopening = fixture.frames.size
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                track = stableBookPositionBounds()
                fixture.awaitDraw("reopened menu clears its old origin", reopening) {
                    it.enabled && !it.previewing && it.displayedOrigin == null
                }
                fixture.assertHeld(ReaderSeekLoadingFixture.CHAPTER_A)

                // With A still pending and the previous session cleared, even a temporary
                // marker is forbidden. ACTION_CANCEL must leave both the anchor and A intact.
                fixture.drain()
                val cancelledPreviewStart = fixture.frames.size
                val beforeCancel = fixture.beginPointer(track, ReaderSeekLoadingFixture.FRACTION_A).use { pointer ->
                    pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_B)
                    fixture.awaitDraw("preview before ACTION_CANCEL", cancelledPreviewStart) { it.previewing }
                    assertMarkerDrawsHaveNoOrigin(fixture, cancelledPreviewStart)
                    fixture.frames.size
                }
                val cancelled = fixture.awaitDraw("cancelled preview clears its temporary marker", beforeCancel) {
                    !it.previewing && it.displayedOrigin == null
                }
                assertEquals("Cancellation must retain pending A", ReaderSeekLoadingFixture.FRACTION_A, cancelled.value, 0.005f)
                fixture.assertHeld(ReaderSeekLoadingFixture.CHAPTER_A)
                assertMarkerDrawsHaveNoOrigin(fixture, cancelledPreviewStart)

                if (completeBeforeSecondDown) {
                    fixture.release(ReaderSeekLoadingFixture.CHAPTER_A)
                    fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "passed")
                    awaitPositionedFixtureChapter(ReaderSeekLoadingFixture.CHAPTER_A, "Benchmark rapid chapter 6 paragraph")
                    returnPrefix = "Benchmark rapid chapter 6 paragraph"
                    returnChapter = ReaderSeekLoadingFixture.CHAPTER_A
                    returnText = centeredText(returnPrefix)
                    returnBounds = visibleText(returnText).visibleBounds
                    waitForStableTextBounds(returnText, returnBounds)
                    returnProgress = persistedChapterProgress(returnChapter)
                    fixture.drain()
                    assertMarkerDrawsHaveNoOrigin(fixture, cancelledPreviewStart)
                    // The newly persisted chapter proves that pendingTarget was cleared before
                    // DOWN; gate.passed or visible text alone would not establish that boundary.
                    secondStartProgress = fixture.frames.last().value
                    expectedOriginProgress = secondStartProgress
                }
            }

            fixture.drain()
            val secondSeekStart = fixture.frames.size
            fixture.beginPointer(track, secondStartProgress).use { pointer ->
                pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_B)
                fixture.awaitDraw("held second drag", secondSeekStart) { it.previewing }
                // Include every draw after the pre-DOWN drain, including a missing marker.
                // The row is settled here; selecting by visible content could hide a flash.
                val startSequence = fixture.frames[secondSeekStart].sequence
                if (completeBeforeSecondDown) expectedOriginFrame = fixture.frames[secondSeekStart]
                fun assertSecondGestureOrigin() {
                    if (secondGestureHasOrigin) {
                        assertMarkerDrawsKeepOrigin(fixture, startSequence, expectedOriginFrame, expectedOriginProgress)
                    } else {
                        assertMarkerDrawsHaveNoOrigin(fixture, secondSeekStart)
                    }
                }
                assertSecondGestureOrigin()

                if (reopenMenu && !completeBeforeSecondDown) {
                    // Complete A while MOVE remains held. Null was frozen at DOWN, so A's
                    // completion must not create a marker during MOVE, UP, or B's later load.
                    fixture.release(ReaderSeekLoadingFixture.CHAPTER_A)
                    fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "passed")
                    awaitPositionedFixtureChapter(ReaderSeekLoadingFixture.CHAPTER_A, "Benchmark rapid chapter 6 paragraph")
                    pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_B - 0.01f, steps = 3)
                    pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_B, steps = 3)
                    fixture.drain()
                    assertSecondGestureOrigin()
                }

                val beforeRelease = fixture.frames.size
                pointer.finish()
                fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_B, "entered")
                fixture.assertHeld(ReaderSeekLoadingFixture.CHAPTER_B)
                val committed = fixture.awaitDraw("second seek committed while B is held", beforeRelease) {
                    !it.previewing
                }
                assertEquals("The pending thumb must stay at B", ReaderSeekLoadingFixture.FRACTION_B, committed.value, 0.005f)
                assertEquals("UP must preserve the origin decision made at DOWN", secondGestureHasOrigin, committed.formalOrigin != null)
                assertSecondGestureOrigin()
                if (!reopenMenu) fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "cancelled")

                assertEquals("B must still be unpositioned while held", -1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_B))
                fixture.release(ReaderSeekLoadingFixture.CHAPTER_B)
                fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_B, "passed")
                awaitPositionedFixtureChapter(ReaderSeekLoadingFixture.CHAPTER_B, "Benchmark rapid chapter 10 paragraph")
                fixture.drain()
                assertSecondGestureOrigin()

                if (!reopenMenu) {
                    // Releasing a cancelled A must not resurrect the superseded destination.
                    fixture.release(ReaderSeekLoadingFixture.CHAPTER_A)
                    SystemClock.sleep(250)
                    visibleTextContaining("Benchmark rapid chapter 10 paragraph")
                    assertEquals("Cancelled A must not publish reading progress", -1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A))
                }
            }

            if (!secondGestureHasOrigin) {
                assertFalse("Completing B must not create a return action", device.hasObject(By.desc("Return to the position before jumping")))
                returnPrefix = "Benchmark rapid chapter 10 paragraph"
                returnChapter = ReaderSeekLoadingFixture.CHAPTER_B
                returnText = centeredText(returnPrefix)
                returnBounds = visibleText(returnText).visibleBounds
                waitForStableTextBounds(returnText, returnBounds)
                returnProgress = persistedChapterProgress(returnChapter)
                fixture.drain()
                assertMarkerDrawsHaveNoOrigin(fixture, secondSeekStart)
                val completedBProgress = fixture.frames.last().value

                // A new DOWN after B has finished may finally create an origin, anchored to B.
                val thirdSeekStart = fixture.frames.size
                fixture.beginPointer(track, completedBProgress).use { pointer ->
                    pointer.moveTo(ReaderSeekLoadingFixture.FRACTION_A)
                    fixture.awaitDraw("new gesture from completed B", thirdSeekStart) { it.previewing }
                    val newOrigin = fixture.frames[thirdSeekStart]
                    assertMarkerDrawsKeepOrigin(fixture, newOrigin.sequence, newOrigin, completedBProgress)
                    val beforeUp = fixture.frames.size
                    pointer.finish()
                    fixture.awaitDraw("new B origin becomes formal", beforeUp) { !it.previewing && it.formalOrigin != null }
                    visibleTextContaining("Benchmark rapid chapter 6 paragraph")
                    fixture.drain()
                    assertMarkerDrawsKeepOrigin(fixture, newOrigin.sequence, newOrigin, completedBProgress)
                }
            }

            tapDescription("Return to the position before jumping")
            assertTrue(device.wait(Until.gone(By.desc("Return to the position before jumping")), UI_TIMEOUT))
            assertEquals(returnText, waitForCenteredText(returnPrefix, returnText))
            assertEquals("Return must restore the new session's exact offset", returnBounds, visibleText(returnText).visibleBounds)
            val returnDeadline = SystemClock.uptimeMillis() + 5_000
            while (abs(persistedChapterProgress(returnChapter) - returnProgress) > 0.01f && SystemClock.uptimeMillis() < returnDeadline) {
                SystemClock.sleep(50)
            }
            assertEquals("Return must persist the correct session anchor", returnProgress, persistedChapterProgress(returnChapter), 0.01f)
        }
    }

    private fun awaitPositionedFixtureChapter(chapterId: String, prefix: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (persistedChapterProgress(chapterId) < 0f && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertTrue("The new $chapterId must finish positioning and persist", persistedChapterProgress(chapterId) >= 0f)
        visibleTextContaining(prefix)
    }

    private fun assertMarkerDrawsHaveNoOrigin(fixture: ReaderSeekLoadingFixture, startIndex: Int) {
        val frames = fixture.frames.drop(startIndex)
        assertTrue("The no-origin gesture must produce Compose draws", frames.isNotEmpty())
        frames.forEach { frame ->
            assertTrue(
                "No temporary or formal marker may appear in any draw: $frame",
                frame.displayedOrigin == null && frame.formalOrigin == null &&
                    frame.gestureOrigin == null && frame.markerX == null,
            )
        }
    }

    private fun assertMarkerDrawsKeepOrigin(
        fixture: ReaderSeekLoadingFixture,
        startSequence: Long,
        expected: ReaderSeekLoadingFixture.MarkerDraw,
        expectedOrigin: Float = requireNotNull(expected.formalOrigin),
    ) {
        val expectedMarkerX = requireNotNull(expected.markerX)
        val frames = fixture.frames.filter { it.sequence >= startSequence }
        assertTrue("The real UI must have drawn the second gesture", frames.isNotEmpty())
        frames.forEach { frame ->
            assertTrue("Marker must stay present in every Compose draw: $frame", frame.displayedOrigin != null && frame.markerX != null)
            assertEquals("Marker must retain its original fraction: $frame", expectedOrigin, frame.displayedOrigin!!, 0.0001f)
            assertEquals("Marker's rendered center must not jump: $frame", expectedMarkerX, frame.markerX!!, 1f)
            assertEquals("Track geometry must remain comparable", expected.trackWidth, frame.trackWidth, 1f)
            frame.formalOrigin?.let { assertEquals("Committed origin must match the held marker", expectedOrigin, it, 0.0001f) }
            frame.gestureOrigin?.let { assertEquals("Temporary origin must match the captured anchor", expectedOrigin, it, 0.0001f) }
        }
    }

    private fun stableBookPositionBounds(): Rect {
        var previous: Rect? = null
        var stableSamples = 0
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val bounds = visibleDescription("Book position").visibleBounds
            if (bounds.width() > 0 && bounds.height() > 0 && bounds == previous) stableSamples++ else stableSamples = 1
            if (stableSamples >= 3) return bounds
            previous = bounds
            SystemClock.sleep(100)
        }
        error("Book position did not settle before the controlled gesture: $previous")
    }
}
