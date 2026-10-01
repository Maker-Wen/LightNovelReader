package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderAnchor
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPaginationLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.readerContentKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.singlePageNavigationFraction
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class FlipPageContentViewModel(
    private val bookRepository: BookRepository,
    coroutineScope: CoroutineScope,
    private val contentComponentRepository: ContentComponentRepository,
    private val onPositionChanged: (Long, ReaderPosition, String, Float) -> Unit,
    private val onNavigate: (String) -> Unit,
    private val onPagination: (String, String, ReaderPaginationLayout, List<ReaderAnchor>) -> Unit,
    private val onLayout: (ReaderPaginationLayout) -> Unit,
    private val getReadingHistory: suspend (String, String) -> Pair<Float, Int?>,
    private val onNavigateToBoundary: (String, ChapterPosition) -> Unit = { id, _ -> onNavigate(id) },
) : ContentViewModel {
    private val engineJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val scope = CoroutineScope(coroutineScope.coroutineContext + engineJob + Dispatchers.Main.immediate)
    private var chapterJob: Job? = null
    private var prevChapterJob: Job? = null
    private var nextChapterJob: Job? = null
    private var generation = 0L
    private var requestId = 0L
    private var requestedPosition: ChapterPosition? = null
    private var legacyProgress = 0f
    private var lastPosition: ReaderPosition? = null
    private var lastReportedPosition: ReaderPosition? = null
    private var layout: ReaderPaginationLayout? = null
    private var paginatedLayout: ReaderPaginationLayout? = null
    private var paginatedContentKey = ""
    private var anchors = emptyList<ReaderAnchor>()
    private var locationHashes = emptyList<Int>()
    private var paginationComplete = false
    private var nextPageAnchor: ReaderAnchor? = null

    override val uiState = MutableFlipPageContentUiState(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { if (engineJob.isActive) onNavigate(it) },
        changeChapterAtBoundary = { id, direction -> navigateToBoundary(id, direction) },
        retry = ::retry,
        updateLayout = ::updateLayout,
        updatePagination = ::updatePagination,
        updateResolvedPages = ::updateResolvedPages,
    )

    init {
        scope.launch {
            snapshotFlow {
                val pager = uiState.pagerState
                if (uiState.isPositioning || pager.isAnimating || pager.restoreInProgress ||
                    pager.restoreTargetHash != null || pager.restoreToEnd) null
                else pager.currentPage
            }.collect { if (it != null) reportPosition() }
        }
    }

    override fun changeBookId(id: String) {
        if (id == uiState.bookId) return
        generation++
        cancelNavigation()
        uiState.bookId = id
        uiState.readingChapterId = null
        uiState.readingChapterContent = null
        uiState.prevChapterContent = null
        uiState.nextChapterContent = null
        uiState.pagerState.reset()
        uiState.isPositioning = true
        lastPosition = null
        lastReportedPosition = null
        anchors = emptyList()
        locationHashes = emptyList()
        paginatedContentKey = ""
        paginationComplete = false
        nextPageAnchor = null
    }

    override fun loadNextChapter() {
        if (!uiState.isPositioning) uiState.readingChapterContent?.get()?.nextChapter?.let { navigateToBoundary(it, 1) }
    }

    override fun loadPrevChapter() {
        if (!uiState.isPositioning) uiState.readingChapterContent?.get()?.prevChapter?.let { navigateToBoundary(it, -1) }
    }

    private fun navigateToBoundary(id: String, direction: Int) {
        if (engineJob.isActive && id.isNotBlank()) {
            onNavigateToBoundary(id, ChapterPosition.Relative(if (direction > 0) 0f else 1f))
        }
    }

    override fun changeChapter(id: String, position: ChapterPosition?, requestId: Long) {
        if (!engineJob.isActive || id.isBlank() || uiState.bookId.isBlank()) return
        val content = uiState.readingChapterContent?.get()?.takeIf { it.id == id && uiState.readingChapterId == id }
        val awaitingBody = position != null && content == null && uiState.readingChapterId == id &&
            uiState.readingChapterContent?.isErr != true && uiState.isPositioning && chapterJob?.isActive == true
        this.requestId = requestId
        requestedPosition = position
        lastReportedPosition = null
        uiState.isPositioning = true
        uiState.pagerState.invalidateNavigation()
        if (position != null && content != null) {
            // A new explicit target supersedes a still-pending history restoration.
            uiState.pagerState.restoreTargetHash = null
            uiState.pagerState.restoreTargetFragmentHash = null
            uiState.pagerState.restoreInProgress = false
            uiState.pagerState.restoreToEnd = false
            if (anchors.isNotEmpty() && layout == paginatedLayout && paginatedContentKey == content.contentKey) applyPosition(content)
            return
        }
        // Keep the first body request alive, but let its result use the latest target/requestId.
        // Failed loads remain eligible for retry, including after a layout change.
        if (awaitingBody) return
        generation++
        cancelNavigation()
        uiState.readingChapterId = id
        val currentGeneration = generation
        val bookId = uiState.bookId
        val seamless = uiState.pagerState.pendingChapterId == id
        if (!seamless) {
            uiState.pagerState.reset()
            uiState.readingChapterContent = null
            uiState.prevChapterContent = null
            uiState.nextChapterContent = null
        }
        anchors = emptyList()
        locationHashes = emptyList()
        paginatedContentKey = ""
        paginationComplete = false
        nextPageAnchor = null
        chapterJob = scope.launch {
            try {
                val (savedProgress, savedHash) = if (position == null) getReadingHistory(bookId, id) else 0f to null
                if (!isCurrent(currentGeneration)) return@launch
                legacyProgress = savedProgress
                bookRepository.getChapterContentFlow(id, bookId, WebDataSourcePriority.High)
                    .flowOn(Dispatchers.IO).collect { result ->
                    result.andThen { parseChapter(it) }.onOk { loaded ->
                        if (!isCurrent(currentGeneration)) return@collect
                        val previous = uiState.readingChapterContent?.get()
                        if (previous?.id == loaded.id && previous.contentKey == loaded.contentKey) {
                            if (previous.title == loaded.title && previous.prevChapter == loaded.prevChapter &&
                                previous.nextChapter == loaded.nextChapter) return@collect
                            uiState.readingChapterContent = Ok(ChapterContentUiState(
                                loaded.id, loaded.title, previous.content, loaded.prevChapter,
                                loaded.nextChapter, loaded.contentKey
                            ))
                            preloadAdjacentChapters(loaded, currentGeneration, previous)
                            if (previous.title != loaded.title) {
                                lastReportedPosition = null
                                reportPosition()
                            }
                            return@collect
                        }
                        if (previous != null && !uiState.isPositioning) {
                            capturePosition()?.takeIf { it.chapterId == loaded.id }?.let {
                                requestedPosition = ChapterPosition.Exact(it)
                            }
                        }
                        val enteringTransition = previous?.id != loaded.id && uiState.pagerState.pendingChapterId == id
                        Snapshot.withMutableSnapshot {
                            if (requestedPosition != null) {
                                uiState.pagerState.restoreTargetHash = null
                                uiState.pagerState.restoreTargetFragmentHash = null
                                uiState.pagerState.restoreInProgress = false
                                uiState.pagerState.restoreToEnd = false
                            }
                            if (enteringTransition) {
                                uiState.pagerState.reset(preserveChapterTransition = true)
                                uiState.pagerState.restoreToEnd = requestedPosition == ChapterPosition.Relative(1f)
                            }
                            uiState.isPositioning = !(enteringTransition && requestedPosition == ChapterPosition.Relative(0f))
                            uiState.readingChapterContent = Ok(loaded)
                            anchors = emptyList()
                            locationHashes = emptyList()
                            paginatedContentKey = ""
                            paginationComplete = false
                            nextPageAnchor = null
                            if (requestedPosition == null) {
                                val progress = (savedProgress.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f)
                                val componentIndex = (progress * loaded.content.lastIndex).roundToInt()
                                uiState.pagerState.restoreTargetHash = loaded.content[componentIndex].hashCode()
                                uiState.pagerState.restoreTargetFragmentHash = savedHash
                                uiState.pagerState.restoreInProgress = true
                            }
                        }
                        preloadAdjacentChapters(loaded, currentGeneration, previous?.takeIf { it.id == loaded.id })
                    }.onErr { failLoading(it, currentGeneration) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failLoading(WebRequestError("章节加载失败", error.message ?: "无法加载章节", error), currentGeneration)
            }
        }
    }

    private fun retry() {
        uiState.readingChapterId?.let { changeChapter(it, requestedPosition, requestId) }
    }

    private suspend fun parseChapter(chapter: ChapterContent): Result<ChapterContentUiState, WebRequestError> =
        try {
            withContext(Dispatchers.Default) {
                val components = contentComponentRepository.getContentDataListFromJson(chapter.content)
                if (components.isEmpty()) Err(WebRequestError("章节为空", "该章节没有可阅读的内容"))
                else Ok(ChapterContentUiState(chapter.id, chapter.title, components, chapter.prevChapter,
                    chapter.nextChapter, readerContentKey(components)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Err(WebRequestError("章节加载失败", error.message ?: "无法加载章节", error))
        }

    private fun updateLayout(newLayout: ReaderPaginationLayout) {
        if (!engineJob.isActive || newLayout.width <= 0 || newLayout.height <= 0 || layout == newLayout) return
        if (!uiState.isPositioning) {
            capturePosition()?.takeIf { it.chapterId == uiState.readingChapterId }?.let {
                requestedPosition = ChapterPosition.Exact(it)
            }
        }
        uiState.isPositioning = true
        uiState.pagerState.invalidateNavigation()
        layout = newLayout
        onLayout(newLayout)
    }

    private fun updatePagination(
        chapterId: String,
        contentKey: String,
        measuredLayout: ReaderPaginationLayout,
        pageAnchors: List<ReaderAnchor>,
        pageHashes: List<Int>,
    ) {
        if (!engineJob.isActive || measuredLayout != layout || pageAnchors.isEmpty() || pageHashes.size != pageAnchors.size) return
        val content = uiState.readingChapterContent?.get() ?: return
        if (content.id != chapterId || content.id != uiState.readingChapterId || content.contentKey != contentKey) return
        val changed = !paginationComplete || paginatedLayout != measuredLayout || paginatedContentKey != contentKey || anchors != pageAnchors
        anchors = pageAnchors
        locationHashes = pageHashes
        paginatedLayout = measuredLayout
        paginatedContentKey = contentKey
        paginationComplete = true
        nextPageAnchor = null
        if (changed) onPagination(chapterId, contentKey, measuredLayout, pageAnchors)
        if (uiState.isPositioning) applyPosition(content) else reportPosition()
    }

    private fun updateResolvedPages(
        chapterId: String,
        contentKey: String,
        measuredLayout: ReaderPaginationLayout,
        pageAnchors: List<ReaderAnchor>,
        pageHashes: List<Int>,
        nextPageAnchor: ReaderAnchor,
    ) {
        if (!engineJob.isActive || measuredLayout != layout || pageAnchors.isEmpty() || pageHashes.size != pageAnchors.size) return
        val content = uiState.readingChapterContent?.get() ?: return
        if (content.id != chapterId || content.id != uiState.readingChapterId || content.contentKey != contentKey) return
        if (paginatedLayout == measuredLayout && paginatedContentKey == contentKey &&
            (paginationComplete || pageAnchors.size < anchors.size)) return
        anchors = pageAnchors
        locationHashes = pageHashes
        paginatedLayout = measuredLayout
        paginatedContentKey = contentKey
        paginationComplete = false
        this.nextPageAnchor = nextPageAnchor
        // Only the complete index is sent to the persistent pagination cache.
        if (uiState.isPositioning) applyPosition(content) else reportPosition()
    }

    private fun applyPosition(content: ChapterContentUiState) {
        if (anchors.isEmpty()) return
        val target = when (val position = requestedPosition) {
            is ChapterPosition.Relative -> {
                if (!position.fraction.isFinite()) {
                    fail(WebRequestError("定位失败", "阅读位置无效"), generation)
                    return
                }
                if (!paginationComplete && position.fraction.coerceIn(0f, 1f) != 0f) return
                (position.fraction.coerceIn(0f, 1f) * anchors.lastIndex).roundToInt()
            }
            is ChapterPosition.Exact -> {
                val saved = position.position
                val anchor = saved.anchor?.takeIf {
                    saved.chapterId == content.id && (saved.contentKey.isBlank() || saved.contentKey == content.contentKey) &&
                        it.componentIndex in content.content.indices
                }
                if (anchor == null) {
                    if (!paginationComplete) return
                    ((saved.fraction.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f) * anchors.lastIndex).roundToInt()
                } else {
                    if (!paginationComplete && nextPageAnchor?.let { anchor.isBefore(it) } != true) return
                    anchors.indexOfLast { candidate ->
                        candidate.componentIndex < anchor.componentIndex ||
                            candidate.componentIndex == anchor.componentIndex &&
                            (candidate.characterOffset < anchor.characterOffset ||
                                candidate.characterOffset == anchor.characterOffset && candidate.fraction <= anchor.fraction)
                    }.coerceAtLeast(0)
                }
            }
            null -> {
                val pager = uiState.pagerState
                if (pager.restoreInProgress || pager.restoreTargetHash != null ||
                    pager.restoreTargetFragmentHash != null || pager.restoreToEnd ||
                    pager.currentPage !in anchors.indices) return
                pager.currentPage
            }
        }.coerceIn(anchors.indices)
        Snapshot.withMutableSnapshot {
            uiState.pagerState.moveTo(target)
            uiState.pagerState.restoreTargetHash = null
            uiState.pagerState.restoreTargetFragmentHash = null
            uiState.pagerState.restoreInProgress = false
            uiState.pagerState.restoreToEnd = false
            uiState.pagerState.clearTransition()
            uiState.isPositioning = false
        }
        reportPosition()
    }

    private fun readPosition(): ReaderPosition? {
        if (!engineJob.isActive || uiState.isPositioning || uiState.pagerState.isAnimating ||
            uiState.pagerState.pendingChapterDirection != 0 || uiState.pagerState.restoreInProgress ||
            uiState.pagerState.restoreTargetHash != null || uiState.pagerState.restoreTargetFragmentHash != null ||
            uiState.pagerState.restoreToEnd) return null
        val content = uiState.readingChapterContent?.get() ?: return null
        if (content.id != uiState.readingChapterId || content.contentKey != paginatedContentKey || layout != paginatedLayout) return null
        val page = uiState.pagerState.currentPage
        val anchor = anchors.getOrNull(page) ?: return null
        val fraction = when {
            !paginationComplete -> sourceFraction(content, anchor)
            anchors.size > 1 -> page.toFloat() / anchors.lastIndex
            else -> singlePageNavigationFraction(requestedPosition, legacyProgress)
        }
        return ReaderPosition(content.id, fraction, anchor, content.contentKey, locationHashes.getOrNull(page))
    }

    private fun reportPosition() {
        val position = readPosition() ?: return
        val content = uiState.readingChapterContent?.get() ?: return
        val page = uiState.pagerState.currentPage
        val componentIndex = content.content.indexOfFirst { it.hashCode() == position.locationHash }
            .takeIf { it >= 0 } ?: position.anchor?.componentIndex ?: return
        val progress = when {
            paginationComplete && page == anchors.lastIndex -> 1f
            content.content.size <= 1 -> 0f
            else -> (componentIndex.toFloat() / content.content.lastIndex)
                .coerceAtMost(if (paginationComplete) 1f else 0.999999f)
        }
        uiState.readingProgress = progress
        lastPosition = position
        if (lastReportedPosition == position) return
        lastReportedPosition = position
        onPositionChanged(requestId, position, content.title, progress)
    }

    private fun ReaderAnchor.isBefore(other: ReaderAnchor): Boolean =
        componentIndex < other.componentIndex || componentIndex == other.componentIndex &&
            (characterOffset < other.characterOffset || characterOffset == other.characterOffset && fraction < other.fraction)

    private fun sourceFraction(content: ChapterContentUiState, anchor: ReaderAnchor): Float {
        val component = content.content.getOrNull(anchor.componentIndex)
        val textLength = (component as? ParagraphComponentData)?.paragraph?.textNodes?.sumOf { it.text.length } ?: 0
        val inside = if (textLength > 0) anchor.characterOffset.toFloat() / textLength else anchor.fraction
        // The final page count is still unknown; retain the exact source anchor and only estimate the fraction.
        return ((anchor.componentIndex + inside.coerceIn(0f, 1f)) / content.content.size)
            .coerceIn(0f, 0.999999f)
    }

    private fun preloadAdjacentChapters(content: ChapterContentUiState, currentGeneration: Long, previous: ChapterContentUiState?) {
        if (previous == null || previous.prevChapter != content.prevChapter) {
            prevChapterJob?.cancel()
            uiState.prevChapterContent = null
            prevChapterJob = preload(content.prevChapter, currentGeneration) {
                if (uiState.readingChapterContent?.get()?.prevChapter == it.id) uiState.prevChapterContent = Ok(it)
            }
        }
        if (previous == null || previous.nextChapter != content.nextChapter) {
            nextChapterJob?.cancel()
            uiState.nextChapterContent = null
            nextChapterJob = preload(content.nextChapter, currentGeneration) {
                if (uiState.readingChapterContent?.get()?.nextChapter == it.id) uiState.nextChapterContent = Ok(it)
            }
        }
    }

    private fun preload(chapterId: String?, currentGeneration: Long, onContent: (ChapterContentUiState) -> Unit): Job? {
        val id = chapterId?.takeIf(String::isNotBlank) ?: return null
        val bookId = uiState.bookId
        return scope.launch {
            try {
                var previous: ChapterContentUiState? = null
                bookRepository.getChapterContentFlow(id, bookId).flowOn(Dispatchers.IO).collect { result ->
                    val loaded = result.andThen { parseChapter(it) }.get() ?: return@collect
                    if (!isCurrent(currentGeneration)) return@collect
                    val old = previous
                    val updated = if (old?.contentKey == loaded.contentKey) {
                        if (old.title == loaded.title && old.prevChapter == loaded.prevChapter &&
                            old.nextChapter == loaded.nextChapter) return@collect
                        ChapterContentUiState(loaded.id, loaded.title, old.content, loaded.prevChapter,
                            loaded.nextChapter, loaded.contentKey)
                    } else loaded
                    previous = updated
                    onContent(updated)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed optional prefetch must not replace readable content.
            }
        }
    }

    private fun failLoading(error: WebRequestError, currentGeneration: Long) {
        if (!isCurrent(currentGeneration) || uiState.readingChapterContent?.get()?.id == uiState.readingChapterId) return
        fail(error, currentGeneration)
    }

    private fun fail(error: WebRequestError, currentGeneration: Long) {
        if (!isCurrent(currentGeneration)) return
        uiState.readingChapterContent = Err(error)
        uiState.isPositioning = false
        anchors = emptyList()
        locationHashes = emptyList()
        uiState.pagerState.reset()
    }

    private fun isCurrent(currentGeneration: Long) = engineJob.isActive && generation == currentGeneration

    private fun cancelNavigation() {
        chapterJob?.cancel()
        prevChapterJob?.cancel()
        nextChapterJob?.cancel()
    }

    override fun capturePosition(): ReaderPosition? = readPosition() ?: lastPosition

    override fun dispose() {
        generation++
        uiState.pagerState.invalidateNavigation()
        scope.cancel()
    }
}
