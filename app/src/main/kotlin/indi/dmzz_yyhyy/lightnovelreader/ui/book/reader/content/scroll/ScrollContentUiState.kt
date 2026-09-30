package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPaginationLayout
import io.nightfish.lightnovelreader.api.error.WebRequestError

interface ScrollContentUiState : ContentUiState {
    val lazyListState: LazyListState
    val contentList: List<Pair<String, Result<ChapterContentUiState, WebRequestError>>?>
    val setPaginationLayout: (ReaderPaginationLayout) -> Unit
    val setHeaderHeight: (ScrollGeometryKey, Int) -> Unit
    val setComponentSize: (ScrollGeometryKey, Int, Int, Float) -> Unit
    val setTextLayout: (ScrollGeometryKey, Int, TextLayoutResult) -> Unit
    val writeProgressRightNow: () -> Unit
    override val readingChapterContent: Result<ChapterContentUiState, WebRequestError>?
        get() = contentList.firstOrNull { it?.first == readingChapterId }?.second
}

class MutableScrollContentUiSate(
    override val loadNextChapter: () -> Unit,
    override val loadPrevChapter: () -> Unit,
    override val changeChapter: (String) -> Unit,
    override val retry: () -> Unit,
    override val setPaginationLayout: (ReaderPaginationLayout) -> Unit,
    override val setHeaderHeight: (ScrollGeometryKey, Int) -> Unit,
    override val setComponentSize: (ScrollGeometryKey, Int, Int, Float) -> Unit,
    override val setTextLayout: (ScrollGeometryKey, Int, TextLayoutResult) -> Unit,
    override val writeProgressRightNow: () -> Unit
) : ScrollContentUiState {
    override var bookId by mutableStateOf("")
    override var readingProgress by mutableFloatStateOf(0f)
    override var lazyListState by mutableStateOf(preloadedChapterListState())
    override var readingChapterId: String? by mutableStateOf(null)
    override var isPositioning by mutableStateOf(false)
    override val contentList = mutableStateListOf<Pair<String, Result<ChapterContentUiState, WebRequestError>>?>(null, null, null)
}

/** Includes the header, whose height is not represented by the processed body content key. */
data class ScrollGeometryKey(
    val bookId: String,
    val chapterId: String,
    val contentKey: String,
    val title: String,
    val layoutKey: String
)

@OptIn(ExperimentalFoundationApi::class)
internal fun preloadedChapterListState() = LazyListState(
    cacheWindow = LazyLayoutCacheWindow(
        aheadFraction = 1000f,
        behindFraction = 1000f,
    )
)
