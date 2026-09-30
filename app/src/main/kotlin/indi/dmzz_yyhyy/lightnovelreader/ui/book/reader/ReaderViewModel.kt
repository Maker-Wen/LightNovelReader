package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderAnchor
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPaginationLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.readReaderHistory
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.readerContentKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentViewModel
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val bookRepository: BookRepository,
    private val userDataRepository: UserDataRepository,
    val contentComponentRepository: ContentComponentRepository,
    private val pageIndexStore: ReaderPageIndexStore,
    private val sourceProvider: WebBookDataSourceProvider
) : ViewModel() {
    val settingState = SettingState(userDataRepository, viewModelScope)
    private var contentViewModel: ContentViewModel? = null
    private val _uiState = MutableReaderScreenUiState(null)
    val uiState: ReaderScreenUiState = _uiState
    private val readingBookListUserData = userDataRepository.stringListUserData(UserDataPath.ReadingBooks.path)
    // Preserve the existing lifetime: the final statistics flush can outlive reader disposal.
    private val readingStatsScope = CoroutineScope(Dispatchers.IO)
    private var directoryJob: Job? = null
    private var mapJob: Job? = null
    private var saveJob: Job? = null
    private val saveMutex = Mutex()
    @Volatile private var session = 0L
    @Volatile private var requestId = 0L
    private var engineGeneration = 0L
    private var active = false
    private var sourceId = ""
    private var chapterId = ""
    private var actualPosition: ReaderPosition? = null
    private var actualTitle = ""
    private val chapterReadingProgress = mutableMapOf<String, Float>()
    private val pendingChapterLocationHashes = mutableMapOf<String, Int?>()
    private val maxChapterReadingProgress = mutableMapOf<String, Float>()
    @Volatile private var positionRevision = 0L
    private var pendingTarget: ReaderPosition? = null
    private var pendingChapterPosition: ChapterPosition? = null
    private var returning = false
    private var seeking = false
    private var seekOriginPosition: ReaderPosition? = null
    private var deferredDirectory: BookVolumes? = null
    private var layout: ReaderPaginationLayout? = null
    private var frozenPageCounts = emptyMap<String, Int?>()
    private var mapInitialized = false
    private var lastSaveTime = 0L

    var bookId = ""
        set(value) {
            endSession()
            field = value
            if (value.isBlank()) return
            active = true
            sourceId = currentSourceId()
            _uiState.bookId = value
            createEngine(settingState.isUsingFlipPage)
            addToReadingBook(value)
            viewModelScope.launch(Dispatchers.IO) {
                statsRepository.updateReadingStatistics(ReadingStatsUpdate(bookId = value, readEventDelta = 1))
            }
            val currentSession = session
            directoryJob = viewModelScope.launch {
                bookRepository.getBookVolumesFlow(value).flowOn(Dispatchers.IO).collect { result ->
                    if (!isCurrent(currentSession)) return@collect
                    // Keep an available directory when its background refresh fails.
                    if (result.get() != null || _uiState.bookVolumes == null) _uiState.bookVolumes = result
                    result.get()?.let { volumes ->
                        if (seeking) deferredDirectory = volumes else updateDirectory(volumes)
                    }
                }
            }
        }

    init {
        viewModelScope.launch {
            settingState.isUsingFlipPageUserData.getFlowWithDefault(false).collect { flip ->
                if (active && (contentViewModel is FlipPageContentViewModel) != flip) {
                    val target = pendingTarget
                    val position = target ?: contentViewModel?.capturePosition() ?: actualPosition
                    val restore = if (target != null) pendingChapterPosition else position?.let(ChapterPosition::Exact)
                    createEngine(flip)
                    if (chapterId.isNotBlank()) navigate(position?.chapterId ?: chapterId, restore)
                }
            }
        }
    }

    private fun currentSourceId() = if (sourceProvider.isWebDataSourceFounded()) sourceProvider.value.id.toString() else ""
    private fun isCurrent(value: Long) = active && session == value && sourceId == currentSourceId()

    private fun createEngine(flip: Boolean) {
        contentViewModel?.dispose()
        val generation = ++engineGeneration
        val onPosition: (Long, ReaderPosition, String, Float) -> Unit = { id, position, title, progress ->
            if (generation == engineGeneration) positionChanged(id, position, title, progress)
        }
        val onLayout: (ReaderPaginationLayout) -> Unit = { value ->
            if (generation == engineGeneration && active) layoutChanged(value)
        }
        val onPagination: (String, String, ReaderPaginationLayout, List<ReaderAnchor>) -> Unit = { id, key, value, anchors ->
            if (generation == engineGeneration && active) cachePagination(id, key, value, anchors)
        }
        contentViewModel = if (flip) FlipPageContentViewModel(
            bookRepository = bookRepository, coroutineScope = viewModelScope,
            contentComponentRepository = contentComponentRepository,
            onPositionChanged = onPosition, onNavigate = ::changeChapter,
            onNavigateToBoundary = ::changeChapterAtBoundary,
            getReadingHistory = ::getReadingHistory,
            onPagination = onPagination, onLayout = onLayout
        ) else ScrollContentViewModel(
            bookRepository = bookRepository, coroutineScope = viewModelScope,
            contentComponentRepository = contentComponentRepository,
            onPositionChanged = onPosition, onNavigate = ::changeChapter,
            getReadingHistory = ::getReadingHistory,
            onPagination = onPagination, onLayout = onLayout
        )
        contentViewModel?.changeBookId(bookId)
        _uiState.contentUiState = contentViewModel?.uiState
    }

    fun endSession() {
        saveJob?.cancel()
        if (active) persistPosition(requestId, immediate = true, closing = true)
        active = false
        session++
        requestId++
        engineGeneration++
        contentViewModel?.dispose()
        contentViewModel = null
        directoryJob?.cancel()
        mapJob?.cancel()
        chapterId = ""
        actualPosition = null
        chapterReadingProgress.clear()
        pendingChapterLocationHashes.clear()
        maxChapterReadingProgress.clear()
        pendingTarget = null
        pendingChapterPosition = null
        returning = false
        seeking = false
        seekOriginPosition = null
        layout = null
        frozenPageCounts = emptyMap()
        mapInitialized = false
        deferredDirectory = null
        _uiState.originPosition = null
        _uiState.position = null
        _uiState.progressMap = ReaderProgressMap(emptyList())
        _uiState.bookVolumes = null
        _uiState.contentUiState = null
    }

    fun prevChapter() = contentViewModel?.loadPrevChapter()
    fun nextChapter() = contentViewModel?.loadNextChapter()

    fun changeChapter(chapterId: String) {
        returning = false
        navigate(chapterId, null)
    }

    private fun changeChapterAtBoundary(chapterId: String, position: ChapterPosition) {
        returning = false
        navigate(chapterId, position)
    }

    private fun readingLocationPath(bookId: String, chapterId: String) =
        "reader.reading_location.$bookId.$chapterId"

    private suspend fun getReadingHistory(requestedBook: String, chapterId: String): Pair<Float, Int?> {
        val (savedSession, pending) = withContext(Dispatchers.Main.immediate) {
            if (!isCurrent(session) || bookId != requestedBook) throw CancellationException("Reader session changed")
            session to Triple(
                chapterReadingProgress[chapterId],
                pendingChapterLocationHashes.containsKey(chapterId),
                pendingChapterLocationHashes[chapterId],
            )
        }
        val history = withContext(Dispatchers.IO) {
            readReaderHistory(
                currentProgress = pending.first,
                hasPendingLocationHash = pending.second,
                pendingLocationHash = pending.third,
                readSavedProgress = {
                    bookRepository.getUserReadingData(requestedBook).currentChapterReadingProgressMap[chapterId] ?: 0f
                },
                readSavedLocationHash = {
                    userDataRepository.intUserData(readingLocationPath(requestedBook, chapterId)).get()
                },
            )
        }
        return withContext(Dispatchers.Main.immediate) {
            if (!isCurrent(savedSession) || bookId != requestedBook) throw CancellationException("Reader session changed")
            history
        }
    }

    private fun navigate(id: String, target: ChapterPosition?) {
        if (!active || id.isBlank()) return
        chapterId = id
        pendingChapterPosition = target
        pendingTarget = when (target) {
            is ChapterPosition.Exact -> target.position
            is ChapterPosition.Relative -> ReaderPosition(id, target.fraction)
            null -> ReaderPosition(id, 0f)
        }
        _uiState.position = pendingTarget
        saveJob?.cancel()
        contentViewModel?.changeChapter(id, target, ++requestId)
    }

    fun beginSeek(): ReaderPosition? {
        if (!active) return null
        if (seeking) return seekOriginPosition
        // Keep an existing return point, but do not recreate a cleared one from stale content
        // while a new destination is pending. Freeze null too: completion during this gesture
        // must not introduce a marker; the next gesture can capture the completed position.
        seekOriginPosition = _uiState.originPosition ?: if (pendingTarget == null) {
            contentViewModel?.capturePosition() ?: actualPosition
        } else {
            null
        }
        seeking = true
        // Once touched, this exact navigation scale remains fixed for the session.
        mapInitialized = true
        mapJob?.cancel()
        return seekOriginPosition
    }

    fun cancelSeek() {
        seeking = false
        seekOriginPosition = null
        deferredDirectory?.let(::updateDirectory)
        deferredDirectory = null
    }

    fun seek(progress: Float) {
        if (!seeking) return
        val target = _uiState.progressMap.target(progress) ?: return cancelSeek()
        // Refresh only the no-op comparison; the return marker always uses the DOWN snapshot.
        val current = contentViewModel?.capturePosition() ?: actualPosition
        if (current?.chapterId != target.chapterId || kotlin.math.abs(current.fraction - target.fraction) > 0.00001f || pendingTarget != null) {
            if (_uiState.originPosition == null) _uiState.originPosition = seekOriginPosition
            returning = false
            navigate(target.chapterId, ChapterPosition.Relative(target.fraction))
        }
        cancelSeek()
    }

    fun returnToOrigin() {
        // An earlier return can finish and clear the formal marker while this gesture is held.
        val origin = _uiState.originPosition ?: seekOriginPosition
        cancelSeek()
        if (origin == null) return
        if (_uiState.originPosition == null) _uiState.originPosition = origin
        returning = true
        navigate(origin.chapterId, ChapterPosition.Exact(origin))
    }

    fun clearReturnPosition() {
        cancelSeek()
        _uiState.originPosition = null
        returning = false
    }

    private fun positionChanged(id: Long, position: ReaderPosition, title: String, readProgress: Float) {
        if (!active || id != requestId || sourceId != currentSourceId() || !readProgress.isFinite()) return
        if (pendingTarget != null && pendingTarget?.chapterId != position.chapterId) return
        val completed = pendingTarget != null
        pendingTarget = null
        pendingChapterPosition = null
        chapterId = position.chapterId
        actualPosition = position
        actualTitle = title
        val actualReadProgress = readProgress.coerceIn(0f, 1f)
        chapterReadingProgress[position.chapterId] = actualReadProgress
        pendingChapterLocationHashes[position.chapterId] = position.locationHash
        maxChapterReadingProgress[position.chapterId] = maxOf(
            maxChapterReadingProgress[position.chapterId] ?: 0f, actualReadProgress
        )
        positionRevision++
        _uiState.position = position
        if (returning && _uiState.originPosition?.chapterId == position.chapterId) {
            _uiState.originPosition = null
            returning = false
        }
        persistPosition(id, immediate = completed || SystemClock.uptimeMillis() - lastSaveTime >= 2500)
    }

    private fun persistPosition(id: Long, immediate: Boolean, closing: Boolean = false) {
        val position = actualPosition ?: return
        val savedBook = bookId
        val savedSession = session
        val savedSource = sourceId
        val revision = positionRevision
        val title = actualTitle
        val currentProgress = chapterReadingProgress.toMap()
        val locationHashes = pendingChapterLocationHashes.toMap()
        val maxProgress = maxChapterReadingProgress.toMap()
        val chapterIds = _uiState.progressMap.chapters.map { it.id }.toSet()
        fun matchesSnapshot() = currentSourceId() == savedSource &&
            (positionRevision == revision || (closing && bookId != savedBook))
        saveJob?.cancel()
        // A popped Navigation3 entry has already cancelled viewModelScope in onCleared.
        val scope = if (closing) CoroutineScope(Dispatchers.IO) else viewModelScope
        val job = scope.launch {
            if (!immediate) delay(300)
            saveMutex.withLock {
                if (!matchesSnapshot()) return@withLock
                if (!closing && (!isCurrent(savedSession) || id != requestId)) return@withLock
                lastSaveTime = SystemClock.uptimeMillis()
                withContext(Dispatchers.IO) {
                    // Keep the same chapter batch as progress, including reports merged by
                    // a newer save. Scrolling must invalidate an older page-fragment hash.
                    for ((chapter, hash) in locationHashes) {
                        val path = readingLocationPath(savedBook, chapter)
                        if (hash == null) userDataRepository.remove(path)
                        else userDataRepository.intUserData(path).set(hash)
                    }
                    bookRepository.updateUserReadingData(savedBook) { old ->
                        if (!matchesSnapshot() ||
                            (!closing && (session != savedSession || requestId != id))) old else {
                            val updated = old.copy(
                                currentChapterReadingProgressMap = old.currentChapterReadingProgressMap + currentProgress,
                                maxChapterReadingProgressMap = old.maxChapterReadingProgressMap + maxProgress.mapValues { (chapter, progress) ->
                                    maxOf(old.maxChapterReadingProgressMap[chapter] ?: 0f, progress)
                                }
                            )
                            val total = if (chapterIds.isEmpty()) old.readingProgress else
                                chapterIds.sumOf { (updated.maxChapterReadingProgressMap[it] ?: 0f).toDouble() }.toFloat() / chapterIds.size
                            updated.copy(lastReadTime = LocalDateTime.now(), lastReadChapterId = position.chapterId,
                                lastReadChapterTitle = title, readingProgress = total.coerceIn(0f, 1f))
                        }
                    }
                    if (matchesSnapshot() && bookRepository.getUserReadingData(savedBook).readingProgress >= 1f)
                        statsRepository.markBookFinished(savedBook)
                }
                // Back on Main: retire this batch only if no newer report replaced it.
                if (!closing && isCurrent(savedSession) && matchesSnapshot()) {
                    pendingChapterLocationHashes.keys.removeAll(locationHashes.keys)
                }
            }
        }
        if (!closing) saveJob = job
    }

    private fun updateDirectory(volumes: BookVolumes) {
        val chapters = volumes.volumes.flatMap { it.chapters }.distinctBy { it.id }
        _uiState.progressMap = ReaderProgressMap(chapters.map { ReaderProgressChapter(it.id, it.title, frozenPageCounts[it.id]) })
        initializeMap()
    }

    private fun layoutChanged(value: ReaderPaginationLayout) {
        if (layout?.key == value.key) return
        layout = value
        if (!mapInitialized) {
            mapJob?.cancel()
            mapJob = null
        }
        initializeMap()
    }

    private fun initializeMap() {
        val value = layout ?: return
        if (mapInitialized || mapJob?.isActive == true || _uiState.progressMap.isEmpty) return
        val savedSession = session
        val savedSource = sourceId
        val savedBook = bookId
        mapJob = viewModelScope.launch {
            val counts = withContext(Dispatchers.Default) {
                val indexes = pageIndexStore.readBook(savedSource, savedBook, value.key)
                buildMap {
                    for ((id, index) in indexes) {
                        ensureActive()
                        try {
                            val chapter = withContext(Dispatchers.IO) { bookRepository.getCachedChapterContent(id, savedBook) } ?: continue
                            val content = contentComponentRepository.getContentDataListFromJson(chapter.content)
                            val bodyKey = readerContentKey(content)
                            val indexKey = if (value.mode == "scroll") readerScrollPageIndexKey(bodyKey, chapter.title) else bodyKey
                            if (indexKey == index.contentKey) put(id, index.pageCount)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("ReaderViewModel", "Ignoring stale cached chapter index $id", error)
                        }
                    }
                }
            }
            if (!isCurrent(savedSession) || mapInitialized || layout?.key != value.key) return@launch
            frozenPageCounts = counts
            mapInitialized = true
            _uiState.progressMap = ReaderProgressMap(_uiState.progressMap.chapters.map { it.copy(pageCount = frozenPageCounts[it.id]) })
        }
    }

    private fun cachePagination(id: String, key: String, value: ReaderPaginationLayout, anchors: List<ReaderAnchor>) {
        val savedSource = sourceId
        val savedBook = bookId
        viewModelScope.launch(Dispatchers.IO) {
            pageIndexStore.write(savedSource, savedBook, id, ReaderPageIndex(value.key, key, anchors.size))
        }
    }

    fun updateTotalReadingTime(bookId: String, totalReadingTime: Int) {
        if (bookId.isBlank() || totalReadingTime <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.updateUserReadingData(bookId) {
                it.copy(lastReadTime = LocalDateTime.now(), totalReadTime = it.totalReadTime + totalReadingTime)
            }
        }
    }

    private fun addToReadingBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readingBookListUserData.update { it.filterNot { id -> id == bookId } + bookId }
        }
    }

    fun accumulateReadingTime(bookId: String, seconds: Int) {
        if (bookId.isBlank()) return
        readingStatsScope.launch { statsRepository.accumulateBookReadTime(bookId, seconds) }
    }

    override fun onCleared() {
        endSession()
        super.onCleared()
    }
}
