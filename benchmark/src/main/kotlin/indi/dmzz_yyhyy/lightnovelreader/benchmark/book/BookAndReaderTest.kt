package indi.dmzz_yyhyy.lightnovelreader.benchmark.book

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.DelayedImageServer
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.ReaderSeekLoadingFixture
import indi.dmzz_yyhyy.lightnovelreader.benchmark.ui.UiAutomatorTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs

@LargeTest
@RunWith(AndroidJUnit4::class)
class BookAndReaderTest : UiAutomatorTest() {
    private fun openBookDetails(title: String = "Benchmark Sample Novel") {
        launchApp()
        navigateToBookDetails(title)
    }

    private fun navigateToBookDetails(title: String) {
        openBottomNavigation("Bookshelf")
        clickScrolledText(title)
        assertText(title)
    }

    private fun swipeReaderDown(times: Int) {
        repeat(times) {
            device.swipe(
                device.displayWidth / 2,
                (device.displayHeight * 0.78).toInt(),
                device.displayWidth / 2,
                (device.displayHeight * 0.28).toInt(),
                35,
            )
            device.waitForIdle()
            SystemClock.sleep(250)
        }
        SystemClock.sleep(1_000)
    }

    private fun currentCenteredVisibleText(prefix: String): String? {
        val centerY = device.displayHeight / 2
        return try {
            device.findObjects(By.textStartsWith(prefix))
                .mapNotNull { object2 ->
                    val bounds = object2.visibleBounds
                    if (bounds.height() <= 0) null
                    else object2.text to abs(bounds.centerY() - centerY)
                }
                .minByOrNull { it.second }
                ?.first
        } catch (_: StaleObjectException) {
            null
        }
    }

    private fun centeredVisibleText(prefix: String): String {
        repeat(40) {
            currentCenteredVisibleText(prefix)?.let { return it }
            SystemClock.sleep(250)
        }
        assertTrue("Expected a visible progress marker starting with: $prefix", false)
        error("unreachable")
    }

    private fun waitForCenteredVisibleText(prefix: String, expected: String): String {
        repeat(40) {
            if (currentCenteredVisibleText(prefix) == expected) return expected
            SystemClock.sleep(250)
        }
        return centeredVisibleText(prefix)
    }

