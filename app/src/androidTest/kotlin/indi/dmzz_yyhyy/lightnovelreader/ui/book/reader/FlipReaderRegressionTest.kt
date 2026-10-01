package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ParagraphComponentRender
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.EmptyWebDataSource
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPaginationLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.RelatedBooksTestFixture
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.onMain
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.ComponentRender
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.text.ParagraphNode
import io.nightfish.lightnovelreader.api.text.TextNode
import io.nightfish.lightnovelreader.api.ui.LocalComponentRender
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real reader component, VM, pointer routing and frame-clock animation; only source/render data is controlled. */
@RunWith(AndroidJUnit4::class)
class FlipReaderRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: RelatedBooksTestFixture
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingState
    private lateinit var model: FlipPageContentViewModel
    private val reports = mutableListOf<Pair<Long, ReaderPosition>>()
    private var retries = 0
    private var menuToggles = 0
    private val selectionMenu = RecordingSelectionMenu()
    private var measuredLayout: ReaderPaginationLayout? = null
    private val renderedSizes = mutableMapOf<String, IntSize>()

    @Before
    fun setUp() {
        fixture = RelatedBooksTestFixture()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        settings = onMain { SettingState(UserDataRepository(fixture.database.userDataDao()), scope) }
        runBlocking {
            settings.isUsingFlipPageUserData.set(true)
            settings.isUsingClickFlipPageUserData.set(true)
        }
        compose.waitUntil(5_000) { onMain { settings.isUsingFlipPage && settings.isUsingClickFlipPage } }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        if (::model.isInitialized) onMain { model.dispose() }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        fixture.close()
    }

    @Test
    fun descendantRetryClickDoesNotPageOrToggleMenu() {
        showReader()
        awaitPositioned()
        compose.onNodeWithTag("retry-0").performTouchInput {
            down(center)
            moveTo(center + Offset(3f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, menuToggles)
            assertEquals(0, model.uiState.pagerState.currentPage)
            assertEquals(0f, model.uiState.pagerState.pageOffset, 0f)
        }
        // A plain central tap still toggles the menu once; a swipe still advances one page.
        compose.onNodeWithTag("reader-surface").performTouchInput { click(Offset(width / 2f, height / 8f)) }
        compose.runOnIdle { assertEquals(1, menuToggles) }
        compose.onNodeWithTag("reader-surface").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { onMain { model.uiState.pagerState.currentPage == 1 && !model.uiState.pagerState.isAnimating } }
        compose.runOnIdle { assertEquals(1, menuToggles) }
    }

    @Test
    fun tapStartedWhileLoadingSurvivesContentReplacementAndTogglesOnce() {
        val bodyReady = CompletableDeferred<Unit>()
        showReader(bodyReady)
        compose.onNodeWithTag("reader-surface").performTouchInput { down(center) }
        bodyReady.complete(Unit)
        awaitPositioned()
        compose.onNodeWithTag("reader-surface").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, menuToggles)
            assertEquals(0, model.uiState.pagerState.currentPage)
        }
    }

    @Test
    fun longPressOnSelectableTextDoesNotPageOrToggleMenu() {
        showReader()
        awaitPositioned()
        compose.onNodeWithText("Page 0", useUnmergedTree = true).performTouchInput { longClick(center) }
        compose.runOnIdle {
            assertEquals(0, menuToggles)
            assertEquals(0, model.uiState.pagerState.currentPage)
            assertTrue("The real SelectionContainer must show its text context menu", selectionMenu.isShowing)
            assertTrue("The real selection must offer copying its text",
                selectionMenu.data?.components?.any { it.key == TextContextMenuKeys.CopyKey } == true)
        }
    }

    @Test
    fun seekAndReturnRetireRunningAnimationAndQueuedPageRequest() {
        showReader()
        awaitPositioned()
        val origin = compose.runOnIdle { requireNotNull(model.capturePosition()) }
        compose.mainClock.autoAdvance = false

        startTurnAndQueueAnother()
        compose.runOnIdle {
            model.changeChapter("chapter", ChapterPosition.Relative(.5f), 2)
            assertEquals(2, model.uiState.pagerState.currentPage)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertSettledAt(2)
            assertEquals(2L, reports.last().first)
            assertEquals(.5f, reports.last().second.fraction, 0f)
        }

        startTurnAndQueueAnother()
        compose.runOnIdle {
            model.changeChapter("chapter", ChapterPosition.Exact(origin), 3)
            assertEquals(0, model.uiState.pagerState.currentPage)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertSettledAt(0)
            assertEquals(3L, reports.last().first)
            assertEquals(origin.anchor, reports.last().second.anchor)
            assertEquals(origin.fraction, reports.last().second.fraction, 0f)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun startTurnAndQueueAnother() {
        compose.onNodeWithTag("reader-surface").performTouchInput { swipeLeft(durationMillis = 64) }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { assertTrue("The real page-turn animation must be suspended", model.uiState.pagerState.isAnimating) }
        // The collector is busy animating, so this accepted tap remains queued under the old generation.
        compose.onNodeWithTag("reader-surface").performTouchInput { click(Offset(width * .9f, height / 8f)) }
    }

    private fun assertSettledAt(page: Int) {
        assertEquals(page, model.uiState.pagerState.currentPage)
        assertEquals(0f, model.uiState.pagerState.pageOffset, 0f)
        assertFalse(model.uiState.pagerState.isAnimating)
        assertFalse(model.uiState.isPositioning)
    }

    private fun awaitPositioned() {
        try {
            compose.waitUntil(5_000) {
                onMain { !model.uiState.isPositioning && model.uiState.pagerState.pageCount == 5 && model.uiState.pagerState.endReached }
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(onMain {
                val state = model.uiState
                "Reader did not finish five-page pagination: positioning=${state.isPositioning}, " +
                    "pages=${state.pagerState.pageCount}, end=${state.pagerState.endReached}, " +
                    "chapter=${state.readingChapterId}, result=${state.readingChapterContent}, " +
                    "contentSize=${state.readingChapterContent?.get()?.content?.size}, " +
                    "viewport=${state.pagerState.viewportWidth}, layout=$measuredLayout, renders=$renderedSizes"
            }, error)
        }
        compose.waitForIdle()
    }

    private fun showReader(bodyReady: CompletableDeferred<Unit>? = null) {
        val data = (0..4).map { ParagraphComponentData(ParagraphNode(listOf(TextNode(it.toString())))) }
        val chapter = ChapterContent("chapter", "Fixture chapter", buildJsonObject {
            put("components", buildJsonArray {
                data.forEach { component -> add(buildJsonObject {
                    put("id", component.id.toString())
                    put("data", component.toJsonElement())
                }) }
            })
        })
        fixture.provider.update(object : WebBookDataSource by EmptyWebDataSource {
            override val id = Identifier("flip_regression", "source")
            override suspend fun getChapterContent(chapterId: String, bookId: String): Result<ChapterContent, WebRequestError> {
                bodyReady?.await()
                return Ok(chapter)
            }
        })
        val components = fixture.text.contentComponentRepository.apply {
            // Register the real paragraph serializer/render directly through the host builder.
            // This isolated fixture has no application PluginInjector or image source injection.
            registrar.id(ParagraphComponentData.id)
                .apply { componentRender = { ParagraphComponentRender().erase() } }
                .data(ParagraphComponentData::class)
                .serializer(ParagraphComponentData.jsonSerializer)
                .register()
        }
        model = onMain {
            FlipPageContentViewModel(fixture.books, scope, components,
                { id, position, _, _ -> reports += id to position }, {}, { _, _, _, _ -> },
                { measuredLayout = it }, { _, _ -> 0f to null }
            ).also {
                it.changeBookId("book")
                it.changeChapter("chapter", ChapterPosition.Relative(0f), 1)
            }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides selectionMenu,
                    LocalComponentRender provides object : ComponentRender {
                    @Composable
                    override fun Component(modifier: Modifier, componentData: AbstractContentComponentData) {
                        val index = (componentData as ParagraphComponentData).paragraph.textNodes.first().text
                        // One full-height component per measured page makes the 5-page seek scale deterministic.
                        Box(modifier.fillMaxSize().onSizeChanged { renderedSizes[index] = it }.testTag("page-$index")) {
                            Text("Page $index", Modifier.align(Alignment.TopCenter))
                            Button({ retries++ }, Modifier.align(Alignment.Center).testTag("retry-$index")) { Text("重试") }
                        }
                    }
                }) {
                    FlipPageContentComponent(Modifier.testTag("reader-surface"), model.uiState, settings,
                        PaddingValues(), { menuToggles++ })
                }
            }
        }
    }

    private class RecordingSelectionMenu : TextContextMenuProvider {
        var isShowing = false
            private set
        var data: TextContextMenuData? = null
            private set

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            data = dataProvider.data()
            isShowing = true
            try { awaitCancellation() }
            finally { isShowing = false }
        }
    }
}
