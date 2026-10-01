package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.format.FormatRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjectorProvider
import indi.dmzz_yyhyy.lightnovelreader.data.text.SimplifiedTraditionalProcessor
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.EmptyWebDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.MutableWebDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.DetailViewModel
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.RelatedBooksDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real repositories and ViewModels, with only network/list results controlled by each test. */
internal class RelatedBooksTestFixture : AutoCloseable {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    val database = Room.inMemoryDatabaseBuilder(context, LightNovelReaderDatabase::class.java)
        .allowMainThreadQueries().build()
    val provider = MutableWebDataSourceProvider()
    val workManager: WorkManager = WorkManager.getInstance(context)
    val bookshelf = BookshelfRepository(database.bookshelfDao(), workManager)
    val text = TextProcessingRepository(
        SimplifiedTraditionalProcessor(UserDataRepository(database.userDataDao())),
        FormatRepository(database.formattingRuleDao()),
        ContentComponentRepository(PluginInjectorProvider()),
    )
    val books = BookRepository(
        provider,
        LocalBookDataSource(database.bookInformationDao(), database.bookVolumesDao(), database.chapterContentDao(), database.userReadingDataDao()),
        bookshelf,
        text,
        workManager,
    )
    private val models = ViewModelStore()
    private var nextModel = 0

    fun expanded(): ExpandedPageViewModel = onMain {
        keep(ExpandedPageViewModel(context, ExploreRepository(provider), bookshelf, text, books))
    }

    fun detail(): DetailViewModel = onMain {
        keep(DetailViewModel(books, text, bookshelf, DownloadProgressRepository(database.userDataDao(), books), workManager))
    }

    private fun <T : ViewModel> keep(model: T): T {
        models.put("fixture-${nextModel++}", model)
        return model
    }

    override fun close() {
        onMain { models.clear() }
        database.close()
    }
}

internal class ControlledRelatedSource(
    override val id: Identifier = Identifier("author_fixture", "active"),
    val author: String = "原始作者",
    override val supportedRelatedBookKinds: Set<RelatedBookKind> = setOf(RelatedBookKind.AUTHOR),
    private val pageFactory: () -> ExploreExpandedPageDataSource = { ControlledExpandedPage() },
    val legacyPages: Map<String, ExploreExpandedPageDataSource> = emptyMap(),
) : WebBookDataSource by EmptyWebDataSource, RelatedBooksDataSource {
    val requests = CopyOnWriteArrayList<RelatedBooksRequest>()
    val createdPages = CopyOnWriteArrayList<ExploreExpandedPageDataSource>()
    override val explorePageProvider: ExplorePageProvider = object : ExplorePageProvider.DefaultExplorePageProvider {
        override val explorePageIdList: List<String> = emptyList()
        override val exploreTapPageDataSourceMap: Map<String, ExploreTapPageDataSource> = emptyMap()
        override val exploreExpandedPageDataSourceMap: Map<String, ExploreExpandedPageDataSource> = legacyPages
    }

    override fun createRelatedBooksPage(request: RelatedBooksRequest): ExploreExpandedPageDataSource {
        requests.add(request)
        return pageFactory().also(createdPages::add)
    }

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> = Ok(
        BookInformation(id = id, title = "Fixture book $id", author = author, description = "", publishingHouse = "",
            wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 10, 1, 12, 0), isComplete = true),
    )

    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> = Ok(BookVolumes(id, emptyList()))
}

internal class ControlledExpandedPage(
    private val cancellationCleanupGate: CompletableDeferred<Unit>? = null,
) : ExploreExpandedPageDataSource {
    override val title = "原始作者的书籍"
    override val filters: List<Filter<*>> = emptyList()
    val sessions = CopyOnWriteArrayList<Session>()
    val loadMoreCalls = AtomicInteger()

    class Session {
        val results = Channel<SearchResult>(Channel.UNLIMITED)
        val cleanupStarted = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        fun emit(result: SearchResult) { check(results.trySend(result).isSuccess) }
        fun finish() { results.close() }
    }

    override fun getResultFlow(): Flow<SearchResult> = flow {
        val session = Session().also(sessions::add)
        try {
            for (result in session.results) emit(result)
        } finally {
            session.cleanupStarted.set(true)
            withContext(NonCancellable) { cancellationCleanupGate?.await() }
            session.cancelled.set(true)
        }
    }

    override fun loadMore() { loadMoreCalls.incrementAndGet() }
}

internal fun <T> onMain(block: () -> T): T {
    val result = AtomicReference<T>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
    return result.get()
}

internal suspend fun awaitUi(description: String, predicate: () -> Boolean) {
    try {
        withTimeout(5_000) {
            while (!onMain(predicate)) delay(20)
        }
    } catch (error: Exception) {
        throw AssertionError("Timed out waiting for $description", error)
    }
}
