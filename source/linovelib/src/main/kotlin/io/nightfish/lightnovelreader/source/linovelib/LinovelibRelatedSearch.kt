package io.nightfish.lightnovelreader.source.linovelib

import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal object LinovelibRelatedSearch {
    private const val AUTHOR_PREFIX = "作者："

    fun authorDisplayTag(author: String): String = "$AUTHOR_PREFIX$author"

    fun displayTags(
        author: String,
        tags: List<String>,
        publishingHouse: String = ""
    ): List<String> = buildList {
        if (publishingHouse.isNotBlank()) add(publishingHouse)
        if (author.isNotBlank()) add(authorDisplayTag(author))
        addAll(tags.filter(String::isNotBlank))
    }.distinct()

    fun keyword(displayTag: String): String = displayTag.removePrefix(AUTHOR_PREFIX).trim()
}

/** Expanded page backed by a Linovelib list URL and its pagination controls. */
internal class LinovelibLinkedExpandedPageDataSource(
    private val displayTag: String,
    private val targetUrl: String,
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    override val filters: List<Filter<*>> = emptyList(),
    private val filteredUrl: (() -> String)? = null,
    private val targetUrlLoader: (suspend () -> String)? = null
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag

    @Volatile
    private var loadMoreRequests: Channel<Unit>? = null

    override fun loadMore() {
        loadMoreRequests?.trySend(Unit)
    }

    override fun getResultFlow(): Flow<SearchResult> = flow {
        val requests = Channel<Unit>(Channel.CONFLATED)
        loadMoreRequests = requests
        // A filter change cancels and recollects this flow.  Keep pagination
        // state local to one collection so a new filter starts at page one.
        val emittedBookIds = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()
        var currentPage = 1
        try {
            val firstPageUrl = runCatching {
                val url = targetUrlLoader?.invoke() ?: filteredUrl?.invoke() ?: targetUrl
                requireNotNull(parser.validatedListTarget(url)) { "Unsupported list address" }
            }.onFailure(Throwable::rethrowIfCancellation).getOrElse {
                emit(SearchResult.Error("书单地址加载失败，请稍后重试"))
                emit(SearchResult.End())
                return@flow
            }
            var pageUrl = firstPageUrl
            var lastPage = 1
            while (true) {
                if (!visitedUrls.add(pageUrl)) {
                    emit(SearchResult.Error("书单分页地址重复，请稍后重试"))
                    break
                }
                val html = runCatching { htmlLoader(pageUrl) }
                    .onFailure(Throwable::rethrowIfCancellation)
                    .getOrElse {
                        emit(SearchResult.Error("书单加载失败，请稍后重试"))
                        emit(SearchResult.End())
                        return@flow
                    }
                if (currentPage == 1) {
                    lastPage = parser.parseLastPage(html)
                }

                val parsedBooks = parser.parseListRow(displayTag, html).books
                if (parsedBooks.isEmpty() &&
                    !LinovelibSearchResponse(firstPageUrl, html).hasEmptyResultList()
                ) {
                    emit(SearchResult.Error("未能识别书单页面，请稍后重试"))
                    emit(SearchResult.End())
                    return@flow
                }
                val books = parsedBooks.filter { emittedBookIds.add(it.id) }
                // Expanded-page collectors add both SingleBook and MultipleBook
                // to their list; SingleBook preserves the plugin's behaviour.
                books.forEach { emit(SearchResult.SingleBook(it.id)) }

                if (currentPage == 1 && books.isEmpty()) break
                // The page's actual next link takes precedence over numeric
                // URL templates, including author lists. Older wenku/top pages
                // without next links retain their existing page-number fallback.
                val nextPage = parser.parseNextListPage(html, pageUrl)
                if (nextPage.invalid) {
                    emit(SearchResult.Error("书单分页地址不受支持，请稍后重试"))
                    break
                }
                val nextPageUrl = nextPage.url
                    ?: if (currentPage < lastPage) {
                        LinovelibUrls.listPage(firstPageUrl, currentPage + 1)
                    } else null
                if (nextPageUrl == null) {
                    if (currentPage < lastPage) {
                        emit(SearchResult.Error("书单分页地址不受支持，请稍后重试"))
                    }
                    break
                }
                if (nextPageUrl in visitedUrls) {
                    emit(SearchResult.Error("书单分页地址重复，请稍后重试"))
                    break
                }
                // Do not issue the next page until the host explicitly asks for
                // more.  CONFLATED avoids queueing duplicate taps.
                if (books.isNotEmpty()) requests.receive()
                currentPage++
                pageUrl = nextPageUrl
            }
            if (emittedBookIds.isEmpty()) emit(SearchResult.Empty())
            emit(SearchResult.End())
        } finally {
            if (loadMoreRequests === requests) loadMoreRequests = null
            requests.close()
        }
    }
}

/** Expanded page that resolves a tag through the regular search provider. */
internal class LinovelibRelatedExpandedPageDataSource(
    private val searchBookIds: (String) -> Flow<SearchResult>,
    private val displayTag: String
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag
    override val filters: List<Filter<*>> = emptyList()

    override fun loadMore() = Unit

    override fun getResultFlow(): Flow<SearchResult> =
        searchBookIds(LinovelibRelatedSearch.keyword(displayTag))
}

/** Navigation 3 route used by [LinovelibWebDataSource] for tag clicks. */
internal object LinovelibRelatedNavigation {
    fun createExpandedRoute(pageId: String): Route.Main.Explore.Expanded =
        Route.Main.Explore.Expanded(pageId)
}
