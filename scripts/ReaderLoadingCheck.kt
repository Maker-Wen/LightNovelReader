import androidx.compose.foundation.lazy.LazyListItemInfo
import com.github.michaelbull.result.*
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.LoadingScenario
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.*
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollGeometryKey
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

typealias ChapterResult = Result<ChapterContent, WebRequestError>
private const val BODY = "one|two|three"
private fun chapter(id: String = "a", body: String = BODY, title: String = id,
                    previous: String? = null, next: String? = null) = ChapterContent(id, title, body, previous, next)
private suspend fun waitFor(label: String, ready: () -> Boolean) {
    try { withTimeout(3_000) { while (!ready()) delay(2) } }
    catch (error: TimeoutCancellationException) { error("Timed out: $label") }
}

private class ReaderCheck(val mode: String, private val savedHash: Int? = null) : AutoCloseable {
    val repository = BookRepository()
    var historyOverride: (suspend (String, String) -> Pair<Float, Int?>)? = null
    private suspend fun readingHistory(bookId: String, chapterId: String): Pair<Float, Int?> =
        historyOverride?.invoke(bookId, chapterId)
            ?: ((repository.getUserReadingData(bookId).currentChapterReadingProgressMap[chapterId] ?: 0f) to savedHash)
    val reports = mutableListOf<Pair<Long, ReaderPosition>>()
    val readProgress = mutableListOf<Float>()
    val indexes = mutableListOf<List<ReaderAnchor>>()
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val engine: ContentViewModel = if (mode == "flip") FlipPageContentViewModel(
        repository, parent, ContentComponentRepository(), { request, position, _, progress ->
            reports += request to position; readProgress += progress
        }, {}, { _, _, _, anchors -> indexes += anchors }, {}, getReadingHistory = ::readingHistory,
    ) else ScrollContentViewModel(
        repository, parent, ContentComponentRepository(),
        { request, position, _, progress ->
            reports += request to position; readProgress += progress
        }, {}, { _, _, _, _ -> }, {}, getReadingHistory = ::readingHistory,
    )
    var layout = ReaderPaginationLayout(mode = mode)
    val current get() = engine.uiState.readingChapterContent?.get()

    init {
        engine.changeBookId("book")
        when (val vm = engine) {
            is FlipPageContentViewModel -> vm.uiState.updateLayout(layout)
            is ScrollContentViewModel -> vm.uiState.setPaginationLayout(layout)
        }
    }

    fun add(value: ChapterResult, id: String = value.get()?.id ?: "a", holdLocal: Boolean = false) =
        LoadingScenario(value, holdLocal).also { repository.scenarios[id] = it }

    suspend fun open(id: String = "a", target: ChapterPosition? = ChapterPosition.Relative(.5f), request: Long = 1) {
        engine.changeChapter(id, target, request)
        waitFor("$mode $id local result") { current?.id == id && repository.scenarios.getValue(id).emitted.get() >= 1 }
    }

    /** Feed synthetic, fully measured non-text components; real geometry/position code consumes them. */
    fun measure(headerHeight: Int = 100) {
        val content = requireNotNull(current)
        when (val vm = engine) {
            is FlipPageContentViewModel -> {
                vm.uiState.pagerState.updatePageCount(content.content.size)
                resolveHistory(content.content.size, complete = true)
                vm.uiState.updatePagination(content.id, content.contentKey, layout,
                    content.content.indices.map { ReaderAnchor(it) }, content.content.map { it.hashCode() })
            }
            is ScrollContentViewModel -> {
                val state = vm.uiState.lazyListState
                val offset = state.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0
                state.layoutInfo.totalItemsCount = 3
                state.layoutInfo.visibleItemsInfo = listOf(LazyListItemInfo(content.id, offset, headerHeight + content.content.size * 400))
                val key = ScrollGeometryKey("book", content.id, content.contentKey, content.title, layout.key)
                vm.uiState.setHeaderHeight(key, headerHeight)
                content.content.indices.forEach { vm.uiState.setComponentSize(key, it, 400, 0f) }
            }
        }
    }

    /** Simulate the renderer locating history in the synthetic one-component-per-page layout. */
    fun resolveHistory(count: Int, complete: Boolean = false) {
        val pager = (engine as FlipPageContentViewModel).uiState.pagerState
        val hashes = requireNotNull(current).content.take(count).map { it.hashCode() }
        if (pager.restoreTargetHash != null) {
            val fragment = hashes.indexOf(pager.restoreTargetFragmentHash).takeIf { it >= 0 }
            if (pager.restoreTargetFragmentHash != null && fragment == null && !complete) return
            val target = fragment ?: hashes.indexOf(pager.restoreTargetHash).takeIf { it >= 0 }
            if (target == null && !complete) return
            target?.let(pager::moveTo)
            pager.restoreTargetHash = null
            pager.restoreTargetFragmentHash = null
            pager.restoreInProgress = false
            pager.clearTransition()
        }
        if (complete && pager.restoreToEnd) {
            pager.moveTo(count - 1)
            pager.restoreToEnd = false
            pager.clearTransition()
        }
    }