    private fun persistedChapterProgress(
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

    private fun assertCompletedChapterProgress(chapterId: String, maximum: Boolean = false) {
        repeat(40) {
            if (persistedChapterProgress(chapterId, maximum) == 1f) return
            SystemClock.sleep(250)
        }
        assertEquals("Chapter completion must be persisted", 1f, persistedChapterProgress(chapterId, maximum))
    }

    private fun waitForProgressAfter(previous: Float): Float {
        repeat(40) {
            val progress = persistedChapterProgress()
            if (progress > previous) return progress
            SystemClock.sleep(250)
        }
        return persistedChapterProgress()
    }

    private fun waitForPersistedChapterLocationHash(chapterId: String): Int {
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

    private fun currentFlipPageTag(): String {
        repeat(40) {
            val tag = device.findObjects(By.res(Pattern.compile(".*flip-page-.*")))
                .mapNotNull { it.resourceName }
                .firstOrNull { !it.endsWith("-pending") }
            if (tag != null) return tag
            SystemClock.sleep(250)
        }
        error("No resolved flip-page state tag was visible")
    }

    private fun waitForDifferentFlipPage(previous: String): String {
        repeat(40) {
            val current = currentFlipPageTag()
            if (current != previous) return current
            SystemClock.sleep(250)
        }
        return currentFlipPageTag()
    }

    private fun waitForFlipPage(expected: String): String {
        repeat(40) {
            val current = currentFlipPageTag()
            if (current == expected) return current
            SystemClock.sleep(250)
        }
        return currentFlipPageTag()
    }

    private fun currentFlipChapterTag(): String {
        repeat(40) {
            device.findObjects(By.res(Pattern.compile(".*flip-chapter-.*")))
                .mapNotNull { it.resourceName }
                .firstOrNull()
                ?.let { return it }
            SystemClock.sleep(250)
        }
        error("No active flip chapter state tag was visible")
    }

    private fun waitForFlipChapter(chapterId: String): String {
        val expectedSuffix = "flip-chapter-$chapterId"
        repeat(40) {
            val current = currentFlipChapterTag()
            if (current.endsWith(expectedSuffix)) return current
            SystemClock.sleep(250)
        }
        return currentFlipChapterTag()
    }

    private fun turnPagesUntilChapter(
        chapterId: String,
        forward: Boolean,
        maxTurns: Int = 40,
    ) {
        val expectedSuffix = "flip-chapter-$chapterId"
        repeat(maxTurns) {
            if (currentFlipChapterTag().endsWith(expectedSuffix)) return
            swipePage(forward)
        }
        error(
            "Pager did not reach chapter $chapterId; " +
                "chapter=${currentFlipChapterTag()} page=${currentFlipPageTag()} " +
                "state=${currentFlipStateDescription()}"
        )
    }

    private fun currentFlipStateDescription(): String =
        device.findObjects(By.desc(Pattern.compile("flip-state-.*")))
            .mapNotNull { it.contentDescription }
            .firstOrNull()
            ?: "missing"

    private fun waitForFlipPagerIdle(timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastState = currentFlipStateDescription()
        while (SystemClock.uptimeMillis() < deadline) {
            lastState = currentFlipStateDescription()
            if (lastState.contains("animating=false") && lastState.contains("direction=0")) {
                SystemClock.sleep(100)
                val confirmed = currentFlipStateDescription()
                if (confirmed.contains("animating=false") && confirmed.contains("direction=0")) {
                    return
                }
                lastState = confirmed
            }
            SystemClock.sleep(50)
        }
        assertTrue("Flip pager input lock did not recover: $lastState", false)
    }

    private fun swipePage(
        forward: Boolean,
        steps: Int = 20,
        settleMs: Long = 350,
    ) {
        device.swipe(
            if (forward) device.displayWidth * 5 / 6 else device.displayWidth / 6,
            device.displayHeight / 2,
            if (forward) device.displayWidth / 6 else device.displayWidth * 5 / 6,
            device.displayHeight / 2,
            steps,
        )
        if (settleMs >= 100) device.waitForIdle()
        SystemClock.sleep(settleMs)
    }

    private fun reachPageBoundary(forward: Boolean, maxTurns: Int = 30): String {
        var previous = currentFlipPageTag()
        repeat(maxTurns) {
            swipePage(forward)
            val current = currentFlipPageTag()
            if (current == previous) return current
            previous = current
        }
        error("Flip pager did not reach its ${if (forward) "end" else "start"} boundary")
    }

    private fun enablePageTurnMode(
        enableVolumeKeys: Boolean = false,
        enableTapToTurn: Boolean = false,
        disableAnimation: Boolean = false,
    ) {
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickText("Settings")
        clickText("Controls")
        clickText("Page Turn Mode")
        scrollToText("Volume Key Navigation")
        if (enableVolumeKeys) {
            clickText("Volume Key Navigation")
        }
        if (enableTapToTurn) {
            scrollToText("Tap to Turn Pages")
            clickText("Tap to Turn Pages")
        }
        if (disableAnimation) {
            scrollToText("Page Turn Animation")
            clickText("Page Turn Animation")
            // Dropdown entry nodes may be replaced during the opening animation. Reacquire
            // their bounds until stable, then issue one click without retaining a UiObject2.
            val deadline = SystemClock.uptimeMillis() + 5_000
            var noneBounds: Rect? = null
            var stableSamples = 0
            while (SystemClock.uptimeMillis() < deadline && stableSamples < 3) {
                val bounds = try {
                    device.findObject(By.text("None"))?.visibleBounds
                        ?.takeIf { it.width() > 0 && it.height() > 0 }
                } catch (_: StaleObjectException) {
                    null
                }
                stableSamples = when {
                    bounds == null -> 0
                    bounds == noneBounds -> stableSamples + 1
                    else -> 1
                }
                noneBounds = bounds?.let { Rect(it) }
                if (stableSamples < 3) SystemClock.sleep(100)
            }
            assertTrue(
                "Animation None entry did not reach three stable visible bounds within 5 seconds: $noneBounds",
                stableSamples == 3 && noneBounds != null,
            )
            val readyBounds = requireNotNull(noneBounds)
            device.click(readyBounds.centerX(), readyBounds.centerY())
            device.waitForIdle()
        }
        pressBack()
        val settingsTitle = "Reader Settings"
        repeat(3) {
            if (device.wait(Until.gone(By.text(settingsTitle)), 1_000L)) return@repeat
            pressBack()
        }
        assertTrue(
            "Reader settings did not close",
            device.wait(Until.gone(By.text(settingsTitle)), TIMEOUT),
        )
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        device.waitForIdle()
        SystemClock.sleep(1_000)
    }

    private fun setReaderMargin(title: String, value: Float) {
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
            var node: AccessibilityNodeInfo? = assertText(title).accessibilityNodeInfo
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
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            if (slider().rangeInfo.current == value) return
            SystemClock.sleep(50)
        }
        assertEquals("Unexpected $title value", value, slider().rangeInfo.current, 0f)
    }

    private fun turnPagesUntilText(
        targetText: String,
        forward: Boolean,
        maxTurns: Int = 40,
    ) {
        repeat(maxTurns) {
            if (device.hasObject(By.textContains(targetText))) return
            device.swipe(
                if (forward) device.displayWidth * 5 / 6 else device.displayWidth / 6,
                device.displayHeight / 2,
                if (forward) device.displayWidth / 6 else device.displayWidth * 5 / 6,
                device.displayHeight / 2,
                20,
            )
            if (device.wait(Until.hasObject(By.textContains(targetText)), 1_000L)) return
            SystemClock.sleep(250)
        }
        assertTextContains(targetText)
    }

    @Test
    fun detailActionsOpenExportFormattingAndMoreMenus() {
        openBookDetails()

        clickDescription("export")
        assertText("Export as Epub")
        pressBack()

        clickDescription("formatting")
        assertText("Book Rules")
        pressBack()

        clickDescription("more")
        assertText("Mark as read…")
        pressBack()
    }

    @Test
    fun chapterSelectionAndReaderControlsWork() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")

        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertText("Contents")
        assertText("Settings")
        assertText("Bookmarks")

        clickText("Contents")
        assertText("Select Chapter")
        assertText("Benchmark Chapter One")
        clickCenter(scrollToText("Benchmark Bonus Volume"))
        clickCenter(scrollToText("Benchmark Chapter Two"))
        assertForegroundPackage(TARGET_PACKAGE)
    }

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
        swipeBookFraction(assertDescription("Book position").visibleBounds, 0.30f)
        waitForChapterProgressInRange(0.40f, 0.85f)
        device.waitForIdle()
        val expectedText = centeredVisibleText("Benchmark progress paragraph")
        val expectedBounds = assertText(expectedText).visibleBounds
        val expectedProgress = persistedChapterProgress()
        val track = assertDescription("Book position").visibleBounds

        // No accessibility lookup or idle wait between releases: new requests can supersede
        // work from earlier cross-chapter seeks. The final destination is back in chapter one.
        listOf(0.90f, 0.10f, 0.90f, 0.10f, 0.30f).forEach { fraction ->
            swipeBookFraction(track, fraction, steps = 3)
        }

