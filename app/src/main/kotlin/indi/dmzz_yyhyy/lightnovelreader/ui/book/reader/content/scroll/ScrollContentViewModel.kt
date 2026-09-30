package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import android.os.SystemClock
import android.os.Build
import android.os.Trace
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextLayoutResult
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.readerScrollPageIndexKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderAnchor
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPaginationLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.readerContentKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.singlePageNavigationFraction
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class ScrollContentViewModel(
    val bookRepository: BookRepository,
    coroutineScope: CoroutineScope,
    val contentComponentRepository: ContentComponentRepository,
    private val onPositionChanged: (Long, ReaderPosition, String, Float) -> Unit,
    private val onNavigate: (String) -> Unit,
    private val onPagination: (String, String, ReaderPaginationLayout, List<ReaderAnchor>) -> Unit,
    private val onLayout: (ReaderPaginationLayout) -> Unit,
    private val getReadingHistory: suspend (String, String) -> Pair<Float, Int?>,
) : ContentViewModel {
    private val engineJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    // Snapshot apply observers can run inside LazyColumn's paused precomposition. Always dispatch
    // their continuations before scrolling: an inline scroll cancels that in-progress prefetch.
    private val scope = CoroutineScope(coroutineScope.coroutineContext + engineJob + Dispatchers.Main)
    private var loadJob: Job? = null
    private var paginationJob: Job? = null
    private val adjacentJobs = arrayOfNulls<Job>(3)
    private val adjacentRequests = arrayOfNulls<String>(3)
    private var compensationJob: Job? = null
    // A local seek replaces positioning, while the chapter's cache/source request stays alive.
    private var generation = 0L
    private var chapterGeneration = 0L
    private var requestId = 0L
    private var requestedPosition: ChapterPosition? = null
    private var historyProgress = 0f
    private var layout: ReaderPaginationLayout? = null
    private var layoutKey = ""
    private var lastPosition: ReaderPosition? = null
    private var heldAnchor: ReaderAnchor? = null
    private var programmaticScrolls = 0
    private val applyingPosition get() = programmaticScrolls > 0
    private var bodyReady = false
    private var geometryRevision by mutableLongStateOf(0L)
    private var geometryFlushJob: Job? = null
    private val pendingGeometry = linkedSetOf<ScrollGeometryKey>()
    private val measurements = mutableMapOf<ScrollGeometryKey, ChapterMeasurements>()
    private val pageIndices = mutableMapOf<String, PageIndex>()
    private val publishedPages = mutableMapOf<String, PageIndex>()
    private var seekTraceGeneration: Long? = null

    private class ChapterMeasurements(val paragraph: BooleanArray) {
        var headerHeight: Int? = null
        val heights = IntArray(paragraph.size) { -1 }
        val topPaddings = FloatArray(paragraph.size)
        val texts = arrayOfNulls<TextLayoutResult>(paragraph.size)
        var dirty = true
        var snapshot: ChapterGeometry? = null
    }

    private class ChapterGeometry(
        val key: ScrollGeometryKey,
        val index: ScrollGeometryIndex,
        val texts: Array<TextLayoutResult?>
    )

    private class PageIndex(
        val geometry: ChapterGeometry,
        val viewportHeight: Int,
        val chapterHeight: Int,
        val anchors: List<ReaderAnchor>,
        val offsets: FloatArray
    )

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::navigate,
        retry = ::retry,
        setPaginationLayout = ::setPaginationLayout,
        setHeaderHeight = { key, height ->
            measurementsFor(key)?.let { buffer ->
                if (buffer.headerHeight != height) {
                    buffer.headerHeight = height
                    queueGeometry(key, buffer)
                }
            }
        },
        setComponentSize = { key, index, height, topPadding ->
            measurementsFor(key)?.let { buffer ->
                if (index in buffer.heights.indices &&
                    (buffer.heights[index] != height || buffer.topPaddings[index] != topPadding)) {
                    buffer.heights[index] = height
                    buffer.topPaddings[index] = topPadding
                    queueGeometry(key, buffer)
                }
            }
        },
        setTextLayout = { key, index, text ->
            measurementsFor(key)?.let { buffer ->
                if (index in buffer.texts.indices && buffer.texts[index] !== text) {
                    buffer.texts[index] = text
                    queueGeometry(key, buffer)
                }
            }
        },
        writeProgressRightNow = ::reportPosition
    )

    init {
        scope.launch {
            var lastReport = 0L
            snapshotFlow {
                val state = uiState.lazyListState
                state.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) } to state.isScrollInProgress
            }.collect { (_, scrolling) ->
                if (scrolling && !applyingPosition && !uiState.isPositioning) heldAnchor = null
                if (!uiState.isPositioning && !applyingPosition) {
                    updateContinuousChapter()
                    updatePagination()
                    val now = SystemClock.uptimeMillis()
                    if (!scrolling || now - lastReport >= 120L) {
                        reportPosition()
                        lastReport = now
                    }
                }
            }
        }
        scope.launch {
            snapshotFlow { geometryRevision }.collect {
                if (!uiState.isPositioning && !applyingPosition) updatePagination()
                compensateHeldAnchor()
            }
        }
    }

    private fun measurementsFor(key: ScrollGeometryKey): ChapterMeasurements? {
        if (!engineJob.isActive || key.bookId != uiState.bookId) return null
        val chapter = uiState.contentList.firstOrNull { it?.first == key.chapterId }?.second?.get() ?: return null
        if (chapter.contentKey != key.contentKey || chapter.title != key.title) return null
        // Layout callbacks may arrive before LaunchedEffect installs the new pagination layout.
        // Keep separate buffers; only readers require the currently active layout key.
        return measurements.getOrPut(key) {
            ChapterMeasurements(BooleanArray(chapter.content.size) { chapter.content[it] is ParagraphComponentData })
        }
    }

    private fun queueGeometry(key: ScrollGeometryKey, buffer: ChapterMeasurements) {
        buffer.dirty = true
        pendingGeometry += key
        if (geometryFlushJob?.isActive == true) return
        // Dispatch after this measure/layout pass, rather than launching inline on Main.immediate.
        geometryFlushJob = scope.launch(Dispatchers.Main) {
            val changed = pendingGeometry.toList()
            pendingGeometry.clear()
            traceReader("reader.geometry.rebuild") {
                changed.forEach { changedKey ->
                    val current = measurements[changedKey] ?: return@forEach
                    val header = current.headerHeight
                    current.snapshot = if (header != null && current.heights.all { it >= 0 } &&
                        current.paragraph.indices.all { !current.paragraph[it] || current.texts[it] != null }) {
                        ChapterGeometry(changedKey,
                            ScrollGeometryIndex.fromMeasurements(header, current.heights, current.topPaddings),
                            current.texts.copyOf())
                    } else null
                    current.dirty = false
                }
            }
            geometryRevision++
        }
    }

    private fun geometryKey(chapter: ChapterContentUiState) = ScrollGeometryKey(
        uiState.bookId, chapter.id, chapter.contentKey, chapter.title, layoutKey
    )

    private fun geometry(chapter: ChapterContentUiState): ChapterGeometry? {
        // Ordinary buffers are deliberately not snapshot state. Every waiter observes this revision.
        geometryRevision
        val buffer = measurements[geometryKey(chapter)] ?: return null
        return buffer.snapshot.takeUnless { buffer.dirty }
    }

    private fun compensateHeldAnchor() {
        val anchor = heldAnchor ?: return
        if (uiState.isPositioning || applyingPosition || uiState.lazyListState.isScrollInProgress) return
        val chapter = uiState.readingChapterContent?.get() ?: return
        val y = anchorY(chapter, anchor) ?: return
        val item = uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapter.id } ?: return
        val height = layout?.height ?: return
        val desired = y - anchor.viewportOffset * height
        if (abs(-item.offset - desired) <= 1f) return
        val token = generation
        val state = uiState.lazyListState
        compensationJob?.cancel()
        compensationJob = scope.launch {
            programmaticScrolls++
            try {
                state.scrollToItem(1, desired.roundToInt())
                if (token == generation) reportPosition()
            } finally { programmaticScrolls-- }
        }
    }

    private inline fun <T> traceReader(name: String, block: () -> T): T {
        if (!BuildConfig.BENCHMARK) return block()
        Trace.beginSection(name)
        return try { block() } finally { Trace.endSection() }
    }

    private fun beginSeekTrace() {
        endSeekTrace()
        if (BuildConfig.BENCHMARK && Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
            seekTraceGeneration = generation
            Trace.beginAsyncSection("reader.seek", generation.toInt())
        }
    }

    private fun endSeekTrace(token: Long? = null) {
        val active = seekTraceGeneration ?: return
        if (token != null && token != active) return
        seekTraceGeneration = null
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("reader.seek", active.toInt())
    }

    private fun navigate(id: String) { if (engineJob.isActive) onNavigate(id) }
    private fun retry() { uiState.readingChapterId?.let { changeChapter(it, requestedPosition, requestId) } }

    override fun changeBookId(id: String) {
        if (!engineJob.isActive || uiState.bookId == id) return
        endSeekTrace()
        generation++
        chapterGeneration++
        loadJob?.cancel()
        paginationJob?.cancel()
        cancelAdjacentChapters()
        compensationJob?.cancel()
        lastPosition = null
        heldAnchor = null
        pageIndices.clear()
        publishedPages.clear()
        measurements.clear()
        pendingGeometry.clear()
        geometryRevision++
        bodyReady = false
        uiState.bookId = id
        uiState.readingChapterId = null
        replaceChapters(null, null, null)
    }

    override fun loadNextChapter() { uiState.readingChapterContent?.get()?.nextChapter?.let(::navigate) }
    override fun loadPrevChapter() { uiState.readingChapterContent?.get()?.prevChapter?.let(::navigate) }

    override fun changeChapter(id: String, position: ChapterPosition?, requestId: Long) {
        if (!engineJob.isActive) return
        val current = uiState.readingChapterContent?.get()
        val localPosition = position != null && bodyReady && current?.id == id && uiState.readingChapterId == id
        val awaitingBody = position != null && !bodyReady && uiState.readingChapterId == id &&
            uiState.readingChapterContent?.isErr != true && uiState.isPositioning && loadJob?.isActive == true
        val currentLayout = layout
        val warm = if (localPosition && !uiState.isPositioning && !applyingPosition && currentLayout != null) {
            pageIndices[id]?.takeIf { cached -> measuredPageIndex(current, currentLayout) === cached }
        } else null
        val cached = uiState.contentList.filterNotNull().associateBy { it.first }
        generation++
        beginSeekTrace()
        this.requestId = requestId
        requestedPosition = position
        heldAnchor = null
        paginationJob?.cancel()
        compensationJob?.cancel()
        uiState.isPositioning = true
        if (localPosition) {
            historyProgress = 0f
            paginateAndPosition(reposition = true, warm = warm)
            return
        }
        // Retarget the pending load instead of restarting it before its first body arrives.
        // Failed loads still create a new request, including after a layout change.
        if (awaitingBody) return
        val token = ++chapterGeneration
        loadJob?.cancel()
        cancelAdjacentChapters()
        bodyReady = false
        uiState.readingChapterId = id
        uiState.readingProgress = 0f
        val reusable = cached[id]?.takeIf { it.second.get() != null }
        val content = reusable?.second?.get()
        replaceChapters(cached[content?.prevChapter], reusable, cached[content?.nextChapter])
        if (reusable == null) uiState.lazyListState = preloadedChapterListState()
        val bookId = uiState.bookId
        loadJob = scope.launch {
            try {
                historyProgress = if (position == null) getReadingHistory(bookId, id).first else 0f
                if (!isCurrentChapter(token, id)) return@launch
                // Window content can be shown immediately without skipping this chapter's refresh.
                reusable?.second?.let { applyChapterResult(id, token, it) }
                loadChapter(id, bookId).collect { result ->
                    applyChapterResult(id, token, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failLoading(WebRequestError("章节加载失败", error.message ?: "无法加载章节", error), token, id)
            }
        }
    }

    private fun isCurrentChapter(token: Long, id: String): Boolean =
        engineJob.isActive && token == chapterGeneration && uiState.readingChapterId == id

    private fun failLoading(error: WebRequestError, token: Long, id: String) {
        if (!isCurrentChapter(token, id) || uiState.readingChapterContent?.get() != null) return
        fail(error, generation)
    }

    private fun applyChapterResult(id: String, token: Long, result: Result<ChapterContentUiState, WebRequestError>) {
        if (!isCurrentChapter(token, id)) return
        result.onErr { failLoading(it, token, id) }
        val loaded = result.get() ?: return
        val previous = uiState.readingChapterContent?.get()
        val firstBody = !bodyReady
        val sameBody = previous?.contentKey == loaded.contentKey
        val geometryChanged = !sameBody || previous.title != loaded.title
        val adjacentChanged = previous == null || previous.prevChapter != loaded.prevChapter ||
            previous.nextChapter != loaded.nextChapter
        if (!firstBody && !geometryChanged && !adjacentChanged) return
        val updated = if (previous != null && sameBody) ChapterContentUiState(
            loaded.id, loaded.title, previous.content, loaded.prevChapter, loaded.nextChapter, loaded.contentKey
        ) else loaded
        val reposition = firstBody || geometryChanged
        if (reposition) {
            if (previous != null && !uiState.isPositioning) {
                capturePosition()?.takeIf { it.chapterId == id }?.let { requestedPosition = ChapterPosition.Exact(it) }
            }
            paginationJob?.cancel()
            compensationJob?.cancel()
            heldAnchor = null
            uiState.isPositioning = true
            if (geometryChanged) {
                pageIndices.remove(id)
                publishedPages.remove(id)
            }
        }
        val cached = uiState.contentList.filterNotNull().associateBy { it.first }
        // Also discard measurements from the old title/content identity before starting a waiter.
        replaceChapters(cached[updated.prevChapter], id to Ok(updated), cached[updated.nextChapter])
        bodyReady = true
        if (firstBody || adjacentChanged) loadAdjacentChapters(updated)
        if (reposition) paginateAndPosition(reposition = true)
    }

    private fun loadChapter(id: String, bookId: String): Flow<Result<ChapterContentUiState, WebRequestError>> =
        bookRepository.getChapterContentFlow(id, bookId).map { result ->
            try {
                result.andThen { chapter ->
                    val content = contentComponentRepository.getContentDataListFromJson(chapter.content)
                    if (content.isEmpty()) {
                        Err(WebRequestError("章节为空", "该章节没有可阅读的内容"))
                    } else {
                        Ok(ChapterContentUiState(chapter.id, chapter.title, content, chapter.prevChapter,
                            chapter.nextChapter, readerContentKey(content)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A malformed cached emission must not prevent a later valid source result.
                Err(WebRequestError("章节加载失败", error.message ?: "无法加载章节", error))
            }
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(Err(WebRequestError("章节加载失败", error.message ?: "无法加载章节", error)))
        }.flowOn(Dispatchers.IO)

    private fun setPaginationLayout(value: ReaderPaginationLayout) {
        if (!engineJob.isActive || value.width <= 0 || value.height <= 0 || value == layout) return
        val previous = capturePosition()
        val hadLayout = layout != null
        compensationJob?.cancel()
        layout = value
        layoutKey = value.key
        onLayout(value)
        pageIndices.clear()
        publishedPages.clear()
        measurements.keys.removeAll { it.layoutKey != layoutKey }
        pendingGeometry.retainAll(measurements.keys)
        geometryRevision++
        if (hadLayout && !uiState.isPositioning && previous != null && previous.chapterId == uiState.readingChapterId) {
            requestedPosition = ChapterPosition.Exact(previous)
            uiState.isPositioning = true
        }
        paginateAndPosition(reposition = uiState.isPositioning)
    }

    private fun paginateAndPosition(reposition: Boolean, warm: PageIndex? = null) {
        if (!bodyReady) return
        val chapter = uiState.readingChapterContent?.get() ?: return
        val paginationLayout = layout ?: return
        val token = generation
        paginationJob?.cancel()
        paginationJob = scope.launch {
            try {
                val state = uiState.lazyListState
                snapshotFlow { state.layoutInfo.totalItemsCount }.first { it >= 2 }
                val cached = warm?.takeIf { measuredPageIndex(chapter, paginationLayout) === it }
                if (reposition) {
                    traceReader(if (cached != null) "reader.seek.warm" else "reader.seek.cold") { Unit }
                    if (cached == null) state.scrollToItem(1)
                }
                var index = cached ?: snapshotFlow { measuredPageIndex(chapter, paginationLayout) }
                    .first { it != null }!!
                if (token != generation || layout != paginationLayout || uiState.readingChapterId != chapter.id) return@launch
                publishPagination(chapter, paginationLayout, index)
                if (!reposition) {
                    reportPosition()
                    return@launch
                }
                programmaticScrolls++
                try {
                    while (true) {
                        val anchor = requestedAnchor(chapter, index.anchors)
                        val offset = if (anchor != null) {
                            checkNotNull(anchorY(chapter, index.geometry, anchor)) -
                                anchor.viewportOffset * paginationLayout.height
                        } else if (!chapter.hasNextChapter() && historyProgress >= 1f) {
                            (index.chapterHeight - paginationLayout.height).coerceAtLeast(0).toFloat()
                        } else {
                            (index.chapterHeight - paginationLayout.height / 2).coerceAtLeast(0) * historyProgress.coerceIn(0f, 1f)
                        }
                        if (token != generation || layout != paginationLayout) return@launch
                        // No suspension between validation and the single target scroll on a warm seek.
                        if (measuredPageIndex(chapter, paginationLayout) !== index) {
                            index = snapshotFlow { measuredPageIndex(chapter, paginationLayout) }.first { it != null }!!
                            continue
                        }
                        state.scrollToItem(1, offset.roundToInt())
                        val appliedIndex = snapshotFlow { measuredPageIndex(chapter, paginationLayout) }
                            .first { it != null }!!
                        if (token != generation || layout != paginationLayout) return@launch
                        if (appliedIndex.geometry !== index.geometry) {
                            // An image/font finished while scrollToItem was suspended. Reapply against its new geometry.
                            traceReader("reader.seek.geometryChanged") { Unit }
                            index = appliedIndex
                            continue
                        }
                        publishPagination(chapter, paginationLayout, appliedIndex)
                        snapshotFlow { readPosition() }.first { it != null }
                        if (token != generation) return@launch
                        uiState.isPositioning = false
                        reportPosition()
                        heldAnchor = anchor ?: lastPosition?.anchor
                        traceReader("reader.seek.applied") { Unit }
                        endSeekTrace(token)
                        break
                    }
                } finally { programmaticScrolls-- }
                // A revision received during positioning must not lose image-anchor compensation.
                compensateHeldAnchor()
            } catch (cancelled: CancellationException) {
                // A replacement job may be continuing the same request after a layout change.
                throw cancelled
            } catch (error: Exception) {
                fail(WebRequestError("定位失败", error.message ?: "无法完成章节排版与定位", error), token)
            }
        }
    }

    private fun requestedAnchor(chapter: ChapterContentUiState, anchors: List<ReaderAnchor>): ReaderAnchor? {
        if (chapter.content.isEmpty()) return null
        return when (val position = requestedPosition) {
            is ChapterPosition.Exact -> position.position.anchor?.takeIf {
                position.position.chapterId == chapter.id &&
                    (position.position.contentKey.isBlank() || position.position.contentKey == chapter.contentKey) &&
                    it.componentIndex in chapter.content.indices && it.viewportOffset.isFinite() && it.fraction.isFinite()
            } ?: relativeAnchor(anchors, position.position.fraction)
            is ChapterPosition.Relative -> relativeAnchor(anchors, position.fraction)
            null -> null
        }
    }

    private fun fail(error: WebRequestError, token: Long) {
        if (!engineJob.isActive || token != generation) return
        val chapterId = uiState.readingChapterId ?: return
        heldAnchor = null
        bodyReady = false
        pageIndices.remove(chapterId)
        publishedPages.remove(chapterId)
        endSeekTrace(token)
        uiState.contentList[1] = chapterId to Err(error)
        uiState.isPositioning = false
    }

    private fun relativeAnchor(anchors: List<ReaderAnchor>, fraction: Float): ReaderAnchor? =
        anchors.getOrNull((fraction.coerceIn(0f, 1f) * (anchors.size - 1).coerceAtLeast(0)).roundToInt())

    private fun anchorY(chapter: ChapterContentUiState, anchor: ReaderAnchor): Float? {
        val measured = geometry(chapter) ?: return null
        return anchorY(chapter, measured, anchor)
    }

    private fun anchorY(chapter: ChapterContentUiState, measured: ChapterGeometry, anchor: ReaderAnchor): Float? {
        val component = chapter.content.getOrNull(anchor.componentIndex) ?: return null
        val top = measured.index.tops[anchor.componentIndex]
        if (component is ParagraphComponentData) {
            val text = measured.texts[anchor.componentIndex] ?: return null
            val offset = anchor.characterOffset.coerceIn(0, text.layoutInput.text.length)
            return top + text.getLineTop(text.getLineForOffset(offset))
        }
        return top + measured.index.heights[anchor.componentIndex] * anchor.fraction.coerceIn(0f, 1f)
    }

    private fun anchorAtOffset(
        chapter: ChapterContentUiState,
        measured: ChapterGeometry,
        index: Int,
        offset: Float,
        height: Int
    ): ReaderAnchor {
        val top = measured.index.tops[index]
        val componentHeight = measured.index.heights[index]
        val inside = (offset - top).coerceIn(0f, componentHeight.toFloat())
        return if (chapter.content[index] is ParagraphComponentData) {
            val text = checkNotNull(measured.texts[index])
            val line = text.getLineForVerticalPosition(inside)
            ReaderAnchor(index, text.getLineStart(line), viewportOffset =
                (top + text.getLineTop(line) - offset) / height)
        } else {
            ReaderAnchor(index, fraction = inside / componentHeight.coerceAtLeast(1), viewportOffset =
                (top + inside - offset) / height)
        }
    }

    private fun measuredPageIndex(chapter: ChapterContentUiState, layout: ReaderPaginationLayout): PageIndex? {
        val measured = geometry(chapter) ?: return null
        val item = uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapter.id } ?: return null
        // A batch can be ready before LazyColumn exposes its matching chapter measurement.
        if (item.size != measured.index.totalHeight.coerceAtLeast(layout.height)) return null
        pageIndices[chapter.id]?.takeIf {
            it.geometry === measured && it.viewportHeight == layout.height && it.chapterHeight == item.size
        }?.let { return it }
        return traceReader("reader.pagination.rebuild") {
            val offsets = measured.index.pageOffsets(layout.height)
            val anchors = if (chapter.content.isEmpty()) listOf(ReaderAnchor(0)) else {
                measured.index.mapPages(layout.height) { index, offset ->
                    anchorAtOffset(chapter, measured, index, offset, layout.height)
                }
            }
            PageIndex(measured, layout.height, item.size, anchors,
                FloatArray(offsets.size) { offsets[it].toFloat() }).also { pageIndices[chapter.id] = it }
        }
    }

    private fun publishPagination(chapter: ChapterContentUiState, layout: ReaderPaginationLayout, index: PageIndex) {
        val previous = publishedPages[chapter.id]
        if (previous?.geometry?.key == index.geometry.key && previous.anchors == index.anchors) return
        publishedPages[chapter.id] = index
        onPagination(chapter.id, readerScrollPageIndexKey(chapter.contentKey, chapter.title), layout, index.anchors)
    }

    private fun updatePagination() {
        val chapter = uiState.readingChapterContent?.get() ?: return
        val layout = layout ?: return
        val index = measuredPageIndex(chapter, layout) ?: return
        publishPagination(chapter, layout, index)
    }

    private fun readPosition(): ReaderPosition? {
        val chapter = uiState.readingChapterContent?.get() ?: return null
        val paginationLayout = layout ?: return null
        val index = measuredPageIndex(chapter, paginationLayout) ?: return null
        val item = uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapter.id } ?: return null
        if (chapter.content.isEmpty()) return ReaderPosition(chapter.id, 0f, contentKey = chapter.contentKey)
        val offset = -item.offset.toFloat()
        val anchor = anchorAtOffset(chapter, index.geometry,
            index.geometry.index.componentAt(offset), offset, paginationLayout.height)
        val page = scrollPageAt(index.offsets, offset + 1f)
        val fraction = when {
            index.anchors.size <= 1 -> singlePageNavigationFraction(requestedPosition, historyProgress)
            isAtChapterEnd(item) -> 1f
            else -> page.toFloat() / (index.anchors.size - 1)
        }
        return ReaderPosition(chapter.id, fraction, anchor, chapter.contentKey)
    }

    private fun reportPosition() {
        if (!engineJob.isActive || uiState.isPositioning) return
        val position = readPosition() ?: return
        val chapter = uiState.readingChapterContent?.get() ?: return
        val item = uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapter.id } ?: return
        // Non-final chapters remain active until their end crosses the viewport center.
        // Preserve that continuous history scale even when navigation already shows 100%.
        // Only the final chapter is complete at the last full screen, where scrolling ends.
        uiState.readingProgress = if (!chapter.hasNextChapter() && isAtChapterEnd(item)) {
            1f
        } else {
            (-item.offset.toFloat() /
                (item.size - (layout?.height ?: 0) / 2).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        lastPosition = position
        onPositionChanged(requestId, position, chapter.title, uiState.readingProgress)
    }

    private fun isAtChapterEnd(item: LazyListItemInfo): Boolean =
        item.offset + item.size <= uiState.lazyListState.layoutInfo.viewportEndOffset + 1

    override fun capturePosition(): ReaderPosition? =
        if (uiState.isPositioning) lastPosition else readPosition() ?: lastPosition

    private fun updateContinuousChapter() {
        val state = uiState.lazyListState
        val viewportCenter = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
        val first = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            viewportCenter in item.offset until item.offset + item.size &&
                uiState.contentList.any { it?.first == item.key && it.second.get() != null }
        } ?: return
        val chapterId = first.key as? String ?: return
        if (chapterId == uiState.readingChapterId) return
        val current = uiState.readingChapterContent?.get() ?: return
        if (chapterId != current.prevChapter && chapterId != current.nextChapter) return
        if (chapterId == current.prevChapter && !state.lastScrolledBackward) return
        if (chapterId == current.nextChapter && !state.lastScrolledForward) return
        val chapter = uiState.contentList.firstOrNull { it?.first == chapterId }?.second?.get() ?: return
        if (chapterId == current.nextChapter) {
            onPositionChanged(
                requestId,
                ReaderPosition(current.id, 1f, contentKey = current.contentKey),
                current.title,
                1f
            )
        }
        val existing = uiState.contentList.filterNotNull().associateBy { it.first }
        endSeekTrace()
        generation++
        chapterGeneration++
        heldAnchor = null
        requestedPosition = null
        historyProgress = 0f
        loadJob?.cancel()
        paginationJob?.cancel()
        cancelAdjacentChapters()
        compensationJob?.cancel()
        val indexDelta = if (chapterId == current.prevChapter) 1 else -1
        val firstIndex = state.firstVisibleItemIndex
        val firstOffset = state.firstVisibleItemScrollOffset
        Snapshot.withMutableSnapshot {
            uiState.readingChapterId = chapterId
            replaceChapters(existing[chapter.prevChapter], existing[chapter.id], existing[chapter.nextChapter])
            state.requestScrollToItem((firstIndex + indexDelta).coerceIn(0, 2), firstOffset)
        }
        paginateAndPosition(reposition = false)
        loadAdjacentChapters(chapter)
    }

    private fun cancelAdjacentChapters() {
        adjacentJobs.forEach { it?.cancel() }
        adjacentJobs.fill(null)
        adjacentRequests.fill(null)
    }

    private fun loadAdjacentChapters(chapter: ChapterContentUiState) {
        val token = chapterGeneration
        val bookId = uiState.bookId
        listOf(0 to chapter.prevChapter, 2 to chapter.nextChapter).forEach { (slot, candidate) ->
            val id = candidate?.takeIf { it != chapter.id && (slot != 2 || it != chapter.prevChapter) }
            if (adjacentRequests[slot] == id) return@forEach
            adjacentJobs[slot]?.cancel()
            adjacentJobs[slot] = null
            adjacentRequests[slot] = id
            if (id == null) return@forEach
            if (uiState.contentList[slot]?.let { it.first == id && it.second.get() != null } == true) return@forEach
            adjacentJobs[slot] = scope.launch {
                loadChapter(id, bookId).collect { result ->
                    snapshotFlow { uiState.isPositioning || !uiState.lazyListState.isScrollInProgress }.first { it }
                    if (!isCurrentChapter(token, chapter.id) || adjacentRequests[slot] != id) return@collect
                    val previous = uiState.contentList[slot]?.takeIf { it.first == id }?.second?.get()
                    val loaded = result.get()
                    if (loaded == null && previous != null) return@collect
                    val updated = if (previous != null && loaded != null && previous.contentKey == loaded.contentKey) {
                        if (previous.title == loaded.title && previous.prevChapter == loaded.prevChapter &&
                            previous.nextChapter == loaded.nextChapter) return@collect
                        Ok(ChapterContentUiState(loaded.id, loaded.title, previous.content,
                            loaded.prevChapter, loaded.nextChapter, loaded.contentKey))
                    } else result
                    uiState.contentList[slot] = id to updated
                }
            }
        }
    }

    private fun replaceChapters(
        previous: Pair<String, Result<ChapterContentUiState, WebRequestError>>?,
        current: Pair<String, Result<ChapterContentUiState, WebRequestError>>?,
        next: Pair<String, Result<ChapterContentUiState, WebRequestError>>?
    ) = Snapshot.withMutableSnapshot {
        uiState.contentList[0] = previous
        uiState.contentList[1] = current
        uiState.contentList[2] = next?.takeIf { it.first != previous?.first }
        val retained = uiState.contentList.mapNotNull { it?.first }.toSet()
        measurements.keys.removeAll { key ->
            val chapter = uiState.contentList.firstOrNull { it?.first == key.chapterId }?.second?.get()
            key.chapterId !in retained || chapter == null || chapter.contentKey != key.contentKey || chapter.title != key.title
        }
        pendingGeometry.retainAll(measurements.keys)
        pageIndices.keys.retainAll(retained)
        publishedPages.keys.retainAll(retained)
        geometryRevision++
    }

    override fun dispose() { endSeekTrace(); generation++; chapterGeneration++; scope.cancel() }
}