    /** The renderer has resolved only this prefix; the remaining page is still being measured. */
    fun partial(count: Int) {
        val vm = engine as FlipPageContentViewModel
        val content = requireNotNull(current)
        vm.uiState.pagerState.updatePageCount(count)
        vm.uiState.updateResolvedPages(content.id, content.contentKey, layout,
            (0 until count).map { ReaderAnchor(it) },
            content.content.take(count).map { it.hashCode() }, ReaderAnchor(count))
    }

    suspend fun positioned(headerHeight: Int = 100) {
        measure(headerHeight)
        waitFor("$mode positioned ${current?.title}") { !engine.uiState.isPositioning && engine.capturePosition() != null }
    }

    fun neighbor(slot: Int): ChapterContentUiState? = when (val vm = engine) {
        is FlipPageContentViewModel -> (if (slot == 0) vm.uiState.prevChapterContent else vm.uiState.nextChapterContent)?.get()
        is ScrollContentViewModel -> vm.uiState.contentList[slot]?.second?.get()
        else -> null
    }

    suspend fun readToEnd() {
        when (val vm = engine) {
            is FlipPageContentViewModel -> vm.uiState.pagerState.moveTo(2)
            is ScrollContentViewModel -> {
                // A user gesture clears the held anchor; scrollToItem alone is a programmatic move.
                val state = vm.uiState.lazyListState
                state.isScrollInProgress = true
                delay(10) // Let the polling snapshotFlow double observe the gesture start.
                state.scrollToItem(1, 800)
                delay(10)
                state.isScrollInProgress = false
            }
        }
        waitFor("$mode user advanced") { engine.capturePosition()?.fraction == 1f }
    }

    override fun close() { engine.dispose(); parent.cancel() }
}

private suspend fun pendingHistoryOverridesPersistedValues() {
    var progressReads = 0
    var hashReads = 0
    val savedProgress: suspend () -> Float = { progressReads++; .1f }
    val savedHash: suspend () -> Int? = { hashReads++; 17 }
    check(readReaderHistory(.8f, true, null, savedProgress, savedHash) == (.8f to null))
    check(progressReads == 0 && hashReads == 0) { "Pending null must invalidate the saved page hash" }
    check(readReaderHistory(.8f, true, 29, savedProgress, savedHash) == (.8f to 29))
    check(progressReads == 0 && hashReads == 0)
    check(readReaderHistory(.8f, false, null, savedProgress, savedHash) == (.8f to 17))
    check(progressReads == 0 && hashReads == 1) { "Only missing history fields should read storage" }
    check(readReaderHistory(null, false, null, savedProgress, savedHash) == (.1f to 17))
    check(progressReads == 1 && hashReads == 2)
    check(readReaderHistory(null, true, null, savedProgress, savedHash) == (.1f to null))
    check(progressReads == 2 && hashReads == 2)
}

private suspend fun ordinaryEntryUsesLatestPendingHistory(mode: String) {
    ReaderCheck(mode, savedHash = "one".hashCode()).use { check ->
        check.repository.readingData = UserReadingData(mapOf("a" to .1f))
        var historyRequests = 0
        check.historyOverride = { bookId, chapterId ->
            check(bookId == "book" && chapterId == "a")
            historyRequests++
            // This is the production resolver: a newer unsaved progress and explicit null hash
            // must win over an early persisted page from before scrolling or switching modes.
            readReaderHistory(1f, true, null,
                { check.repository.getUserReadingData(bookId).currentChapterReadingProgressMap.getValue(chapterId) },
                { "one".hashCode() })
        }
        check.add(Ok(chapter()))
        check.open(target = null)
        check.positioned()
        check(historyRequests == 1 && check.repository.readingDataRequests.get() == 0)
        check(check.engine.capturePosition()?.fraction == 1f) { "$mode ordinary entry ignored the newer pending history" }
        if (mode == "flip") check(check.engine.capturePosition()?.anchor == ReaderAnchor(2)) {
            "An invalidated old page hash must not override the pending end-of-chapter position"
        }
    }
}

