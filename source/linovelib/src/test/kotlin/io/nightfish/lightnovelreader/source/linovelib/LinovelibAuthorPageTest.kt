package io.nightfish.lightnovelreader.source.linovelib

import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibAuthorPageTest {
    private val host = LinovelibUrls.HOST

    @Test
    fun `real single author list finishes without a numeric page rule`() = runBlocking {
        val requests = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            "Vermillon", "$host/authorarticle/Vermillon.html",
            { url -> requests += url; fixture("single-page") }, LinovelibHtmlParser()
        )
        val results = page.getResultFlow().toList()
        assertEquals(listOf("4513"), results.bookIds())
        assertEquals(listOf("$host/authorarticle/Vermillon.html"), requests)
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun `author next links use shared paging and skip duplicate only middle page`() = runBlocking {
        val requests = mutableListOf<String>()
        val first = "$host/authorarticle/Fixture.html"
        val page = LinovelibLinkedExpandedPageDataSource(
            "Fixture", first,
            { url ->
                requests += url
                fixture(when {
                    "cursor=third" in url -> "multi-page-last"
                    "cursor=second" in url -> "multi-page-second"
                    else -> "multi-page-first"
                })
            }, LinovelibHtmlParser()
        )
        val results = async { page.getResultFlow().toList() }
        await { requests.size == 1 }
        repeat(10) { yield() }
        assertEquals(listOf(first), requests)
        page.loadMore()
        val collected = withTimeout(1_000) { results.await() }
        assertEquals(listOf(first, "$first?cursor=second", "$first?cursor=third"), requests)
        assertEquals(listOf("100", "200", "300"), collected.bookIds())
        assertTrue(collected.none { it is SearchResult.Error })
        assertTrue(collected.last() is SearchResult.End)
    }

    @Test
    fun `author identity is keyed by book id even for identical display names`() = runBlocking {
        val parser = LinovelibHtmlParser()
        parser.parseBookInformation("1", detail("Same", "/authorarticle/One.html"))
        parser.parseBookInformation("2", detail("Same", "/authorarticle/Two.html"))
        val requests = mutableListOf<String>()
        val provider = provider(parser) { url -> requests += url; fixture("single-page") }
        val one = provider.createAuthorPage(request("1", "Same"))
        val two = provider.createAuthorPage(request("2", "Same"))
        assertTrue(one !== two)
        assertTrue(requests.isEmpty())
        one.getResultFlow().toList()
        two.getResultFlow().toList()
        assertEquals(listOf("$host/authorarticle/One.html", "$host/authorarticle/Two.html"), requests)
    }

    @Test
    fun `cold author page lazily restores the exact link from its book details`() = runBlocking {
        val requests = mutableListOf<String>()
        val page = provider(LinovelibHtmlParser()) { url ->
            requests += url
            if (url == "$host/novel/4513.html") detail("Vermillon", "/authorarticle/Vermillon.html")
            else fixture("single-page")
        }.createAuthorPage(request())
        assertTrue(requests.isEmpty())
        assertEquals(listOf("4513"), page.getResultFlow().toList().bookIds())
        assertEquals(listOf("$host/novel/4513.html", "$host/authorarticle/Vermillon.html"), requests)
    }

    @Test
    fun `missing invalid and failed author detail links emit error and never use combined search`() = runBlocking {
        for (link in listOf("", "https://another.example/authorarticle/Vermillon.html", "/wenku/lastupdate_0_0_0_0_0_0_0_1_0.html", null)) {
            val requests = mutableListOf<String>()
            val page = provider(LinovelibHtmlParser()) { url ->
                requests += url
                if (link == null) error("Network failure")
                detail("Vermillon", link)
            }.createAuthorPage(request())
            val results = page.getResultFlow().toList()
            assertEquals(listOf("$host/novel/4513.html"), requests)
            assertEquals(2, results.size)
            assertTrue(results.first() is SearchResult.Error)
            assertTrue(results.last() is SearchResult.End)
        }
    }

    @Test
    fun `later book parse removes an obsolete author link`() {
        val parser = LinovelibHtmlParser()
        parser.parseBookInformation("1", detail("A", "/authorarticle/One.html"))
        assertEquals("$host/authorarticle/One.html", parser.authorTarget("1"))
        parser.parseBookInformation("1", detail("A", ""))
        assertEquals(null, parser.authorTarget("1"))
    }

    @Test
    fun `empty author list ends without requesting another page`() = runBlocking {
        val requests = mutableListOf<String>()
        val target = "$host/authorarticle/A.html"
        val page = LinovelibLinkedExpandedPageDataSource("A", target, { url ->
            requests += url
            "<ol class=book-ol></ol>"
        }, LinovelibHtmlParser())
        val results = page.getResultFlow().toList()
        assertEquals(listOf(target), requests)
        assertEquals(2, results.size)
        assertTrue(results.first() is SearchResult.Empty)
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun `author pages have independent load requests and cancellation sessions`() = runBlocking {
        val parser = LinovelibHtmlParser()
        parser.parseBookInformation("1", detail("Fixture", "/authorarticle/Fixture.html"))
        val requests = mutableListOf<String>()
        val provider = provider(parser) { url ->
            requests += url
            if ("cursor=" in url) fixture("multi-page-last") else fixture("multi-page-first")
        }
        val one = provider.createAuthorPage(request("1", "Fixture"))
        val two = provider.createAuthorPage(request("1", "Fixture"))
        val first = async { one.getResultFlow().toList() }
        val second = async { two.getResultFlow().toList() }
        await { requests.size == 2 }
        one.loadMore()
        assertEquals(listOf("100", "200", "300"), withTimeout(1_000) { first.await() }.bookIds())
        assertEquals(3, requests.size)
        assertTrue(second.isActive)
        second.cancelAndJoin()
        // A cancelled page's request channel must not keep a later collection alive.
        two.loadMore()
        val restarted = async { two.getResultFlow().toList() }
        await { requests.size == 4 }
        two.loadMore()
        assertEquals(listOf("100", "200", "300"), withTimeout(1_000) { restarted.await() }.bookIds())
    }

    @Test
    fun `cancellation from lazy detail and list request propagates`() = runBlocking {
        for (cached in listOf(false, true)) {
            val parser = LinovelibHtmlParser()
            if (cached) parser.parseBookInformation("4513", detail("Vermillon", "/authorarticle/Vermillon.html"))
            val cancellation = kotlin.coroutines.cancellation.CancellationException("Cancelled")
            val page = provider(parser) { throw cancellation }.createAuthorPage(request())
            try {
                page.getResultFlow().toList()
                org.junit.Assert.fail("Cancellation must propagate")
            } catch (actual: kotlin.coroutines.cancellation.CancellationException) {
                assertSame(cancellation, actual)
            }
        }
    }

    @Test
    fun `next links cannot leave the site or allowed list paths`() {
        val parser = LinovelibHtmlParser()
        for (href in listOf("https://another.example/authorarticle/A.html", "/novel/2.html", "javascript:alert(1)", "http://www.bilinovel.net/authorarticle/A.html")) {
            assertEquals(null, parser.nextListPage("<div id=pagelink><a class=next href='$href'>下一页</a></div>", "$host/authorarticle/A.html"))
        }
        assertEquals("$host/authorarticle/A.html?cursor=2", parser.nextListPage(
            "<div id=pagelink><a class=next href='?cursor=2#books'>下一页</a></div>", "$host/authorarticle/A.html"
        ))
    }

    @Test
    fun `pagination loop terminates without repeating the request`() = runBlocking {
        val requests = mutableListOf<String>()
        val target = "$host/authorarticle/A.html"
        val page = LinovelibLinkedExpandedPageDataSource("A", target, { url ->
            requests += url
            fixture("single-page") + "<div id=pagelink><a class=next href='$target#books'>下一页</a></div>"
        }, LinovelibHtmlParser())
        val results = page.getResultFlow().toList()
        assertEquals(listOf(target), requests)
        assertEquals(listOf("4513"), results.bookIds())
        assertTrue(results[results.lastIndex - 1] is SearchResult.Error)
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun `unsupported next links report a failure even without total page metadata`() = runBlocking {
        val requests = mutableListOf<String>()
        val target = "$host/authorarticle/A.html"
        val page = LinovelibLinkedExpandedPageDataSource("A", target, { url ->
            requests += url
            fixture("single-page") + "<div id=pagelink><a class=next href='https://another.example/list'>下一页</a></div>"
        }, LinovelibHtmlParser())
        val results = page.getResultFlow().toList()
        assertEquals(listOf(target), requests)
        assertTrue(results[results.lastIndex - 1] is SearchResult.Error)
        assertTrue(results.last() is SearchResult.End)
    }

    private fun provider(parser: LinovelibHtmlParser, loader: suspend (String) -> String) =
        LinovelibExplorePageProvider(loader, parser, { error("Author requests must not use combined search") })

    private fun request(bookId: String = "4513", author: String = "Vermillon") =
        RelatedBooksRequest(bookId, RelatedBookKind.AUTHOR, author)

    private fun detail(author: String, link: String) = """
        <h1 class="book-title">Book</h1>
        <span class="authorname"><a href="$link">$author</a></span>
    """

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/author/$name.html")).readText()

    private fun List<SearchResult>.bookIds() = filterIsInstance<SearchResult.SingleBook>().map { it.bookId }

    private suspend fun await(condition: () -> Boolean) = withTimeout(1_000) {
        while (!condition()) yield()
    }
}
