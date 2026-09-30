package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A finite local-then-remote source; releasing remote is controlled by each check. */
class LoadingScenario(val local: Result<ChapterContent, WebRequestError>, holdLocal: Boolean = false) {
    val localReady = CompletableDeferred<Unit>().also { if (!holdLocal) it.complete(Unit) }
    val remote = CompletableDeferred<Result<ChapterContent, WebRequestError>>()
    val subscriptions = AtomicInteger()
    val active = AtomicInteger()
    val emitted = AtomicInteger()
    val completed = AtomicInteger()
}

/** Replaces only the data boundary when compiling the real reader engines. */
class BookRepository {
    val scenarios = ConcurrentHashMap<String, LoadingScenario>()
    var readingDataReady: CompletableDeferred<Unit>? = null
    var readingData = UserReadingData()
    val readingDataRequests = AtomicInteger()

    fun getChapterContentFlow(
        id: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    ) = flow {
        val scenario = scenarios[id] ?: return@flow
        scenario.subscriptions.incrementAndGet()
        scenario.active.incrementAndGet()
        try {
            scenario.localReady.await()
            emit(scenario.local)
            scenario.emitted.incrementAndGet()
            emit(scenario.remote.await())
            scenario.emitted.incrementAndGet()
            scenario.completed.incrementAndGet()
        } finally {
            scenario.active.decrementAndGet()
        }
    }

    suspend fun getUserReadingData(bookId: String): UserReadingData {
        readingDataRequests.incrementAndGet()
        readingDataReady?.await()
        return readingData
    }
}