private suspend fun sameChapterSeekKeepsCollection(mode: String) {
    ReaderCheck(mode).use { check ->
        val scenario = check.add(Ok(chapter()))
        check.open(); check.positioned()
        val origin = requireNotNull(check.engine.capturePosition())
        check.engine.changeChapter("a", ChapterPosition.Relative(1f), 2)
        waitFor("$mode same chapter seek") { !check.engine.uiState.isPositioning && check.engine.capturePosition()?.fraction == 1f }
        check.engine.changeChapter("a", ChapterPosition.Exact(origin), 3)
        waitFor("$mode return") { !check.engine.uiState.isPositioning && check.engine.capturePosition()?.fraction == origin.fraction }
        check(scenario.subscriptions.get() == 1 && scenario.active.get() == 1) { "$mode seek/return restarted or cancelled the chapter flow" }
        scenario.remote.complete(Ok(chapter(body = "new|two|three")))
        waitFor("$mode refresh survives seek") { check.current?.contentKey == "new|two|three" }
        check.positioned()
        check(check.engine.capturePosition()?.fraction == origin.fraction)
        check(check.reports.last().first == 3L) { "$mode refresh reused a stale seek request id" }
    }
}

private suspend fun pendingBodyUsesLatestTarget(mode: String, restoreHistory: Boolean) {
    ReaderCheck(mode).use { check ->
        val scenario = check.add(Ok(chapter()), holdLocal = true)
        check.engine.changeChapter("a", if (restoreHistory) null else ChapterPosition.Relative(.5f), 1)
        waitFor("$mode initial body request held") { scenario.subscriptions.get() == 1 }
        check.engine.changeChapter("a", ChapterPosition.Relative(0f), 2)
        val origin = ReaderPosition("a", 1f, ReaderAnchor(2), BODY)
        check.engine.changeChapter("a", ChapterPosition.Exact(origin), 3)
        check.engine.changeChapter("a", ChapterPosition.Exact(origin), 4)
        delay(30)
        check(scenario.subscriptions.get() == 1 && scenario.active.get() == 1) {
            "$mode pending same-chapter seek/return restarted the first body request"
        }
        check(check.current == null && check.reports.isEmpty() && check.engine.uiState.isPositioning)
        scenario.localReady.complete(Unit)
        waitFor("$mode first body released") { check.current?.id == "a" }
        check.positioned()
        check(check.engine.capturePosition()?.fraction == 1f)
        check(check.reports.isNotEmpty() && check.reports.all { it.first == 4L }) {
            "$mode first body reported an obsolete request: ${check.reports}"
        }
        scenario.remote.complete(Ok(chapter(body = "changed|two|three")))
        waitFor("$mode refresh after cold retarget") { check.current?.contentKey == "changed|two|three" }
        check.positioned()
        check(check.engine.capturePosition()?.fraction == 1f && check.reports.last().first == 4L)
    }
}

private suspend fun pendingBodyCancellation(mode: String, action: String) {
    ReaderCheck(mode).use { check ->
        val old = check.add(Ok(chapter()), holdLocal = true)
        check.engine.changeChapter("a", ChapterPosition.Relative(.5f), 1)
        waitFor("$mode first body held before $action") { old.active.get() == 1 }
        check.engine.changeChapter("a", ChapterPosition.Relative(1f), 2)
        when (action) {
            "chapter" -> {
                check.add(Ok(chapter("b")))
                check.open("b", request = 3)
            }
            "book" -> {
                check.engine.changeBookId("other book")
                check.add(Ok(chapter(body = "new book|two|three")))
                check.open(request = 3)
            }
            "dispose" -> check.engine.dispose()
        }
        waitFor("$mode old pending body cancelled by $action") { old.active.get() == 0 }
        old.localReady.complete(Unit)
        old.remote.complete(Ok(chapter(body = "obsolete")))
        delay(30)
        check(old.emitted.get() == 0 && check.reports.none { it.first <= 2L }) {
            "$mode cancelled first body still emitted or reported after $action"
        }
        when (action) {
            "chapter" -> check(check.current?.id == "b")
            "book" -> check(check.current?.contentKey == "new book|two|three")
            "dispose" -> check(check.current == null)
        }
    }
}

private suspend fun failedBodyRetryStartsNewCollection(mode: String, changeLayout: Boolean) {
    ReaderCheck(mode).use { check ->
        val scenario = check.add(Err(WebRequestError("offline", "offline")))
        check.engine.changeChapter("a", ChapterPosition.Relative(.5f), 1)
        waitFor("$mode initial error while refresh remains alive") {
            scenario.emitted.get() == 1 && !check.engine.uiState.isPositioning
        }
        check(scenario.active.get() == 1 && check.current == null)
        if (changeLayout) {
            check.layout = check.layout.copy(width = 600)
            when (val vm = check.engine) {
                is FlipPageContentViewModel -> vm.uiState.updateLayout(check.layout)
                is ScrollContentViewModel -> vm.uiState.setPaginationLayout(check.layout)
            }
        }
        check.engine.uiState.retry()
        waitFor("$mode retry resubscribed") { scenario.subscriptions.get() == 2 && scenario.emitted.get() == 2 }
        check(scenario.active.get() == 1) { "$mode retry kept both chapter collectors" }
        scenario.remote.complete(Ok(chapter()))
        waitFor("$mode retried body ready") { check.current?.id == "a" }
        check.positioned()
        check(check.engine.capturePosition()?.fraction == .5f && check.reports.last().first == 1L)
    }
}

