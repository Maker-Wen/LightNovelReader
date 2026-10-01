package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore.expanedpage

import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** An independent author session using Wenku8's existing complete search flow. */
internal class AuthorBooksPageDataSource(
    private val author: String,
    private val search: (String) -> Flow<SearchResult>,
) : ExploreExpandedPageDataSource {
    override val title: String = author
    override val filters: List<Filter<*>> = emptyList()

    // The search dispatcher already fetches every result page.
    override fun loadMore() = Unit

    override fun getResultFlow(): Flow<SearchResult> = flow { emitAll(search(author)) }
}
