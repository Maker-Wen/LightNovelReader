package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.ControlledRelatedSource
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.RelatedBooksTestFixture
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.awaitUi
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.onMain
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelatedBooksDetailTest {
    private lateinit var fixture: RelatedBooksTestFixture

    @Before
    fun setUp() { fixture = RelatedBooksTestFixture() }

    @After
    fun tearDown() { fixture.close() }

    @Test
    fun viewModelDisplaysProcessedAuthorAndSearchesTheOriginalAuthor() = runBlocking {
        val rawAuthor = "  川原 礫  "
        val source = ControlledRelatedSource(author = rawAuthor)
        fixture.provider.update(source)
        fixture.text.registerProcessors(Identifier("author_fixture", "transform"), object : TextProcessor {
            override val enabled = true
            override fun processText(text: String) = text.replace("礫", "砾")
            override fun processBookInformation(bookInformation: BookInformation) = bookInformation.copy(
                id = "display-${bookInformation.id}",
                author = "[display] ${processText(bookInformation.author)}",
            )
        })
        val model = fixture.detail()
        onMain { model.init("origin-book") }
        awaitUi("processed detail with raw author request") {
            model.uiState.bookInformation?.get()?.author == "[display]   川原 砾  " && model.uiState.authorRequest != null
        }
        assertEquals("display-origin-book", onMain { model.uiState.bookInformation?.get()?.id })
        assertEquals(source.id.toString(), onMain { model.uiState.sourceId })
        assertEquals(RelatedBooksRequest("origin-book", RelatedBookKind.AUTHOR, "川原 礫"), onMain { model.uiState.authorRequest })
        assertEquals(rawAuthor, fixture.database.bookInformationDao().get("origin-book")?.author)

        // A non-idempotent processor catches accidental double conversion in
        // either consumer. The public flow emits cached then remote information.
        val publicResults = fixture.books.getBookInformationFlow("origin-book").toList()
        assertEquals(listOf("[display]   川原 砾  ", "[display]   川原 砾  "), publicResults.map { it.get()?.author })
        assertEquals(listOf("display-origin-book", "display-origin-book"), publicResults.map { it.get()?.id })
        assertEquals(rawAuthor, fixture.database.bookInformationDao().get("origin-book")?.author)
    }

    @Test
    fun authorRemainsVisibleWhenSourceDoesNotSupportAuthorLists() = runBlocking {
        val source = ControlledRelatedSource(author = "visible author", supportedRelatedBookKinds = setOf(RelatedBookKind.TAG))
        fixture.provider.update(source)
        val model = fixture.detail()
        onMain { model.init("origin-book") }
        awaitUi("unsupported author detail") { model.uiState.bookInformation?.get()?.author == "visible author" }
        assertNull(onMain { model.uiState.authorRequest })
    }

    @Test
    fun requestCapabilityRequiresNonblankSourceAndAuthorWithoutTransformingQuery() {
        val supported = setOf(RelatedBookKind.AUTHOR)
        assertNotNull(authorRequestForDetail("site:one", "42", " 川原 礫 ", supported))
        assertEquals("川原 礫", authorRequestForDetail("site:one", "42", " 川原 礫 ", supported)?.value)
        assertNull(authorRequestForDetail(null, "42", "川原 礫", supported))
        assertNull(authorRequestForDetail(" ", "42", "川原 礫", supported))
        assertNull(authorRequestForDetail("site:one", "42", " \n ", supported))
        assertNull(authorRequestForDetail("site:one", "42", "川原 礫", setOf(RelatedBookKind.TAG)))
    }
}