private suspend fun pendingHistoryDoesNotReplaceLatestTarget(mode: String) {
    ReaderCheck(mode).use { check ->
        val scenario = check.add(Ok(chapter()))
        val historyReady = CompletableDeferred<Unit>()
        check.repository.readingDataReady = historyReady
        check.repository.readingData = UserReadingData(mapOf("a" to .5f))
        check.engine.changeChapter("a", null, 1)
        waitFor("$mode saved progress read held") { check.repository.readingDataRequests.get() == 1 }
        check.engine.changeChapter("a", ChapterPosition.Relative(0f), 2)
        check.engine.changeChapter("a", ChapterPosition.Exact(ReaderPosition("a", 1f, ReaderAnchor(2), BODY)), 3)
        delay(30)
        check(scenario.subscriptions.get() == 0) { "$mode retarget bypassed/restarted the pending history load" }
        historyReady.complete(Unit)
        waitFor("$mode body after history read") { check.current?.id == "a" }
        check.positioned()
        check(scenario.subscriptions.get() == 1 && check.repository.readingDataRequests.get() == 1)
        check(check.engine.capturePosition()?.fraction == 1f && check.reports.all { it.first == 3L }) {
            "$mode saved history replaced the newer exact target"
        }
    }
}

private suspend fun seamlessPendingBodyKeepsCollection() {
    ReaderCheck("flip").use { check ->
        check.add(Ok(chapter()))
        val b = check.add(Ok(chapter("b")), holdLocal = true)
        check.open(); check.positioned()
        check.reports.clear()
        val vm = check.engine as FlipPageContentViewModel
        vm.uiState.pagerState.pendingChapterId = "b"
        vm.uiState.pagerState.pendingChapterDirection = 1
        vm.changeChapter("b", ChapterPosition.Relative(0f), 2)
        waitFor("flip seamless next body held") { b.active.get() == 1 }
        check(check.current?.id == "a") { "flip fixture did not preserve the previous body during transition" }
        vm.changeChapter("b", ChapterPosition.Relative(.5f), 3)
        vm.changeChapter("b", ChapterPosition.Exact(ReaderPosition("b", 1f, ReaderAnchor(2), BODY)), 4)
        delay(30)
        check(b.subscriptions.get() == 1 && check.reports.isEmpty())
        b.localReady.complete(Unit)
        waitFor("flip seamless body released") { check.current?.id == "b" }
        check.positioned()
        check(check.engine.capturePosition()?.fraction == 1f && check.reports.all { it.first == 4L })
    }
}

private suspend fun flipTransitionTimeoutIdentitySurvivesBodyArrival() {
    ReaderCheck("flip").use { check ->
        check.add(Ok(chapter()))
        check.add(Ok(chapter("b")))
        check.open(); check.positioned()
        val vm = check.engine as FlipPageContentViewModel
        val pager = vm.uiState.pagerState
        val token = pager.beginChapterTransition("b", 1)
        pager.isAnimating = true
        val inputGeneration = pager.navigationGeneration
        vm.changeChapter("b", ChapterPosition.Relative(0f), 2)
        waitFor("flip boundary body arrives before pagination") { check.current?.id == "b" }
        check(pager.navigationGeneration != inputGeneration) { "Body reset must retire old page input" }
        check(pager.pageCount == 0 && pager.pendingChapterId == "b" && pager.pendingChapterDirection == 1)
        check(pager.ownsChapterTransition(token)) { "Input timeout must still own the preserved unresolved transition" }
        pager.clearTransition()
        check(!pager.ownsChapterTransition(token))
        val replacement = pager.beginChapterTransition("b", 1)
        check(!pager.ownsChapterTransition(token) && pager.ownsChapterTransition(replacement)) {
            "An old timeout must not clear a newer transition with the same chapter and direction"
        }
    }
}

private suspend fun ordinaryNavigationReadsAgain(mode: String) {
    ReaderCheck(mode).use { check ->
        val a = check.add(Ok(chapter()))
        val b = check.add(Ok(chapter("b")))
        check.open(); check.positioned()
        check.open(target = null, request = 2)
        waitFor("$mode ordinary same chapter read") { a.subscriptions.get() == 2 }
        check.positioned()
        check.open("b", request = 3); check.positioned()
        check(a.active.get() == 0 && b.subscriptions.get() == 1)
        a.remote.complete(Ok(chapter(body = "late A")))
        delay(30)
        check(check.current?.id == "b")
        check.engine.dispose()
        waitFor("$mode dispose cancels b") { b.active.get() == 0 }
        b.remote.complete(Ok(chapter("b", body = "late B")))
        delay(30)
        check(check.current?.contentKey == BODY)
    }
}