        assertEquals(
            "The final request must determine the visible paragraph",
            expectedText,
            waitForCenteredVisibleText("Benchmark progress paragraph", expectedText),
        )
        assertEquals("The final request must restore its target offset", expectedBounds, assertText(expectedText).visibleBounds)
        waitForChapterProgressInRange(expectedProgress - 0.01f, expectedProgress + 0.01f)
        SystemClock.sleep(1_500)
        assertEquals("A superseded request must not replace the final chapter", expectedText, centeredVisibleText("Benchmark progress paragraph"))
        assertEquals("A superseded request must not replace the final offset", expectedBounds, assertText(expectedText).visibleBounds)
        assertEquals("Only the final target may remain persisted", expectedProgress, persistedChapterProgress(), 0.01f)
    }

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
                openBookDetails()
                clickScrolledText("Benchmark Chapter One")
                assertTextContains("Benchmark progress paragraph")
                enablePageTurnMode(enableTapToTurn = true, disableAnimation = true)
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
            assertTrue("A real Loading node must exist before DOWN", device.wait(Until.hasObject(loading), TIMEOUT))
            assertEquals(-1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A))

            val tapUp = fixture.beginTouch(device.displayWidth / 2f, device.displayHeight / 2f).use { touch ->
                fixture.release(ReaderSeekLoadingFixture.CHAPTER_A)
                fixture.awaitGate(ReaderSeekLoadingFixture.CHAPTER_A, "passed")
                awaitPositionedFixtureChapter(ReaderSeekLoadingFixture.CHAPTER_A, "Benchmark rapid chapter 6 paragraph")
                assertTrue("The Loading branch must be removed before UP", device.wait(Until.gone(loading), TIMEOUT))
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
                val beforePage = currentFlipPageTag()
                fixture.tapAt(device.displayWidth * 5f / 6, device.displayHeight / 2f)
                assertNotEquals("A side tap must still turn the page", beforePage, waitForDifferentFlipPage(beforePage))
                assertFalse("A side tap must not toggle the menu", device.hasObject(By.desc("Book position")))
                waitForFlipPagerIdle()
                val selectedPage = currentFlipPageTag()
                // A paragraph continuation on the next page need not repeat its prefix.
                // Select actual visible body text scoped to the resolved current page.
                val bodyText = requireNotNull(device.findObject(By.res(selectedPage)))
                    .findObjects(By.text(Pattern.compile("(?s).{20,}")))
                    .filter { it.visibleBounds.height() > 0 }
                    .minByOrNull { abs(it.visibleBounds.centerY() - device.displayHeight / 2) }
                    ?: error("The resolved page has no visible body text: $selectedPage")
                bodyText.longClick()
                assertTrue("Long press must still select text", device.wait(Until.hasObject(By.text(Pattern.compile("Copy|复制"))), TIMEOUT))
                assertFalse("Text selection must not toggle the menu", device.hasObject(By.desc("Book position")))
                assertEquals("Text selection must not turn the page", selectedPage, currentFlipPageTag())
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
            val originalText = centeredVisibleText("Benchmark progress paragraph")
            listOf("Settings", "Contents", "Next Chapter").forEach { tool ->
                stableBookPositionBounds()
                var target = assertText(tool)
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
                assertEquals("The retiring tool must not navigate: $tool", originalText, centeredVisibleText("Benchmark progress paragraph"))
                assertEquals("An exiting Next Chapter tool must not publish chapter two", -1f, persistedChapterProgress("benchmark-chapter-2"))
                fixture.tapAt(device.displayWidth / 2f, device.displayHeight / 2f)
                assertDescription("Book position")
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
            assertTrue(device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
            assertTextContains("Benchmark rapid chapter 6 paragraph 30.")
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
            val originalText = centeredVisibleText("Benchmark progress paragraph")
            val originalBounds = assertText(originalText).visibleBounds
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
                assertTrue(device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
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
                    returnText = centeredVisibleText(returnPrefix)
                    returnBounds = assertText(returnText).visibleBounds
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
                    assertTextContains("Benchmark rapid chapter 10 paragraph")
                    assertEquals("Cancelled A must not publish reading progress", -1f, persistedChapterProgress(ReaderSeekLoadingFixture.CHAPTER_A))
                }
            }

            if (!secondGestureHasOrigin) {
                assertFalse("Completing B must not create a return action", device.hasObject(By.desc("Return to the position before jumping")))
                returnPrefix = "Benchmark rapid chapter 10 paragraph"
                returnChapter = ReaderSeekLoadingFixture.CHAPTER_B
                returnText = centeredVisibleText(returnPrefix)
                returnBounds = assertText(returnText).visibleBounds
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
                    assertTextContains("Benchmark rapid chapter 6 paragraph")
                    fixture.drain()
                    assertMarkerDrawsKeepOrigin(fixture, newOrigin.sequence, newOrigin, completedBProgress)
                }
            }

            clickDescription("Return to the position before jumping")
            assertTrue(device.wait(Until.gone(By.desc("Return to the position before jumping")), TIMEOUT))
            assertEquals(returnText, waitForCenteredVisibleText(returnPrefix, returnText))
            assertEquals("Return must restore the new session's exact offset", returnBounds, assertText(returnText).visibleBounds)
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
        assertTextContains(prefix)
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
            val bounds = assertDescription("Book position").visibleBounds
            if (bounds.width() > 0 && bounds.height() > 0 && bounds == previous) stableSamples++ else stableSamples = 1
            if (stableSamples >= 3) return bounds
            previous = bounds
            SystemClock.sleep(100)
        }
        error("Book position did not settle before the controlled gesture: $previous")
    }

    @Test
    fun scrollingReaderSameChapterSeekAfterRotationReturnsToExactPosition() {
        seedSeekFixture(paragraphCount = 300, singleChapter = true)
        try {
            device.setOrientationNatural()
            openReaderWithControls()
            swipeBookFraction(assertDescription("Book position").visibleBounds, 0.75f)
            waitForChapterProgressInRange(0.55f, 0.95f)

            // Build a portrait geometry cache, then require a new target and exact return using
            // landscape geometry. Comparing anchors within landscape avoids assuming that a
            // paragraph has the same pixel bounds across two different layouts.
            device.setOrientationLeft()
            assertTrue(device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
            assertTextContains("Benchmark progress paragraph")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertDescription("Book position")
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

                swipeBookFraction(assertDescription("Book position").visibleBounds, 0.45f)
                waitForChapterProgressInRange(0.30f, 0.65f)
                device.waitForIdle()
                val anchorText = centeredVisibleText("Benchmark progress paragraph")
                val anchorBounds = assertText(anchorText).visibleBounds

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

                swipeBookFraction(assertDescription("Book position").visibleBounds, 0.35f)
                waitForChapterProgressInRange(0.20f, 0.50f)
                device.waitForIdle()
                val oldAnchorText = centeredVisibleText("Benchmark progress paragraph")
                val oldAnchorNumber = paragraphNumber(oldAnchorText)
                swipeReaderDown(times = 3)
                assertTrue(
                    "The user gesture must move well beyond the old seek anchor before releasing the image",
                    paragraphNumber(centeredVisibleText("Benchmark progress paragraph")) > oldAnchorNumber + 3,
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
                        paragraphNumber(centeredVisibleText("Benchmark progress paragraph")) > oldAnchorNumber + 2,
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
            assertTextContains("Benchmark chapter two progress paragraph")
            assertDescription("Return to the position before jumping")

            device.setOrientationLeft()
            assertTrue(
                "Rotation must hide the reader menu",
                device.wait(Until.gone(By.desc("Book position")), TIMEOUT),
            )
            assertTextContains("Benchmark chapter two progress paragraph")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertDescription("Book position")
            assertFalse(
                "Reopening the menu after rotation must not revive the old return position",
                device.wait(
                    Until.hasObject(By.desc("Return to the position before jumping")),
                    1_500L,
                ),
            )

            seekBookPosition(toEnd = false)
            assertTextContains("Benchmark progress paragraph")
            assertDescription("Return to the position before jumping")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertTrue(device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertDescription("Book position")
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
        val originalText = centeredVisibleText("Benchmark progress paragraph")
        val originalBounds = assertText(originalText).visibleBounds
        val originalProgress = persistedChapterProgress()

        seekBookPosition(toEnd = true)
        assertText("章节为空")
        assertText("该章节没有可阅读的内容")
        assertDescription("Return to the position before jumping")
        assertEquals("An empty chapter must not receive reading progress", -1f, persistedChapterProgress("benchmark-chapter-2"))
        assertEquals("A failed seek must retain the last successful progress", originalProgress, persistedChapterProgress())

        clickText("Retry")
        assertText("章节为空")
        assertDescription("Book position")
        assertDescription("Return to the position before jumping")

        clickDescription("Return to the position before jumping")
        assertTrue(
            "The return position should clear after restoring the original chapter",
            device.wait(Until.gone(By.desc("Return to the position before jumping")), TIMEOUT),
        )
        assertText("Benchmark Chapter One")
        assertEquals(
            originalText,
            waitForCenteredVisibleText("Benchmark progress paragraph", originalText),
        )
        assertEquals("Return must restore the exact text position", originalBounds, assertText(originalText).visibleBounds)
        assertTextNotVisible("章节为空")
    }

    @Test
    fun pagedReaderSeeksAcrossVolumesAndReturnsToOriginalPosition() {
        openReaderWithControls()
        clickText("Settings")
        clickText("Controls")
        clickText("Page Turn Mode")
        assertText("Volume Key Navigation")
        pressBack()
        assertTextContains("Benchmark progress paragraph")
        assertSeekAndReturn(paged = true)
    }

    private fun openReaderWithControls() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertDescription("Book position")
    }

    private fun assertSeekAndReturn(paged: Boolean = false) {
        val originalText = centeredVisibleText("Benchmark progress paragraph")
        val originalBounds = assertText(originalText).visibleBounds
        val originalPage = if (paged) currentFlipPageTag() else null
        seekBookPosition(toEnd = true)
        assertText("Benchmark Chapter Two")
        assertTextContains("Benchmark chapter two progress paragraph")
        assertCompletedChapterProgress("benchmark-chapter-2")
        clickDescription("Return to the position before jumping")
        assertTrue(
            "Return marker should clear only after the original reading position is restored",
            device.wait(
                Until.gone(By.desc("Return to the position before jumping")),
                TIMEOUT,
            ),
        )
        assertText("Benchmark Chapter One")
        assertEquals(
            originalText,
            waitForCenteredVisibleText("Benchmark progress paragraph", originalText),
        )
        assertEquals("Return must restore the exact text position", originalBounds, assertText(originalText).visibleBounds)
        if (originalPage != null) assertEquals(originalPage, waitForFlipPage(originalPage))

        clickText("Contents")
        assertText("Select Chapter")
        assertText("Benchmark Chapter One")
        assertTextNotVisible("Benchmark Chapter Two")
    }

    private fun seekBookPosition(toEnd: Boolean) {
        val track = assertDescription("Book position").visibleBounds
        device.swipe(
            track.centerX(), track.centerY(),
            if (toEnd) track.right - 1 else track.left + 1, track.centerY(), 30,
        )
    }

    private fun seedSeekFixture(
        paragraphCount: Int,
        singleChapter: Boolean = false,
        imageUri: String? = null,
    ) {
        // URI is generated by the loopback fixture and contains no shell metacharacters.
        val imageArgument = imageUri?.let { " --es imageUri $it" }.orEmpty()
        val result = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $SEED_ACTION --ei paragraphCount $paragraphCount --ez singleChapter $singleChapter$imageArgument"
        )
        assertTrue("Seek fixture was not seeded: $result", result.contains("seed=SUCCEEDED"))
    }

    private fun swipeBookFraction(track: Rect, fraction: Float, steps: Int = 30) {
        device.swipe(
            track.centerX(), track.centerY(),
            track.left + (track.width() * fraction).toInt(), track.centerY(), steps,
        )
    }

    private fun waitForChapterProgressInRange(minimum: Float, maximum: Float) {
        repeat(40) {
            if (persistedChapterProgress() in minimum..maximum) return
            SystemClock.sleep(250)
        }
        assertTrue(
            "Expected chapter-one progress in $minimum..$maximum, got ${persistedChapterProgress()}",
            persistedChapterProgress() in minimum..maximum,
        )
    }

    private fun assertSameChapterSeekAndReturn() {
        swipeBookFraction(assertDescription("Book position").visibleBounds, 0.10f)
        waitForChapterProgressInRange(0f, 0.20f)
        // Begin a fresh origin session at the warmed target rather than retaining the first
        // setup jump as the return position.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertTrue(device.wait(Until.gone(By.desc("Book position")), TIMEOUT))
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertDescription("Book position")
        val originalText = centeredVisibleText("Benchmark progress paragraph")
        val originalBounds = assertText(originalText).visibleBounds

        swipeBookFraction(assertDescription("Book position").visibleBounds, 0.75f)
        waitForChapterProgressInRange(0.55f, 0.95f)
        assertNotEquals("The seek must actually move within the chapter", originalText, centeredVisibleText("Benchmark progress paragraph"))
        assertEquals("Same-chapter seeks must not update chapter two", -1f, persistedChapterProgress("benchmark-chapter-2"))
        clickDescription("Return to the position before jumping")
        assertTrue(device.wait(Until.gone(By.desc("Return to the position before jumping")), TIMEOUT))
        assertEquals(originalText, waitForCenteredVisibleText("Benchmark progress paragraph", originalText))
        assertEquals("Same-chapter return must restore the exact text position", originalBounds, assertText(originalText).visibleBounds)
        waitForChapterProgressInRange(0f, 0.20f)
    }

    private fun waitForStableTextBounds(text: String, expected: Rect) {
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

    private fun paragraphNumber(text: String): Int =
        Regex("Benchmark progress paragraph (\\d+)\\.").find(text)
            ?.groupValues?.get(1)?.toInt()
            ?: error("Missing fixture paragraph number: $text")

    @Test
    fun readerSettingsExposeAllGroupsAndPageModes() {
        openReaderWithControls()
        clickText("Settings")

        assertText("Reader Settings")
        assertText("Appearance")
        assertText("Controls")
        assertText("Margins")
        assertText("Keep Screen On")
        assertText("Hide Status Bar")
        assertText("Reader Style")

        clickText("Controls")
        assertText("Page Turn Mode")
        assertText("Back Prevention")
    }

    @Test
    fun scrollingAndVolumeNavigationKeepReaderResponsive() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")

        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.75).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.25).toInt(),
            30,
        )
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP)
        assertTextContains("Benchmark progress paragraph")
    }

    @Test
    fun scrollingModeLongSelectableChapterRemainsResponsive() {
        val fixtureResult = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.EXTEND_RAPID_CHAPTER_CHAIN"
        )
        assertTrue(
            "Failed to create the long scrolling fixture: $fixtureResult",
            fixtureResult.contains("rapid-chapters=SUCCEEDED"),
        )

        openBookDetails()
        clickScrolledText("Benchmark Chapter 6")
        assertTextContains("Benchmark rapid chapter 6 paragraph")

        repeat(24) {
            device.swipe(
                device.displayWidth / 2,
                (device.displayHeight * 0.78).toInt(),
                device.displayWidth / 2,
                (device.displayHeight * 0.22).toInt(),
                4,
            )
        }
        device.waitForIdle()
        assertForegroundPackage(TARGET_PACKAGE)

        repeat(3) {
            if (device.hasObject(By.text("Settings"))) return@repeat
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            device.wait(Until.hasObject(By.text("Settings")), 1_000L)
        }
        clickText("Settings")
        assertText("Reader Settings")
    }

    @Test
    fun bookInformationSheetShowsEveryMetadataGroup() {
        openBookDetails()
        clickScrolledText("Info")

        assertText("Title")
        assertText("Benchmark Sample Novel")
        assertText("ID")
        assertText("9999999")
        assertText("Author")
        assertText("Benchmark Author")
        scrollToText("Stats")
        assertTextContains("12")
        assertTextContains("2 chapters")
    }

    @Test
    fun epubExportOptionsSupportVolumeAndSelectionBranches() {
        openBookDetails()
        clickDescription("export")

        assertText("Export as Epub")
        assertText("Include images")
        assertText("Export by volumes")
        clickText("Export by volumes")
        assertText("Benchmark Volume")
        assertText("Benchmark Bonus Volume")
        clickText("Benchmark Volume")
        assertText("Select All")
        clickText("Select All")
        assertTextNotVisible("Select All")
    }

    @Test
    fun markReadDialogSupportsRangeSelectionAndConfirmation() {
        openBookDetails()
        clickDescription("more")
        clickText("Mark as read…")

        assertText("全部章节")
        assertText("选择范围")
        clickText("选择范围")
        assertText("Benchmark Chapter One")
        assertText("Benchmark Chapter Two")
        clickScrolledText("Benchmark Chapter One")
        clickText("Benchmark Chapter Two")
        assertText("标记已选 2 章为已读")
        clickText("标记已选 2 章为已读")

        assertText("Benchmark Sample Novel")
        device.waitForIdle(2_000)
        restartApp()
        openBottomNavigation("Bookshelf")
        clickText("Benchmark Sample Novel")
        scrollToText("Finished Reading")
    }

    @Test
    fun markReadDialogCanBeCancelledWithoutChangingProgress() {
        openBookDetails()
        clickDescription("more")
        clickText("Mark as read…")
        clickText("取消")

        assertText("Benchmark Sample Novel")
        clickDescription("more")
        clickText("Mark as read…")
        assertText("标记全部为已读")
    }

    @Test
    fun readerModeSwitchesExposeConditionalControlsAndMargins() {
        openReaderWithControls()
        clickText("Settings")

        clickText("Controls")
        assertText("Page Turn Mode")
        assertText("Switch between scrolling mode and page turn mode")
        clickText("Page Turn Mode")
        assertText("Volume Key Navigation")
        scrollToText("Tap to Turn Pages")
        assertText("Page Turn Animation")

        // Scrolling the settings content can still be expanding the bottom sheet. Observe
        // its moving tab before one click; do not retry the click or relax the page assertions.
        val marginsDeadline = SystemClock.uptimeMillis() + 5_000
        var marginsBounds: Rect? = null
        var stableMarginsSamples = 0
        while (SystemClock.uptimeMillis() < marginsDeadline && stableMarginsSamples < 3) {
            val bounds = try {
                device.findObject(By.text("Margins"))?.visibleBounds
                    ?.takeIf { it.width() > 0 && it.height() > 0 }
            } catch (_: StaleObjectException) {
                null
            }
            stableMarginsSamples = when {
                bounds == null -> 0
                bounds == marginsBounds -> stableMarginsSamples + 1
                else -> 1
            }
            marginsBounds = bounds?.let { Rect(it) }
            if (stableMarginsSamples < 3) SystemClock.sleep(100)
        }
        assertTrue(
            "Margins tab did not reach three stable visible bounds within 5 seconds: $marginsBounds",
            stableMarginsSamples == 3 && marginsBounds != null,
        )
        val readyMarginsBounds = requireNotNull(marginsBounds)
        device.click(readyMarginsBounds.centerX(), readyMarginsBounds.centerY())
        device.waitForIdle()
        assertText("Auto Margin Adjustment")
        clickText("Auto Margin Adjustment")
        assertText("Top Margin")
        assertText("Bottom Margin")
        scrollToText("Right Margin")
        assertText("Left Margin")
    }

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
            clickText("Settings")
            clickText("Margins")
            clickText("Auto Margin Adjustment")
            setReaderMargin("Left Margin", 1f)
            setReaderMargin("Right Margin", 1f)
            clickText("Controls")
            clickText("Page Turn Mode")
            pressBack()
            assertTrue(device.wait(Until.gone(By.text("Reader Settings")), TIMEOUT))
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            device.waitForIdle()

            val firstPage = currentFlipPageTag()
            swipePage(forward = true)
            assertNotEquals(
                "Fractional-density margins must allow pagination and normal page turns",
                firstPage,
                waitForDifferentFlipPage(firstPage),
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
    fun pageTurnModeSeamlesslyTransitionsBetweenChaptersInBothDirections() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")

        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickText("Settings")
        clickText("Controls")
        clickText("Page Turn Mode")
        assertText("Volume Key Navigation")

        pressBack()
        device.wait(Until.gone(By.text("Reader Settings")), TIMEOUT)
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        device.waitForIdle()

        turnPagesUntilText("Benchmark chapter two start marker", forward = true)
        assertTextContains("Benchmark chapter two start marker")
        assertForegroundPackage(TARGET_PACKAGE)

        turnPagesUntilText("Benchmark chapter one end marker", forward = false)
        assertTextContains("Benchmark chapter one end marker")
        assertForegroundPackage(TARGET_PACKAGE)
    }

    @Test
    fun pageTurnModeHandlesRepeatedSeamlessRoundTripsAndBookBoundaries() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode()

        waitForFlipChapter("benchmark-chapter-1")
        val firstPage = reachPageBoundary(forward = false)
        repeat(3) {
            swipePage(forward = false)
            assertEquals("Swiping before the first book page must be a no-op", firstPage, currentFlipPageTag())
            assertTrue(currentFlipChapterTag().endsWith("flip-chapter-benchmark-chapter-1"))
        }

        turnPagesUntilChapter("benchmark-chapter-2", forward = true)
        waitForFlipChapter("benchmark-chapter-2")
        assertTextContains("Benchmark chapter two start marker")
        assertCompletedChapterProgress("benchmark-chapter-1", maximum = true)
        repeat(3) {
            turnPagesUntilChapter("benchmark-chapter-1", forward = false)
            waitForFlipChapter("benchmark-chapter-1")
            assertTextContains("Benchmark chapter one end marker")

            if (it == 0) {
                val chapterEnd = currentFlipPageTag()
                swipePage(forward = false)
                assertNotEquals(chapterEnd, waitForDifferentFlipPage(chapterEnd))
                // The page tag changes before the debounced Room save finishes.
                val rereadDeadline = SystemClock.uptimeMillis() + 10_000
                var rereadProgress = persistedChapterProgress()
                while (!(rereadProgress >= 0f && rereadProgress < 1f) &&
                    SystemClock.uptimeMillis() < rereadDeadline) {
                    SystemClock.sleep(250)
                    rereadProgress = persistedChapterProgress()
                }
                assertTrue(
                    "Rereading must lower current progress, got $rereadProgress; ${currentFlipStateDescription()}",
                    rereadProgress >= 0f && rereadProgress < 1f,
                )
                assertCompletedChapterProgress("benchmark-chapter-1", maximum = true)
                swipePage(forward = true)
                assertEquals(chapterEnd, waitForFlipPage(chapterEnd))
            }

            swipePage(forward = true)
            waitForFlipChapter("benchmark-chapter-2")
            assertTextContains("Benchmark chapter two start marker")
        }

        val lastPage = reachPageBoundary(forward = true)
        repeat(3) {
            swipePage(forward = true)
            assertEquals("Swiping after the final book page must be a no-op", lastPage, currentFlipPageTag())
            assertTrue(currentFlipChapterTag().endsWith("flip-chapter-benchmark-chapter-2"))
        }

        swipePage(forward = false)
        val pageBeforeLast = currentFlipPageTag()
        assertNotEquals(lastPage, pageBeforeLast)
        swipePage(forward = true)
        assertEquals("The final page must remain reachable after leaving it", lastPage, waitForFlipPage(lastPage))
    }

    @Test
    fun pageTurnModeSurvivesRapidContinuousTurnsAcrossChapters() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode()
        reachPageBoundary(forward = false)

        repeat(24) { swipePage(forward = true, steps = 2, settleMs = 25) }
        waitForFlipPagerIdle()
        val finalBookPage = reachPageBoundary(forward = true)
        assertTrue(
            "Rapid forward turns stopped before chapter two: ${currentFlipStateDescription()}",
            currentFlipChapterTag().endsWith("flip-chapter-benchmark-chapter-2"),
        )
        assertForegroundPackage(TARGET_PACKAGE)

        repeat(24) { swipePage(forward = false, steps = 2, settleMs = 25) }
        waitForFlipPagerIdle()
        val firstBookPage = reachPageBoundary(forward = false)
        assertTrue(
            "Rapid backward turns stopped before chapter one: ${currentFlipStateDescription()}",
            currentFlipChapterTag().endsWith("flip-chapter-benchmark-chapter-1"),
        )
        assertNotEquals(finalBookPage, firstBookPage)
        assertForegroundPackage(TARGET_PACKAGE)

        swipePage(forward = true)
        assertNotEquals(
            "Pager must remain interactive after rapid cross-chapter input",
            firstBookPage,
            waitForDifferentFlipPage(firstBookPage),
        )
    }

    @Test
    fun pageTurnModeWithoutAnimationKeepsSeamlessChapterBoundariesInteractive() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode(disableAnimation = true)

        turnPagesUntilChapter("benchmark-chapter-2", forward = true)
        waitForFlipChapter("benchmark-chapter-2")
        assertTextContains("Benchmark chapter two start marker")

        turnPagesUntilChapter("benchmark-chapter-1", forward = false)
        waitForFlipChapter("benchmark-chapter-1")
        assertTextContains("Benchmark chapter one end marker")

        val chapterOneLastPage = currentFlipPageTag()
        swipePage(forward = true)
        waitForFlipChapter("benchmark-chapter-2")
        swipePage(forward = false)
        waitForFlipChapter("benchmark-chapter-1")
        assertEquals(chapterOneLastPage, waitForFlipPage(chapterOneLastPage))
    }

    @Test
    fun pageTurnModeWithoutAnimationSurvivesRapidTapTurnsAcrossChapter() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode(enableTapToTurn = true, disableAnimation = true)

        repeat(24) {
            device.click(device.displayWidth * 5 / 6, device.displayHeight / 2)
            SystemClock.sleep(20)
        }
        waitForFlipChapter("benchmark-chapter-2")
        waitForFlipPagerIdle()
        assertTrue(
            "Rapid no-animation taps must cross the seamless chapter boundary: " +
                currentFlipStateDescription(),
            currentFlipChapterTag().endsWith("flip-chapter-benchmark-chapter-2"),
        )

        val finalPage = reachPageBoundary(forward = true)
        device.click(device.displayWidth / 6, device.displayHeight / 2)
        assertNotEquals(
            "The pager must still accept taps after the rapid seamless transition",
            finalPage,
            waitForDifferentFlipPage(finalPage),
        )
    }

    @Test
    fun pageTurnModeWithoutAnimationSurvivesRapidTapTurnsAcrossManyChapters() {
        val fixtureResult = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.EXTEND_RAPID_CHAPTER_CHAIN"
        )
        assertTrue(
            "Failed to extend the rapid-turn chapter chain: $fixtureResult",
            fixtureResult.contains("rapid-chapters=SUCCEEDED"),
        )

        openBookDetails()
        clickScrolledText("Benchmark Chapter Two")
        assertTextContains("Benchmark chapter two progress paragraph")
        enablePageTurnMode(enableTapToTurn = true, disableAnimation = true)

        repeat(320) {
            device.click(device.displayWidth * 5 / 6, device.displayHeight / 2)
            SystemClock.sleep(20)
        }

        val reachedChapter = currentFlipChapterTag()
        val reachedChapterNumber = Regex("benchmark-chapter-(\\d+)$")
            .find(reachedChapter)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: -1
        SystemClock.sleep(1_000)
        waitForFlipPagerIdle()
        assertTrue(
            "Rapid taps must cross eight seamless chapter boundaries: " +
                currentFlipStateDescription(),
            reachedChapterNumber >= 10,
        )

        val lastPage = reachPageBoundary(forward = true)
        device.click(device.displayWidth / 6, device.displayHeight / 2)
        assertNotEquals(
            "The pager must still accept reverse taps after crossing several chapters",
            lastPage,
            waitForDifferentFlipPage(lastPage),
        )
    }

    @Test
    fun readerAppearanceTogglesPersistAcrossReaderReentry() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        val originalText = centeredVisibleText("Benchmark progress paragraph")
        val originalBounds = assertText(originalText).visibleBounds
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickText("Settings")
        clickText("Reader Style")
        assertText("Paper")
        pressBack()
        assertEquals(
            originalText,
            waitForCenteredVisibleText("Benchmark progress paragraph", originalText),
        )
        assertEquals("Reader Style navigation must retain the exact text position", originalBounds, assertText(originalText).visibleBounds)
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickText("Settings")
        assertText("Keep Screen On")
        clickText("Keep Screen On")
        assertFirstSwitchChecked(true)

        pressBack()
        pressBack()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickText("Settings")
        assertFirstSwitchChecked(true)
    }

    @Test
    fun scrollingModeRestoresTheSameComponentAfterProcessRestart() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")

        swipeReaderDown(times = 1)
        val expectedAnchor = centeredVisibleText("Benchmark progress paragraph")

        restartApp()
        clickText("Resume Last Reading")
        assertTextContains("Benchmark progress paragraph")
        SystemClock.sleep(1_000)

        assertEquals(
            "Scrolling mode should restore the same component near the viewport center",
            expectedAnchor,
            waitForCenteredVisibleText("Benchmark progress paragraph", expectedAnchor),
        )
    }

    @Test
    fun pagedHistoryUsesNewScrollProgressAfterSwitchingModeInAnotherChapter() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode()
        waitForFlipChapter("benchmark-chapter-1")
        val earlyPage = currentFlipPageTag()
        waitForPersistedChapterLocationHash("benchmark-chapter-1")
        val earlyProgress = persistedChapterProgress()
        val earlyParagraph = paragraphNumber(centeredVisibleText("Benchmark progress paragraph"))

        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickText("Settings")
        clickText("Controls")
        clickText("Page Turn Mode")
        pressBack()
        assertTrue(device.wait(Until.gone(By.text("Reader Settings")), TIMEOUT))
        assertDescription("Book position")
        swipeBookFraction(assertDescription("Book position").visibleBounds, 0.38f)
        waitForChapterProgressInRange(0.65f, 0.90f)
        val scrollProgress = persistedChapterProgress()
        assertTrue("The scroll must move well beyond the old page", scrollProgress > earlyProgress + 0.3f)
        assertTrue(
            "The visible text must advance along with persisted progress",
            paragraphNumber(centeredVisibleText("Benchmark progress paragraph")) > earlyParagraph + 5,
        )

        // Change modes in another chapter so reopening chapter one must use persisted
        // history. A direct mode switch in chapter one would use an Exact anchor instead.
        clickText("Contents")
        assertText("Select Chapter")
        clickCenter(scrollToText("Benchmark Bonus Volume"))
        clickCenter(scrollToText("Benchmark Chapter Two"))
        assertTextContains("Benchmark chapter two progress paragraph")
        if (!device.hasObject(By.text("Settings"))) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        clickText("Settings")
        clickText("Controls")
        clickText("Page Turn Mode")
        pressBack()
        assertTrue(device.wait(Until.gone(By.text("Reader Settings")), TIMEOUT))
        waitForFlipChapter("benchmark-chapter-2")
        clickText("Contents")
        assertText("Select Chapter")
        clickCenter(scrollToText("Benchmark Volume"))
        clickCenter(scrollToText("Benchmark Chapter One"))
        waitForFlipChapter("benchmark-chapter-1")

        assertNotEquals("Old page history must not override newer scrolling progress", earlyPage, currentFlipPageTag())
        waitForChapterProgressInRange(scrollProgress - 0.10f, scrollProgress + 0.10f)
        assertTrue(
            "History restoration must show the later text, not only preserve the saved number",
            paragraphNumber(centeredVisibleText("Benchmark progress paragraph")) > earlyParagraph + 5,
        )
    }

    @Test
    fun pageTurnModeRestoresTheSameComponentAfterProcessRestart() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode()

        repeat(1) {
            device.swipe(
                device.displayWidth * 5 / 6,
                device.displayHeight / 2,
                device.displayWidth / 6,
                device.displayHeight / 2,
                20,
            )
            SystemClock.sleep(500)
        }
        SystemClock.sleep(1_000)
        val expectedAnchor = centeredVisibleText("Benchmark progress paragraph")

        restartApp()
        clickText("Resume Last Reading")
        assertTextContains("Benchmark progress paragraph")

        assertEquals(
            "Page-turn mode should restore the page containing the saved component hash",
            expectedAnchor,
            waitForCenteredVisibleText("Benchmark progress paragraph", expectedAnchor),
        )

        device.swipe(
            device.displayWidth * 5 / 6,
            device.displayHeight / 2,
            device.displayWidth / 6,
            device.displayHeight / 2,
            20,
        )
        SystemClock.sleep(1_000)
        assertNotEquals(
            "The pager must remain interactive after restoring saved progress",
            expectedAnchor,
            centeredVisibleText("Benchmark progress paragraph"),
        )
    }

    @Test
    fun pageTurnModeCanTurnAfterReturningToDirectoryAndReenteringSameChapter() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode()
        val initialProgress = persistedChapterProgress()
        val initialPage = currentFlipPageTag()

        device.swipe(
            device.displayWidth * 5 / 6,
            device.displayHeight / 2,
            device.displayWidth / 6,
            device.displayHeight / 2,
            20,
        )
        val savedPage = waitForDifferentFlipPage(initialPage)
        val savedProgress = waitForProgressAfter(initialProgress)
        assertTrue(
            "The setup must persist a page after the initially restored page",
            savedProgress > initialProgress,
        )

        pressBack()
        assertText("Benchmark Sample Novel")
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        assertEquals(
            "The restored picture and pagerState must reference the same saved page",
            savedPage,
            waitForFlipPage(savedPage),
        )
        SystemClock.sleep(500)
        assertEquals(savedProgress, persistedChapterProgress())

        pressBack()
        assertText("Benchmark Sample Novel")
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        assertEquals(savedPage, waitForFlipPage(savedPage))
        SystemClock.sleep(500)
        assertEquals(savedProgress, persistedChapterProgress())

        device.swipe(
            device.displayWidth * 5 / 6,
            device.displayHeight / 2,
            device.displayWidth / 6,
            device.displayHeight / 2,
            20,
        )
        val advancedPage = waitForDifferentFlipPage(savedPage)
        val advancedProgress = waitForProgressAfter(savedProgress)
        assertTrue(
            "The reused pager must remain interactive after same-chapter restoration",
            advancedProgress > savedProgress,
        )

        shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.REEMIT_CHAPTER"
        )
        SystemClock.sleep(1_000)
        assertEquals(advancedPage, currentFlipPageTag())
        assertEquals(
            "A repeated chapter Flow emission must not replay the original restore target",
            advancedProgress,
            persistedChapterProgress(),
        )

        device.swipe(
            device.displayWidth * 5 / 6,
            device.displayHeight / 2,
            device.displayWidth / 6,
            device.displayHeight / 2,
            20,
        )
        val finalPage = waitForDifferentFlipPage(advancedPage)
        val finalProgress = waitForProgressAfter(advancedProgress)
        assertTrue(
            "The pager must keep advancing after the repeated chapter emission",
            finalProgress > advancedProgress,
        )
        assertNotEquals(advancedPage, finalPage)
    }

    @Test
    fun pageTurnModeRespondsToVolumeKeys() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode(enableVolumeKeys = true)

        val initialPage = currentFlipPageTag()
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        val nextPage = waitForDifferentFlipPage(initialPage)
        assertNotEquals(initialPage, nextPage)

        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(initialPage, waitForFlipPage(initialPage))
    }

    @Test
    fun pageTurnModeWithoutAnimationRemainsInteractiveAfterRapidVolumeInput() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode(enableVolumeKeys = true, disableAnimation = true)

        repeat(24) {
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        }
        waitForFlipPagerIdle()
        turnPagesUntilChapter("benchmark-chapter-2", forward = true)
        val lastPage = reachPageBoundary(forward = true)

        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP)
        assertNotEquals(
            "The book-end long-press producer must not keep overwriting reverse input",
            lastPage,
            waitForDifferentFlipPage(lastPage),
        )
        assertForegroundPackage(TARGET_PACKAGE)
    }

    @Test
    fun readerTextCanBeSelectedAndCopied() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        val paragraph = assertTextContains("Benchmark progress paragraph")

        paragraph.longClick()
        assertTrue(
            "Long-pressing reader text must open the system copy action",
            device.wait(
                Until.hasObject(By.text(Pattern.compile("Copy|复制"))),
                TIMEOUT,
            ),
        )
        assertFalse("Text selection must not open the reader menu", device.hasObject(By.desc("Book position")))
    }

    @Test
    fun selectingTextNearPageTurnZoneDoesNotTurnThePage() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark progress paragraph")
        enablePageTurnMode(enableTapToTurn = true, disableAnimation = true)

        val initialPage = currentFlipPageTag()
        val paragraph = assertTextContains("Benchmark progress paragraph")
        val bounds = paragraph.visibleBounds
        val selectionX = minOf(device.displayWidth * 5 / 6, bounds.right - 4)
        val selectionY = bounds.centerY()
        shell("input swipe $selectionX $selectionY $selectionX $selectionY 800")

        assertTrue(
            "Long-pressing text in the next-page tap zone must open Copy",
            device.wait(
                Until.hasObject(By.text(Pattern.compile("Copy|复制"))),
                TIMEOUT,
            ),
        )
        assertEquals(
            "Releasing a text-selection long press must not turn the page",
            initialPage,
            waitForFlipPage(initialPage),
        )
    }

    @Test
    fun progressAndLastChapterRemainIndependentAcrossMultipleBooks() {
        openBookDetails()
        clickScrolledText("Benchmark Chapter One")
        swipeReaderDown(times = 2)
        val firstBookAnchor = centeredVisibleText("Benchmark progress paragraph")

        restartApp()
        navigateToBookDetails("Second Benchmark Novel")
        clickScrolledText("Second Book Chapter Two")
        assertTextContains("Second book chapter two progress paragraph")
        swipeReaderDown(times = 2)
        val secondBookAnchor = centeredVisibleText("Second book chapter two progress paragraph")

        restartApp()
        navigateToBookDetails("Benchmark Sample Novel")
        clickScrolledText("Benchmark Chapter One")
        SystemClock.sleep(1_000)
        assertEquals(
            "Opening the first book again should restore its own chapter progress",
            firstBookAnchor,
            centeredVisibleText("Benchmark progress paragraph"),
        )

        restartApp()
        navigateToBookDetails("Second Benchmark Novel")
        clickScrolledText("Second Book Chapter Two")
        SystemClock.sleep(1_000)
        assertEquals(
            "Opening the second book again should restore its chapter and independent progress",
            secondBookAnchor,
            centeredVisibleText("Second book chapter two progress paragraph"),
        )
    }
}
