package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandedPageResultTest {
    @Test
    fun singleAndMultipleResultsStayInListAndDeduplicateUntilExplicitEnd() = runBlocking {
        val page = MutableExpandedPageUiState()
        flowOf(
            SearchResult.SingleBook("first"),
            SearchResult.SingleBook("second"),
            SearchResult.MultipleBook("first"),
            SearchResult.MultipleBook("third"),
        ).takeWhile {
            page.acceptResult(it) { id ->
                check(page.bookList.none { book -> book.first == id }) { "Duplicate fetched twice" }
                emptyFlow()
            }
        }.toList()

        assertEquals(listOf("first", "second", "third"), page.bookList.map { it.first })
        assertFalse(page.isLoading)
        assertFalse(page.isComplete)
        assertFalse(page.acceptResult(SearchResult.End()) { error("Terminal result fetched a book") })
        assertTrue(page.isComplete)
        assertNull(page.errorMessage)
    }

    @Test
    fun emptyAndErrorEndLoadingWhileKeepingAlreadyLoadedBooks() {
        val empty = MutableExpandedPageUiState()
        assertFalse(empty.acceptResult(SearchResult.Empty()) { error("Empty result fetched a book") })
        assertFalse(empty.isLoading)
        assertTrue(empty.isComplete)
        assertTrue(empty.bookList.isEmpty())
        assertNull(empty.errorMessage)

        val failed = MutableExpandedPageUiState()
        assertTrue(failed.acceptResult(SearchResult.MultipleBook("cached-row")) { emptyFlow() })
        assertFalse(failed.acceptResult(SearchResult.Error("offline")) { error("Error fetched a book") })
        assertEquals("offline", failed.errorMessage)
        assertEquals(listOf("cached-row"), failed.bookList.map { it.first })
        assertFalse(failed.isLoading)
        assertTrue(failed.isComplete)
    }
}