private suspend fun sameBodyMetadata(mode: String) {
    ReaderCheck(mode).use { check ->
        val a = check.add(Ok(chapter(previous = "p", next = "n")))
        check.add(Ok(chapter("p"))); check.add(Ok(chapter("n"))); check.add(Ok(chapter("q")))
        check.open(); check.positioned()
        val origin = check.engine.capturePosition()
        a.remote.complete(Ok(chapter(next = "q")))
        waitFor("$mode metadata") { check.current?.nextChapter == "q" && check.current?.prevChapter == null }
        check(!check.engine.uiState.isPositioning) { "$mode identical body and title restarted positioning" }
        check(check.engine.capturePosition() == origin)
        waitFor("$mode new neighbor") { check.neighbor(2)?.id == "q" }
    }
}

private suspend fun titleRefresh(mode: String, duringPositioning: Boolean) {
    ReaderCheck(mode).use { check ->
        val a = check.add(Ok(chapter()))
        check.open()
        if (!duringPositioning) check.positioned()
        // When positioning is pending, do not publish old-title measurements.
        a.remote.complete(Ok(chapter(title = "new title")))
        waitFor("$mode title consumed") { check.current?.title == "new title" }
        check.positioned(headerHeight = 200)
        check(check.engine.capturePosition()?.fraction == .5f) { "$mode title refresh lost the latest requested position" }
        check(check.reports.isNotEmpty())
    }
}

private suspend fun changedBodyUsesLatestPosition(mode: String, duringPositioning: Boolean) {
    ReaderCheck(mode).use { check ->
        val a = check.add(Ok(chapter()))
        check.open()
        if (!duringPositioning) { check.positioned(); check.readToEnd() }
        a.remote.complete(Ok(chapter(body = "changed|two|three")))
        waitFor("$mode changed body") { check.current?.contentKey == "changed|two|three" }
        check.positioned()
        val expected = if (duringPositioning) .5f else 1f
        check(check.engine.capturePosition()?.fraction == expected) {
            "$mode body refresh (duringPositioning=$duringPositioning) expected $expected but got ${check.engine.capturePosition()}, reports=${check.reports}"
        }
    }
}

private suspend fun failuresPreserveReadableContent(mode: String) {
    val errors = listOf(Err(WebRequestError("offline", "offline")), Ok(chapter(body = "")), Ok(chapter(body = "THROW")))
    for (result in errors) ReaderCheck(mode).use { check ->
        val a = check.add(Ok(chapter()))
        check.open(); check.positioned()
        val position = check.engine.capturePosition()
        a.remote.complete(result)
        waitFor("$mode failed refresh handled") { a.active.get() == 0 }
        check(check.current?.contentKey == BODY && !check.engine.uiState.isPositioning)
        check(check.engine.capturePosition() == position)
    }
    for (body in listOf("", "THROW")) ReaderCheck(mode).use { check ->
        val a = check.add(Ok(chapter(body = body)))
        check.engine.changeChapter("a", ChapterPosition.Relative(.5f), 1)
        waitFor("$mode invalid initial cache consumed") { a.emitted.get() >= 1 }
        check(check.current == null)
        a.remote.complete(Ok(chapter()))
        waitFor("$mode later success repairs cache") { check.current?.contentKey == BODY }
        check.positioned()
    }
}

private suspend fun neighborsConsumeBothResults(mode: String) {
    ReaderCheck(mode).use { check ->
        check.add(Ok(chapter(previous = "p", next = "n")))
        val p = check.add(Ok(chapter("p")))
        val n = check.add(Ok(chapter("n")))
        check.open(); check.positioned()
        waitFor("$mode initial neighbors") { check.neighbor(0) != null && check.neighbor(2) != null }
        p.remote.complete(Ok(chapter("p", body = "updated previous")))
        n.remote.complete(Ok(chapter("n", body = "updated next")))
        waitFor("$mode refreshed neighbors") { check.neighbor(0)?.contentKey == "updated previous" && check.neighbor(2)?.contentKey == "updated next" }
        check(p.completed.get() == 1 && n.completed.get() == 1)
    }
}

