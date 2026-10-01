package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.R
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelatedBooksViewModelTest {
    private lateinit var fixture: RelatedBooksTestFixture
    private val request = RelatedBooksRequest("origin-book", RelatedBookKind.AUTHOR, "原始作者")

    @Before
    fun setUp() { fixture = RelatedBooksTestFixture() }

    @After
    fun tearDown() { fixture.close() }

    @Test
    fun refreshCancelsPreviousCollectorBeforeRestartingSamePage() = runBlocking {
        val cleanupGate = CompletableDeferred<Unit>()
        val page = ControlledExpandedPage(cleanupGate)
        val source = ControlledRelatedSource(pageFactory = { page })
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.initRelated(source.id.toString(), request) }
        awaitUi("first collection") { page.sessions.size == 1 }
        val first = page.sessions.single()
        first.emit(SearchResult.SingleBook("old"))
        awaitUi("old row") { model.uiState.bookList.map { it.first } == listOf("old") }

        try {
            onMain { model.loadBookResult() }
            awaitUi("old collector entering cancellation cleanup") { first.cleanupStarted.get() }
            delay(100)
            assertEquals("A new collector started before the previous one finished cleanup", 1, page.sessions.size)
        } finally {
            cleanupGate.complete(Unit)
        }
        awaitUi("old collector cancellation and refreshed collection") {
            first.cancelled.get() && page.sessions.size == 2
        }
        val second = page.sessions.last()
        assertTrue(onMain { model.uiState.bookList.isEmpty() && model.uiState.isLoading })
        first.emit(SearchResult.SingleBook("stale"))
        second.emit(SearchResult.SingleBook("fresh"))
        second.emit(SearchResult.End())
        awaitUi("refreshed list completion") { model.uiState.isComplete }
        assertEquals(listOf("fresh"), onMain { model.uiState.bookList.map { it.first } })
        assertEquals(1, source.requests.size)
        assertNull(onMain { model.uiState.errorMessage })
    }

    @Test
    fun returningToInitializedEntryDoesNotResetItsResultsOrPagination() = runBlocking {
        val page = ControlledExpandedPage()
        val source = ControlledRelatedSource(pageFactory = { page })
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.initRelated(source.id.toString(), request) }
        awaitUi("entry collection") { page.sessions.size == 1 }
        page.sessions.single().emit(SearchResult.MultipleBook("retained"))
        awaitUi("retained row") { model.uiState.bookList.size == 1 }
        onMain {
            model.loadMore()
            model.initRelated(source.id.toString(), request)
        }
        assertEquals(1, page.sessions.size)
        assertEquals(1, page.loadMoreCalls.get())
        assertEquals(listOf("retained"), onMain { model.uiState.bookList.map { it.first } })
        assertFalse(onMain { model.uiState.isComplete })
    }

    @Test
    fun repeatedAuthorQueryUsesIndependentViewModelPageSessions() = runBlocking {
        val source = ControlledRelatedSource()
        fixture.provider.update(source)
        val firstModel = fixture.expanded()
        val secondModel = fixture.expanded()
        onMain {
            firstModel.initRelated(source.id.toString(), request)
            secondModel.initRelated(source.id.toString(), request)
        }
        awaitUi("two independently collected pages") {
            source.createdPages.size == 2 && source.createdPages.all {
                (it as ControlledExpandedPage).sessions.size == 1
            }
        }
        val first = source.createdPages[0] as ControlledExpandedPage
        val second = source.createdPages[1] as ControlledExpandedPage
        assertNotSame(first, second)
        first.sessions.single().emit(SearchResult.SingleBook("first-entry"))
        second.sessions.single().emit(SearchResult.SingleBook("second-entry"))
        awaitUi("both independent rows") {
            firstModel.uiState.bookList.size == 1 && secondModel.uiState.bookList.size == 1
        }
        onMain { firstModel.loadMore() }
        assertEquals(1, first.loadMoreCalls.get())
        assertEquals(0, second.loadMoreCalls.get())
        assertEquals(listOf("first-entry"), onMain { firstModel.uiState.bookList.map { it.first } })
        assertEquals(listOf("second-entry"), onMain { secondModel.uiState.bookList.map { it.first } })
        onMain { firstModel.loadBookResult() }
        awaitUi("first entry refreshed") { first.sessions.size == 2 }
        assertEquals(1, second.sessions.size)
        assertEquals(listOf("second-entry"), onMain { secondModel.uiState.bookList.map { it.first } })
    }

    @Test
    fun continuousSingleBookEmissionsCanLoadMoreAndEndAsOneList() = runBlocking {
        val page = ControlledExpandedPage()
        val source = ControlledRelatedSource(pageFactory = { page })
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain {
            model.initRelated(source.id.toString(), request)
            model.loadMore()
        }
        awaitUi("continuous collection") { page.sessions.size == 1 }
        assertEquals(0, page.loadMoreCalls.get())
        val session = page.sessions.single()
        session.emit(SearchResult.SingleBook("one"))
        awaitUi("first page") { model.uiState.bookList.size == 1 }
        assertFalse(onMain { model.uiState.isComplete })
        onMain { model.loadMore() }
        assertEquals(1, page.loadMoreCalls.get())
        session.emit(SearchResult.SingleBook("two"))
        session.emit(SearchResult.MultipleBook("one"))
        session.emit(SearchResult.End())
        awaitUi("all pages completed") { model.uiState.isComplete }
        assertEquals(listOf("one", "two"), onMain { model.uiState.bookList.map { it.first } })
        onMain { model.loadMore() }
        assertEquals(1, page.loadMoreCalls.get())
    }

    @Test
    fun ordinaryExpandedPageUsesTheSameCollectorForSingleAndMultipleRows() = runBlocking {
        val page = ControlledExpandedPage()
        val source = ControlledRelatedSource(legacyPages = mapOf("legacy-tags" to page))
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.init("legacy-tags") }
        awaitUi("legacy expanded page") { page.sessions.size == 1 }
        val session = page.sessions.single()
        session.emit(SearchResult.SingleBook("publisher-book"))
        session.emit(SearchResult.MultipleBook("tag-book"))
        session.emit(SearchResult.End())
        awaitUi("legacy expanded completion") { model.uiState.isComplete }
        assertEquals(listOf("publisher-book", "tag-book"), onMain { model.uiState.bookList.map { it.first } })
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun finiteSingleBookFlowCompletesWithoutRequiringAnEndEmission() = runBlocking {
        val source = ControlledRelatedSource(pageFactory = {
            object : ExploreExpandedPageDataSource {
                override val title = "one result"
                override val filters: List<Filter<*>> = emptyList()
                override fun loadMore() = error("Completed page requested another page")
                override fun getResultFlow(): Flow<SearchResult> = flowOf(SearchResult.SingleBook("only"))
            }
        })
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.initRelated(source.id.toString(), request) }
        awaitUi("finite flow completion") { model.uiState.isComplete }
        assertEquals(listOf("only"), onMain { model.uiState.bookList.map { it.first } })
        assertFalse(onMain { model.uiState.isLoading })
        onMain { model.loadMore() }
    }

    @Test
    fun errorCanRefreshToEmptyWithoutKeepingPreviousErrorOrRows() = runBlocking {
        val page = ControlledExpandedPage()
        val source = ControlledRelatedSource(pageFactory = { page })
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.initRelated(source.id.toString(), request) }
        awaitUi("error collection") { page.sessions.size == 1 }
        page.sessions.single().emit(SearchResult.MultipleBook("partial"))
        page.sessions.single().emit(SearchResult.Error("fixture offline"))
        awaitUi("error completion") { model.uiState.isComplete }
        assertEquals("fixture offline", onMain { model.uiState.errorMessage })
        assertEquals(listOf("partial"), onMain { model.uiState.bookList.map { it.first } })

        onMain { model.loadBookResult() }
        awaitUi("retry collection") { page.sessions.size == 2 }
        page.sessions.last().emit(SearchResult.Empty())
        awaitUi("empty completion") { model.uiState.isComplete }
        assertTrue(onMain { model.uiState.bookList.isEmpty() })
        assertFalse(onMain { model.uiState.isLoading })
        assertNull(onMain { model.uiState.errorMessage })
    }

    @Test
    fun restoredRouteForAnotherSourceFailsBeforeCreatingAnyPage() = runBlocking {
        val source = ControlledRelatedSource()
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.initRelated("old-source", request) }
        awaitUi("source validation error") { model.uiState.isComplete }
        assertEquals(fixture.context.getString(R.string.related_books_source_unavailable), onMain { model.uiState.errorMessage })
        assertTrue(source.requests.isEmpty())
        assertTrue(onMain { model.uiState.bookList.isEmpty() })
    }

    @Test
    fun sourceChangeBeforeLoadMoreCancelsTheOldPageAndDoesNotDispatchPagination() = runBlocking {
        val page = ControlledExpandedPage()
        val source = ControlledRelatedSource(pageFactory = { page })
        fixture.provider.update(source)
        val model = fixture.expanded()
        onMain { model.initRelated(source.id.toString(), request) }
        awaitUi("original source collection") { page.sessions.size == 1 }
        page.sessions.single().emit(SearchResult.SingleBook("old-source-row"))
        awaitUi("old source row") { model.uiState.bookList.size == 1 }
        fixture.provider.update(ControlledRelatedSource(id = Identifier("author_fixture", "replacement")))
        onMain { model.loadMore() }
        awaitUi("old source cancellation") { page.sessions.single().cancelled.get() }
        assertEquals(0, page.loadMoreCalls.get())
        assertTrue(onMain { model.uiState.isComplete })
        assertTrue(onMain { model.uiState.bookList.isEmpty() })
        assertEquals(fixture.context.getString(R.string.related_books_source_unavailable), onMain { model.uiState.errorMessage })
    }

    @Test
    fun sourceChangeRejectsLateRowsAndEndFromOldCollectorAndClearsItsCards() = runBlocking {
        for (lateResult in listOf(SearchResult.SingleBook("late-old-source-row"), SearchResult.End())) {
            val page = ControlledExpandedPage()
            val source = ControlledRelatedSource(pageFactory = { page })
            fixture.provider.update(source)
            val model = fixture.expanded()
            onMain { model.initRelated(source.id.toString(), request) }
            awaitUi("original source collection before replacement") { page.sessions.size == 1 }
            val session = page.sessions.single()
            session.emit(SearchResult.SingleBook("existing-old-source-row"))
            awaitUi("existing card before source replacement") {
                model.uiState.bookList.map { it.first } == listOf("existing-old-source-row")
            }

            val replacement = ControlledRelatedSource(id = Identifier("author_fixture", "replacement"))
            fixture.provider.update(replacement)
            session.emit(lateResult)
            awaitUi("source validation of the late result") { model.uiState.errorMessage != null }
            awaitUi("old collector stopping after source replacement") { session.cancelled.get() }

            assertEquals(fixture.context.getString(R.string.related_books_source_unavailable), onMain { model.uiState.errorMessage })
            assertTrue(onMain { model.uiState.bookList.isEmpty() })
            assertTrue(onMain { model.uiState.isComplete })
            assertFalse(onMain { model.uiState.isLoading })
            assertTrue(replacement.requests.isEmpty())
        }
    }
}
