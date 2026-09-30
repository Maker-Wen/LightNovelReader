package io.nightfish.lightnovelreader.source.linovelib

import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Linovelib search implementation for the API 4 host.
 *
 * API 4's [SearchResult.MultipleBook] carries a book id; the host then loads the
 * full [io.nightfish.lightnovelreader.api.book.BookInformation] through its book
 * repository.  Keeping the provider at id level avoids the API 3 mutable-book
 * result type and also keeps search and related-page behaviour identical.
 */
internal class LinovelibSearchProvider(
    private val htmlLoader: suspend (String) -> String,
    private val searchHtmlLoader: suspend (String) -> LinovelibSearchResponse,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val host: String = LinovelibUrls.HOST
) : SearchProvider {
    override val searchTypes: List<SearchType> = listOf(
        SearchType(
            type = "book",
            name = "综合".local(),
            tip = "搜索书名、作者、标签".local()
        )
    )

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        val query = keyword.trim()
        val directBookId = parser.bookIdFromKeyword(keyword)
        diagnostics.info(
            "SEARCH_START",
            linkedMapOf("type" to searchType.type, "keyword" to query, "directBookId" to directBookId)
        )

        if (directBookId != null) {
            // A direct id/URL is a single-result search and should navigate directly.
            emit(SearchResult.SingleBook(directBookId))
            diagnostics.info("SEARCH_DONE", mapOf("mode" to "direct", "results" to 1, "bookId" to directBookId))
            emit(SearchResult.End())
            return@flow
        }

        if (query.isEmpty()) {
            emit(SearchResult.Empty())
            emit(SearchResult.End())
            return@flow
        }

        val searchResponse = runCatching { searchHtmlLoader(query) }
            .onFailure {
                it.rethrowIfCancellation()
                diagnostics.error("SEARCH_REQUEST_ERROR", it, mapOf("keyword" to query))
            }
            .getOrNull()
        if (searchResponse == null) {
            emit(SearchResult.Error("Linovelib 搜索请求失败，请稍后重试"))
            emit(SearchResult.End())
            return@flow
        }

        // Linovelib redirects exact matches to a book page.  Treat that as a
        // single result so the host opens the detail page immediately.
        val redirectedBookId = searchResponse.directBookId(parser)
        if (redirectedBookId != null) {
            emit(SearchResult.SingleBook(redirectedBookId))
            diagnostics.info(
                "SEARCH_DONE",
                mapOf("mode" to "redirect", "results" to 1, "bookId" to redirectedBookId)
            )
            emit(SearchResult.End())
            return@flow
        }

        val searchRow = parser.parseListRow("Search", searchResponse.html)
        val books = searchRow.books.distinctBy { it.id }
        diagnostics.info(
            "SEARCH_SOURCE",
            linkedMapOf("source" to "guarded-search", "keyword" to query, "books" to books.size)
        )
        if (books.isEmpty()) {
            emit(
                if (searchResponse.hasEmptyResultList()) SearchResult.Empty()
                else SearchResult.Error("无法识别 Linovelib 搜索结果，请稍后重试")
            )
        } else {
            // API 4 resolves details from each id in the host repository.
            books.forEach { emit(SearchResult.MultipleBook(it.id)) }
        }
        diagnostics.info("SEARCH_DONE", mapOf("mode" to "text", "keyword" to query, "results" to books.size))
        emit(SearchResult.End())
    }

    /**
     * Search used by a related-tag expanded page.  Expanded-page consumers in
     * API 4 accept both result variants, and the historical plugin emitted
     * [SearchResult.SingleBook] for each row.  Preserve that stream shape while
     * keeping the regular search API's multi-result contract intact.
     */
    fun searchBookIds(keyword: String): Flow<SearchResult> = flow {
        search(searchTypes.first(), keyword).collect { result ->
            emit(
                if (result is SearchResult.MultipleBook) {
                    SearchResult.SingleBook(result.bookId)
                } else {
                    result
                }
            )
        }
    }
}