private suspend fun continuousPromotionCancelsOldCollectors() {
    ReaderCheck("scroll").use { check ->
        val a = check.add(Ok(chapter(next = "b")))
        val b = check.add(Ok(chapter("b", previous = "a", next = "c")))
        val c = check.add(Ok(chapter("c", previous = "b")))
        check.open(); check.positioned()
        waitFor("scroll next chapter cached") { check.neighbor(2)?.id == "b" }
        val vm = check.engine as ScrollContentViewModel
        val state = vm.uiState.lazyListState
        state.lastScrolledForward = true
        state.isScrollInProgress = true
        state.firstVisibleItemIndex = 2
        state.firstVisibleItemScrollOffset = 0
        state.layoutInfo.visibleItemsInfo = listOf(LazyListItemInfo("b", 0, 1_300))
        waitFor("scroll continuous promotion") { check.current?.id == "b" }
        check(check.reports.zip(check.readProgress).any { (report, progress) ->
            report.second.chapterId == "a" && progress == 1f
        }) { "Entering the next chapter must still mark the previous chapter complete" }
        state.isScrollInProgress = false
        check.positioned()
        waitFor("scroll prior collectors cancelled") { a.active.get() == 0 && b.active.get() == 0 }
        waitFor("scroll new outer neighbor loaded") { check.neighbor(2)?.id == "c" }
        check(a.subscriptions.get() == 1 && b.subscriptions.get() == 1 && c.subscriptions.get() == 1) {
            "Continuous promotion should reuse the window and request only the new outer neighbor"
        }
        a.remote.complete(Ok(chapter(body = "late old a", next = "b")))
        b.remote.complete(Ok(chapter("b", body = "late old b", previous = "a", next = "c")))
        c.remote.complete(Ok(chapter("c", body = "new outer c", previous = "b")))
        waitFor("scroll new outer collector remains active") { check.neighbor(2)?.contentKey == "new outer c" }
        check(check.current?.id == "b" && check.current?.contentKey == BODY)
        check(check.neighbor(0)?.id == "a" && check.neighbor(0)?.contentKey == BODY)
        check(a.completed.get() == 0 && b.completed.get() == 0)
    }
}

private suspend fun ReaderCheck.openScrollHistory(hasNext: Boolean, progress: Float = 0f): ScrollContentViewModel {
    repository.readingData = UserReadingData(mapOf("a" to progress))
    add(Ok(chapter(next = if (hasNext) "b" else null)))
    if (hasNext) add(Ok(chapter("b", previous = "a")))
    open(target = null)
    positioned()
    if (hasNext) waitFor("scroll history next chapter cached") { neighbor(2)?.id == "b" }
    return engine as ScrollContentViewModel
}

private suspend fun scrollHistoryPreservesChapterTail() {
    // Synthetic chapter height 1300, viewport 500. Its final half-screen is still
    // the current chapter until offset 1050, even though navigation already shows 100%.
    for (offset in listOf(800, 950, 1049)) {
        val savedProgress = ReaderCheck("scroll").use { check ->
            val vm = check.openScrollHistory(hasNext = true)
            vm.uiState.lazyListState.scrollToItem(1, offset)
            vm.uiState.writeProgressRightNow()
            check(check.reports.last().second.fraction == 1f) { "Chapter-end navigation must remain at 100%" }
            val progress = check.readProgress.last()
            check(progress < 1f) { "Offset $offset prematurely saved as complete: $progress" }
            progress
        }
        ReaderCheck("scroll").use { check ->
            val restored = check.openScrollHistory(hasNext = true, progress = savedProgress)
            check(restored.uiState.lazyListState.firstVisibleItemScrollOffset == offset) {
                "Reopening the chapter moved from $offset to ${restored.uiState.lazyListState.firstVisibleItemScrollOffset}"
            }
        }
    }
    // A completed non-final chapter retains the old viewport-center interpretation.
    ReaderCheck("scroll").use { check ->
        val vm = check.openScrollHistory(hasNext = true, progress = 1f)
        check(vm.uiState.lazyListState.firstVisibleItemScrollOffset == 1050)
    }
    // The final chapter cannot scroll beyond its last full screen: retain completion
    // and its distinct history restore endpoint, including an explicit seek to the end.
    ReaderCheck("scroll").use { check ->
        val vm = check.openScrollHistory(hasNext = false, progress = 1f)
        check(vm.uiState.lazyListState.firstVisibleItemScrollOffset == 800)
        check(check.readProgress.last() == 1f && check.reports.last().second.fraction == 1f)
        vm.uiState.lazyListState.scrollToItem(1, 750)
        vm.uiState.writeProgressRightNow()
        check(check.readProgress.last() < 1f)
        vm.changeChapter("a", ChapterPosition.Relative(1f), 2)
        waitFor("scroll seek to book end") { !vm.uiState.isPositioning && check.reports.last().first == 2L }
        check(vm.uiState.lazyListState.firstVisibleItemScrollOffset == 800)
        check(check.readProgress.last() == 1f && check.reports.last().second.fraction == 1f)
    }
}

