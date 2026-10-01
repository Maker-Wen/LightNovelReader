package indi.dmzz_yyhyy.lightnovelreader.data.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.data.web.EmptyWebDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.RelatedBooksDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class RelatedBooksSourceTest {
    @Test
    fun invalidSourceOrRequestNeverCreatesPageAndValidCallsKeepRawValue() {
        val requests = mutableListOf<RelatedBooksRequest>()
        val origin = authorSource(requests)
        var current = proxy(origin)
        var available = true
        val provider = object : WebBookDataSourceProvider {
            override fun isWebDataSourceFounded() = available
            override val value get() = current
        }
        val request = RelatedBooksRequest("42", RelatedBookKind.AUTHOR, " 川原 礫 ")

        assertTrue(runCatching { provider.createRelatedBooksPage("site:other", request) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { provider.createRelatedBooksPage("site:one", request.copy(kind = RelatedBookKind.TAG)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { provider.createRelatedBooksPage("site:one", request.copy(value = "  ")) }.exceptionOrNull() is IllegalArgumentException)
        available = false
        assertNull(provider.relatedBooksSource())
        assertTrue(runCatching { provider.createRelatedBooksPage("site:one", request) }.isFailure)
        available = true
        current = proxy(EmptyWebDataSource)
        assertNull(provider.relatedBooksSource())
        assertTrue(runCatching { provider.createRelatedBooksPage("site:one", request) }.isFailure)
        current = proxy(origin)

        assertTrue(requests.isEmpty())
        val first = provider.createRelatedBooksPage("site:one", request)
        val second = provider.createRelatedBooksPage("site:one", request)
        assertNotSame(first, second)
        assertEquals(request.value, first.title)
        assertEquals(listOf(request, request), requests)
    }

    @Test
    fun legacySourceNeedsNoRelatedInterfaceAndCannotCreateRelatedPage() {
        val origin = Proxy.newProxyInstance(
            WebBookDataSource::class.java.classLoader,
            arrayOf(WebBookDataSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getId" -> Identifier("site", "legacy")
                else -> error("Unexpected legacy source call: ${method.name}")
            }
        } as WebBookDataSource
        val source = proxy(origin)
        val provider = object : WebBookDataSourceProvider {
            override fun isWebDataSourceFounded() = true
            override val value = source
        }
        assertEquals(source, provider.relatedBooksSource())
        assertTrue(
            runCatching {
                provider.createRelatedBooksPage("site:legacy", RelatedBooksRequest("42", RelatedBookKind.AUTHOR, "author"))
            }.exceptionOrNull() is IllegalStateException
        )
        assertTrue(origin !is RelatedBooksDataSource)
    }

    private fun authorSource(requests: MutableList<RelatedBooksRequest>): WebBookDataSource =
        Proxy.newProxyInstance(
            WebBookDataSource::class.java.classLoader,
            arrayOf(WebBookDataSource::class.java, RelatedBooksDataSource::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getId" -> Identifier("site", "one")
                "getSupportedRelatedBookKinds" -> setOf(RelatedBookKind.AUTHOR)
                "createRelatedBooksPage" -> {
                    val request = args!![0] as RelatedBooksRequest
                    requests += request
                    object : ExploreExpandedPageDataSource {
                        override val title = request.value
                        override val filters = emptyList<Filter<*>>()
                        override fun loadMore() = Unit
                        override fun getResultFlow() = emptyFlow<SearchResult>()
                    }
                }
                else -> error("Unexpected source call: ${method.name}")
            }
        } as WebBookDataSource

    private fun proxy(origin: WebBookDataSource): ProxyWebBookDataSource =
        Proxy.newProxyInstance(
            ProxyWebBookDataSource::class.java.classLoader,
            arrayOf(ProxyWebBookDataSource::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getOrigin" -> origin
                "getId" -> origin.id
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Proxy(${origin.id})"
                else -> error("Unexpected proxy call: ${method.name}")
            }
        } as ProxyWebBookDataSource
}
