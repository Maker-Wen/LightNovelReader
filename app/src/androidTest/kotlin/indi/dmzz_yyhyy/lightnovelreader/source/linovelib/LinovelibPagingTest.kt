package indi.dmzz_yyhyy.lightnovelreader.source.linovelib

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.source.linovelib.LinovelibHtmlParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs Linovelib's real author factory, HTML parsing and pagination flows on Android.
 * Only HTML transport is replaced by assets; no replacement page implementation is used.
 * A single reflection fixture constructs the module's internal provider and invokes
 * its public author factory without publishing production API or accessing fields.
 */
@RunWith(AndroidJUnit4::class)
class LinovelibPagingTest {
    private val host = "https://www.bilinovel.net"
    private val firstUrl = "$host/authorarticle/Fixture.html"

    @Test
    fun sharedPagingWaitsForLoadMoreSkipsDuplicatePageAndRetainsQueryOnlyPath() = runBlocking {
        withTimeout(5_000) {
            val requests = mutableListOf<String>()
            val page = authorPage(cachedParser()) { url ->
                requests += url
                fixture(when {
                    "cursor=third" in url -> "multi-page-last"
                    "cursor=second" in url -> "multi-page-second"
                    else -> "multi-page-first"
                })
            }
            val results = async(start = CoroutineStart.UNDISPATCHED) { page.getResultFlow().toList() }

            assertEquals(listOf(firstUrl), requests)
            assertTrue(results.isActive)
            page.loadMore()

            val collected = results.await()
            assertEquals(listOf(firstUrl, "$firstUrl?cursor=second", "$firstUrl?cursor=third"), requests)
            assertEquals(listOf("100", "200", "300"), collected.bookIds())
            assertFalse(collected.any { it is SearchResult.Error })
            assertTrue(collected.last() is SearchResult.End)
        }
    }

