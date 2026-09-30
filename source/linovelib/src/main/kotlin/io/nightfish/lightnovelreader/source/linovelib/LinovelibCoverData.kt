package io.nightfish.lightnovelreader.source.linovelib

import android.net.Uri
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessingRepositoryApi
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Repairs covers stored by older Linovelib hosts and normalizes covers returned
 * by the source.  It is enabled only while this source is the active source.
 */
internal class LinovelibCoverData(
    private val localData: LocalBookDataSourceApi,
    private val textProcessing: TextProcessingRepositoryApi,
    private val bookshelves: BookshelfRepositoryApi,
    private val isActive: () -> Boolean
) : TextProcessor {
    override val enabled: Boolean get() = isActive()

    // localData is scoped to the active source. Do not run this against another
    // source when the user switches sources during application startup.
    fun onLoad() {
        runBlocking(Dispatchers.IO) {
            val bookIds = localData.getAllUserReadingData().map { it.id } +
                bookshelves.getAllBookshelfBooksMetadata().map { it.id }
            bookIds.distinct().forEach { repairCover(it) }
        }
        textProcessing.registerProcessors(LINOVELIB_SOURCE_ID, this)
    }

    suspend fun repairCover(bookId: String) {
        localData.getBookInformation(bookId)?.let { book ->
            val original = book.coverUri.toString()
            val current = LinovelibUrls.currentCoverUrl(original)
            if (current != original) {
                localData.updateBookInformation(book.copy(coverUri = Uri.parse(current)))
            }
        }
    }

    override fun processBookVolumes(bookVolumes: BookVolumes): BookVolumes = bookVolumes

    override fun processText(text: String): String = text

    override fun processBookInformation(bookInformation: BookInformation): BookInformation {
        val original = bookInformation.coverUri.toString()
        val current = LinovelibUrls.currentCoverUrl(original)
        if (current == original) return bookInformation
        return bookInformation.copy(coverUri = Uri.parse(current))
    }

    override fun processChapterContent(
        bookId: String,
        chapterContent: ChapterContent,
        componentProcessor: ComponentProcessor
    ): ChapterContent = chapterContent

    override fun processExploreBooksRow(exploreDisplayBook: ExploreDisplayBook): ExploreDisplayBook =
        exploreDisplayBook
}
