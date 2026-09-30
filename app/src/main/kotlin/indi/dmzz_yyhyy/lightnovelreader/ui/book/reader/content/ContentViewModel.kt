package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

interface ContentViewModel {
    val uiState: ContentUiState
    fun changeBookId(id: String)
    fun loadNextChapter()
    fun loadPrevChapter()
    fun changeChapter(id: String, position: ChapterPosition? = null, requestId: Long = 0)
    fun capturePosition(): ReaderPosition?
    fun dispose()
}