    @Test
    fun refreshRecollectsPageOneAndCancelledLoadMoreCannotAdvanceIt() = runBlocking {
        withTimeout(5_000) {
            val requests = mutableListOf<String>()
            val page = authorPage(cachedParser()) { url ->
                requests += url
                fixture(if ("cursor=" in url) "multi-page-last" else "multi-page-first")
            }
            val oldResults = mutableListOf<SearchResult>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) { page.getResultFlow().toList(oldResults) }
            assertEquals(listOf("100", "200"), oldResults.bookIds())
            first.cancelAndJoin()
            page.loadMore()

            val refreshed = async(start = CoroutineStart.UNDISPATCHED) { page.getResultFlow().toList() }
            assertEquals(listOf(firstUrl, firstUrl), requests)
            assertTrue(refreshed.isActive)
            page.loadMore()

            assertEquals(listOf("100", "200", "300"), refreshed.await().bookIds())
            assertEquals(listOf(firstUrl, firstUrl, "$firstUrl?cursor=second"), requests)
            assertFalse(oldResults.any { it is SearchResult.End || it is SearchResult.Error })
        }
    }

    @Test
    fun twoAuthorPageSessionsKeepLoadMoreAndCancellationIndependent() = runBlocking {
        withTimeout(5_000) {
            val requests = mutableListOf<String>()
            val factory = provider(cachedParser()) { url ->
                requests += url
                fixture(if ("cursor=" in url) "multi-page-last" else "multi-page-first")
            }
            val one = factory.createAuthorPage(request())
            val two = factory.createAuthorPage(request())
            assertNotSame(one, two)
            val first = async(start = CoroutineStart.UNDISPATCHED) { one.getResultFlow().toList() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { two.getResultFlow().toList() }

            assertEquals(listOf(firstUrl, firstUrl), requests)
            one.loadMore()
            assertEquals(listOf("100", "200", "300"), first.await().bookIds())
            assertTrue(second.isActive)
            assertEquals(3, requests.size)
            second.cancelAndJoin()
            two.loadMore()

            val restarted = async(start = CoroutineStart.UNDISPATCHED) { two.getResultFlow().toList() }
            assertEquals(firstUrl, requests.last())
            assertEquals(4, requests.size)
            assertTrue(restarted.isActive)
            two.loadMore()
            assertEquals(listOf("100", "200", "300"), restarted.await().bookIds())
            assertEquals(5, requests.size)
        }
    }

    @Test
    fun coldFactoryLazilyRestoresAuthorLinkAndSameNamesKeepBookIdentity() = runBlocking {
        withTimeout(5_000) {
            val requests = mutableListOf<String>()
            val factory = provider(LinovelibHtmlParser()) { url ->
                requests += url
                when (url) {
                    "$host/novel/4513.html" -> detail("Same", "/authorarticle/One.html")
                    "$host/novel/42.html" -> detail("Same", "/authorarticle/Two.html")
                    else -> fixture("single-page")
                }
            }
            val one = factory.createAuthorPage(request("4513", "Same"))
            val two = factory.createAuthorPage(request("42", "Same"))
            assertTrue(requests.isEmpty())
            assertEquals(listOf("4513"), one.getResultFlow().toList().bookIds())
            assertEquals(listOf("4513"), two.getResultFlow().toList().bookIds())
            assertEquals(listOf(
                "$host/novel/4513.html", "$host/authorarticle/One.html",
                "$host/novel/42.html", "$host/authorarticle/Two.html",
            ), requests)
        }
    }

    @Test
    fun failedDetailOrListTerminatesWithErrorAndNeverFallsBackToCombinedSearch() = runBlocking {
        withTimeout(5_000) {
            for (stage in listOf("detail", "missing-link", "external-link", "list")) {
                val requests = mutableListOf<String>()
                val page = authorPage(LinovelibHtmlParser()) { url ->
                    requests += url
                    when {
                        stage == "detail" -> error("Details transport failed")
                        url.contains("/novel/") -> detail("Fixture", when (stage) {
                            "missing-link" -> ""
                            "external-link" -> "https://another.example/authorarticle/Fixture.html"
                            else -> "/authorarticle/Fixture.html"
                        })
                        else -> error("List transport failed")
                    }
                }
                val results = page.getResultFlow().toList()
                assertEquals(stage, 2, results.size)
                assertTrue(stage, results.first() is SearchResult.Error)
                assertTrue(stage, results.last() is SearchResult.End)
                assertEquals(stage, if (stage == "list") 2 else 1, requests.size)
                assertEquals("$host/novel/4513.html", requests.first())
            }
        }
    }

    @Test
    fun cancellingDetailAndListTransportStopsLoaderWithoutReportingFailure() = runBlocking {
        withTimeout(5_000) {
            for (cached in listOf(false, true)) {
                var started = false
                var stopped = false
                val requests = mutableListOf<String>()
                val results = mutableListOf<SearchResult>()
                val parser = if (cached) cachedParser() else LinovelibHtmlParser()
                val page = authorPage(parser) { url ->
                    requests += url
                    started = true
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
                val job = launch(start = CoroutineStart.UNDISPATCHED) { page.getResultFlow().toList(results) }
                assertTrue(started)
                assertFalse(stopped)
                assertEquals(listOf(if (cached) firstUrl else "$host/novel/4513.html"), requests)
                job.cancelAndJoin()
                assertTrue(stopped)
                page.loadMore()
                assertTrue(results.isEmpty())
                assertEquals(1, requests.size)
            }
        }
    }

    private fun cachedParser() = LinovelibHtmlParser().also {
        it.parseBookInformation("4513", detail("Fixture", "/authorarticle/Fixture.html"))
    }

    private fun authorPage(
        parser: LinovelibHtmlParser,
        loader: suspend (String) -> String,
    ): ExploreExpandedPageDataSource = provider(parser, loader).createAuthorPage(request())

    private fun provider(parser: LinovelibHtmlParser, loader: suspend (String) -> String): AuthorFactory =
        AuthorFactory(loader, parser, host)

    /** Reflects only the internal provider boundary; every returned page is production code. */
    private class AuthorFactory(loader: suspend (String) -> String, parser: LinovelibHtmlParser, host: String) {
        private val providerClass = Class.forName(
            "io.nightfish.lightnovelreader.source.linovelib.LinovelibExplorePageProvider"
        )
        private val forbiddenSearch: (String) -> Flow<SearchResult> = {
            error("Author queries must not use combined search")
        }
        private val provider = providerClass.constructors.single { it.parameterCount == 4 }
            .newInstance(loader, parser, forbiddenSearch, host)
        private val createAuthorPage = providerClass.getMethod("createAuthorPage", RelatedBooksRequest::class.java)

        fun createAuthorPage(request: RelatedBooksRequest): ExploreExpandedPageDataSource =
            createAuthorPage.invoke(provider, request) as ExploreExpandedPageDataSource
    }

    private fun request(bookId: String = "4513", author: String = "Fixture") =
        RelatedBooksRequest(bookId, RelatedBookKind.AUTHOR, author)

    private fun detail(author: String, link: String) = """
        <h1 class="book-title">Book</h1>
        <span class="authorname"><a href="$link">$author</a></span>
    """.trimIndent()

    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("author/$name.html").bufferedReader().use { it.readText() }

    private fun List<SearchResult>.bookIds() = filterIsInstance<SearchResult.SingleBook>().map { it.bookId }
}