private suspend fun flipOrdinaryEntryReadsResolvedPrefix() {
    for (target in listOf(null, ChapterPosition.Relative(0f))) ReaderCheck("flip").use { check ->
        check.add(Ok(chapter()))
        check.open(target = target)
        check.partial(1)
        check.resolveHistory(1)
        check.partial(1)
        check(!check.engine.uiState.isPositioning) { "Chapter start should not wait for all pages" }
        val start = requireNotNull(check.engine.capturePosition())
        check(start.anchor == ReaderAnchor(0) && start.locationHash == "one".hashCode())
        check(check.indexes.isEmpty() && check.readProgress.last() < 1f)
        val vm = check.engine as FlipPageContentViewModel
        check.partial(2)
        vm.uiState.pagerState.moveTo(1)
        waitFor("flip reads resolved prefix") { check.reports.last().second.anchor == ReaderAnchor(1) }
        check(requireNotNull(check.engine.capturePosition()).fraction in 0f..<1f)
        check(check.readProgress.last() < 1f) { "Last resolved page is not the end of the chapter" }
        check(check.indexes.isEmpty()) { "Partial pagination must not publish a disk index" }
        check.measure()
        check(vm.uiState.pagerState.currentPage == 1) { "Finishing pagination moved the user's reading page" }
        check(check.engine.capturePosition()?.fraction == .5f)
        check(check.indexes.size == 1 && check.indexes.single().size == 3)
        check.measure()
        check(check.indexes.size == 1) { "Unchanged complete pagination should not rewrite the index" }
    }
}

private suspend fun flipHistoryRestoresBeforeCompletion() {
    ReaderCheck("flip", savedHash = "two".hashCode()).use { check ->
        check.add(Ok(chapter()))
        check.open(target = null)
        val vm = check.engine as FlipPageContentViewModel
        check.partial(1)
        check(vm.uiState.isPositioning && check.reports.isEmpty())
        check.partial(2)
        check(vm.uiState.isPositioning) { "Do not save chapter start before history has restored" }
        // Simulate the renderer finding the historical hash in its resolved second page.
        check.resolveHistory(2)
        check.partial(2)
        check(!vm.uiState.isPositioning && check.engine.capturePosition()?.anchor == ReaderAnchor(1))
        check(check.readProgress.last() < 1f && check.indexes.isEmpty())
        check.measure()
        check(vm.uiState.pagerState.currentPage == 1)
    }
}

private suspend fun flipRatioWaitsAndNewTargetsReplaceHistory() {
    for (fraction in listOf(.5f, 1f)) ReaderCheck("flip").use { check ->
        check.add(Ok(chapter()))
        check.open(target = ChapterPosition.Relative(fraction))
        check.partial(2)
        check(check.engine.uiState.isPositioning && check.reports.isEmpty()) {
            "Ratio $fraction must wait for the final page count"
        }
        check.measure()
        check(check.engine.capturePosition()?.fraction == fraction)
    }
    ReaderCheck("flip", savedHash = "three".hashCode()).use { check ->
        val scenario = check.add(Ok(chapter()))
        check.open(target = null); check.partial(1)
        check(check.engine.uiState.isPositioning)
        check.engine.changeChapter("a", ChapterPosition.Relative(0f), 2)
        check.partial(1)
        check(!check.engine.uiState.isPositioning && check.reports.last().first == 2L)
        check(check.engine.capturePosition()?.anchor == ReaderAnchor(0))
        check(scenario.subscriptions.get() == 1 && scenario.active.get() == 1)
    }
}

private suspend fun flipSplitTailAndExactBoundary() {
    ReaderCheck("flip").use { check ->
        check.add(Ok(chapter()))
        check.open(target = ChapterPosition.Relative(0f))
        val vm = check.engine as FlipPageContentViewModel
        val content = requireNotNull(check.current)
        val hashes = content.content.map { it.hashCode() }
        val firstPages = listOf(ReaderAnchor(0), ReaderAnchor(1), ReaderAnchor(2))
        fun partial(anchors: List<ReaderAnchor>, boundary: ReaderAnchor) {
            vm.uiState.pagerState.updatePageCount(anchors.size)
            vm.uiState.updateResolvedPages(content.id, content.contentKey, check.layout,
                anchors, anchors.map { hashes[it.componentIndex] }, boundary)
        }
        partial(firstPages, ReaderAnchor(2, characterOffset = 10))
        vm.uiState.pagerState.moveTo(2)
        waitFor("flip last component is readable") { check.reports.last().second.anchor == ReaderAnchor(2) }
        check(check.readProgress.last() < 1f) { "A partially paginated final component must not mark the chapter finished" }
        val boundary = ReaderPosition("a", .9f, ReaderAnchor(2, characterOffset = 10), content.contentKey)
        check.engine.changeChapter("a", ChapterPosition.Exact(boundary), 2)
        check(vm.uiState.isPositioning) { "An anchor at the unresolved boundary is not readable yet" }
        partial(firstPages + boundary.anchor!!, ReaderAnchor(2, characterOffset = 20))
        check(!vm.uiState.isPositioning && check.engine.capturePosition()?.anchor == boundary.anchor)
        check(check.readProgress.last() < 1f && check.indexes.isEmpty())
        check.engine.changeChapter("a", ChapterPosition.Exact(boundary.copy(contentKey = "old body")), 3)
        check(vm.uiState.isPositioning) { "An invalid exact anchor must await full pagination for its ratio fallback" }
        val complete = firstPages + listOf(boundary.anchor, ReaderAnchor(2, characterOffset = 20))
        vm.uiState.pagerState.updatePageCount(complete.size)
        vm.uiState.updatePagination(content.id, content.contentKey, check.layout, complete, complete.map { hashes[it.componentIndex] })
        check(!vm.uiState.isPositioning && check.engine.capturePosition()?.fraction == 1f)
        val before = requireNotNull(check.engine.capturePosition())
        vm.uiState.updateResolvedPages(content.id, content.contentKey, check.layout,
            firstPages, hashes, ReaderAnchor(2, characterOffset = 10))
        check(check.engine.capturePosition() == before && check.indexes.size == 1) {
            "A late partial callback must not downgrade a complete index"
        }
    }
}

