package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8

import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore.expanedpage.AuthorBooksPageDataSource
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthorBooksPageDataSourceTest {
    @Test
    fun eachCollectionUsesRawAuthorAndLoadMoreDoesNotStartSearch() = runBlocking {
        val author = " 川原 礫 "
        val queries = mutableListOf<String>()
        val page = AuthorBooksPageDataSource(author) { query ->
            queries += query
            flow {
                emit(SearchResult.SingleBook("42"))
                emit(SearchResult.End())
            }
        }
        val resultFlow = page.getResultFlow()
        page.loadMore()
        page.loadMore()
        assertTrue(queries.isEmpty())
        assertEquals(author, page.title)
        assertTrue(page.filters.isEmpty())
        repeat(2) {
            val results = resultFlow.toList()
            assertEquals(2, results.size)
            assertTrue(results[0] is SearchResult.SingleBook)
            assertTrue(results[1] is SearchResult.End)
        }
        assertEquals(listOf(author, author), queries)
    }

    @Test
    fun cancellationStopsSearchAndAnotherPageDoesNotShareSession() = runBlocking {
        withTimeout(5_000) {
            var started = 0
            var stopped = 0
            val search: (String) -> kotlinx.coroutines.flow.Flow<SearchResult> = {
                flow {
                    started++
                    try {
                        emit(SearchResult.SingleBook("42"))
                        awaitCancellation()
                    } finally {
                        stopped++
                    }
                }
            }
            val firstPage = AuthorBooksPageDataSource("author", search)
            val secondPage = AuthorBooksPageDataSource("author", search)
            val first = launch(start = CoroutineStart.UNDISPATCHED) { firstPage.getResultFlow().toList() }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { secondPage.getResultFlow().toList() }
            assertEquals(2, started)
            first.cancelAndJoin()
            assertEquals(1, stopped)
            assertTrue(second.isActive)
            second.cancelAndJoin()
            assertEquals(2, stopped)
        }
    }

    @Test
    fun wenku8FactoryCreatesNewAuthorPagesAndKeepsExistingTagRoute() {
        val api = Wenku8Api()
        try {
            assertEquals(setOf(RelatedBookKind.AUTHOR), api.supportedRelatedBookKinds)
            val request = RelatedBooksRequest("42", RelatedBookKind.AUTHOR, " 川原 礫 ")
            val first = api.createRelatedBooksPage(request)
            val second = api.createRelatedBooksPage(request)
            assertNotSame(first, second)
            assertEquals(request.value, first.title)
            assertTrue(runCatching { api.createRelatedBooksPage(request.copy(kind = RelatedBookKind.TAG)) }.isFailure)
            assertTrue(runCatching { api.createRelatedBooksPage(request.copy(value = " ")) }.isFailure)
            assertEquals(Route.Main.Explore.Expanded("校园"), api.progressBookTagClick("校园"))
        } finally {
            api.ktorClient.close()
        }
    }
}
