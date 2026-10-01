package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import androidx.compose.runtime.Stable
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItem
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError

@Stable
interface DetailUiState {
    val bookInformation: Result<BookInformation, WebRequestError>?
    val sourceId: String?
    val authorRequest: RelatedBooksRequest?
    val bookVolumes: Result<BookVolumes, WebRequestError>?
    val userReadingData: UserReadingData?
    val isCached: Boolean
    val downloadItem: DownloadItem?
    val isInBookshelf: Boolean
}

class MutableDetailUiState : DetailUiState {
    override var bookInformation: Result<BookInformation, WebRequestError>? by mutableStateOf(null)
    override var sourceId: String? by mutableStateOf(null)
    override var authorRequest: RelatedBooksRequest? by mutableStateOf(null)
    override var bookVolumes: Result<BookVolumes, WebRequestError>? by mutableStateOf(null)
    override var userReadingData: UserReadingData? by mutableStateOf(null)
    override var isCached: Boolean by mutableStateOf(false)
    override var downloadItem: DownloadItem? by mutableStateOf(null)
    override var isInBookshelf: Boolean by mutableStateOf(false)
}

internal fun authorRequestForDetail(
    sourceId: String?,
    bookId: String,
    author: String,
    supportedKinds: Set<RelatedBookKind>,
): RelatedBooksRequest? =
    if (!sourceId.isNullOrBlank() && RelatedBookKind.AUTHOR in supportedKinds && author.isNotBlank())
        RelatedBooksRequest(bookId, RelatedBookKind.AUTHOR, author.trim())
    else null