private suspend fun flipPartialCallbacksRejectStaleIdentity() {
    ReaderCheck("flip").use { check ->
        check.add(Ok(chapter()))
        check.open(target = null)
        val vm = check.engine as FlipPageContentViewModel
        val content = requireNotNull(check.current)
        fun publish(id: String = "a", key: String = content.contentKey,
                    layout: ReaderPaginationLayout = check.layout) =
            vm.uiState.updateResolvedPages(id, key, layout, listOf(ReaderAnchor(0)),
                listOf("one".hashCode()), ReaderAnchor(1))
        publish(id = "previous"); publish(key = "old-body"); publish(layout = check.layout.copy(width = 900))
        check(vm.uiState.isPositioning && check.reports.isEmpty())
        check.partial(1)
        check.resolveHistory(1)
        check.partial(1)
        check(!vm.uiState.isPositioning)
        val reports = check.reports.size
        check.engine.dispose()
        publish()
        check(check.reports.size == reports)
    }
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
fun main() {
    val main = newSingleThreadContext("reader-loading-check")
    Dispatchers.setMain(main)
    try {
        runBlocking(main) {
            pendingHistoryOverridesPersistedValues()
            for (mode in listOf("flip", "scroll")) {
                ordinaryEntryUsesLatestPendingHistory(mode)
                pendingBodyUsesLatestTarget(mode, restoreHistory = false)
                pendingBodyUsesLatestTarget(mode, restoreHistory = true)
                for (action in listOf("chapter", "book", "dispose")) pendingBodyCancellation(mode, action)
                failedBodyRetryStartsNewCollection(mode, changeLayout = false)
                failedBodyRetryStartsNewCollection(mode, changeLayout = true)
                pendingHistoryDoesNotReplaceLatestTarget(mode)
                println("Reader loading checks passed: $mode pending-body seek/return reuse, latest request, pending history, retry before/after layout, chapter/book/dispose cancellation")
                sameChapterSeekKeepsCollection(mode)
                ordinaryNavigationReadsAgain(mode)
                sameBodyMetadata(mode)
                titleRefresh(mode, duringPositioning = true)
                titleRefresh(mode, duringPositioning = false)
                changedBodyUsesLatestPosition(mode, duringPositioning = true)
                changedBodyUsesLatestPosition(mode, duringPositioning = false)
                failuresPreserveReadableContent(mode)
                neighborsConsumeBothResults(mode)
                println("Reader loading checks passed: $mode seek/return, navigation, metadata, title/body refresh, errors, cancellation, neighbors")
            }
            println("Reader history checks passed: production pending/null/fallback resolution and both engines restore the latest pending position")
            seamlessPendingBodyKeepsCollection()
            flipTransitionTimeoutIdentitySurvivesBodyArrival()
            println("Reader loading checks passed: flip seamless transition retains pending body flow with old chapter still present")
            flipOrdinaryEntryReadsResolvedPrefix()
            flipHistoryRestoresBeforeCompletion()
            flipRatioWaitsAndNewTargetsReplaceHistory()
            flipSplitTailAndExactBoundary()
            flipPartialCallbacksRejectStaleIdentity()
            println("Reader loading checks passed: flip early reading, history, complete ratios, partial progress/index guards, stale callbacks")
            continuousPromotionCancelsOldCollectors()
            println("Reader loading checks passed: scroll continuous promotion cancels old collectors and retains slot identity")
            scrollHistoryPreservesChapterTail()
            println("Reader loading checks passed: scroll chapter-tail history round trips, completed history and final-book endpoint")
        }
    } finally { Dispatchers.resetMain(); main.close() }
}
