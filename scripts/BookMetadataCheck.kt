import android.util.Log
import androidx.work.WorkManager
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.Metadata
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.ControlledSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import java.time.LocalDateTime

private enum class Kind { INFORMATION, VOLUMES }
private val updatedAt = LocalDateTime.of(2026, 9, 30, 1, 0)
private val offline = WebRequestError("Offline", "Controlled request failure")

private fun information(title: String) = BookInformation(
    id = "3492", title = title, author = "Author", description = "Description",
    publishingHouse = "Publisher", wordCount = WordCount(1000),
    lastUpdated = updatedAt, isComplete = false
)

private fun volumes(title: String) = BookVolumes("3492", listOf(
    Volume("volume", title, listOf(ChapterInformation("chapter", "Chapter")))
))

private fun processed(value: Any): Any = when (value) {
    is BookInformation -> value.copy(title = "processed:" + value.title)
    is BookVolumes -> value.copy(volumes = value.volumes.map {
        it.copy(volumeTitle = "processed:" + it.volumeTitle)
    })
    else -> error("Unexpected metadata: $value")
}

private class Fixture(val kind: Kind) {
    val events = mutableListOf<String>()
    val local = LocalBookDataSource(events)
    val web = ControlledSource(events)
    val bookshelf = BookshelfRepository(events)
    val text = TextProcessingRepository(events)
    val repository = BookRepository(WebBookDataSourceProvider(web), local, bookshelf, text, WorkManager())
    val cached: Any = if (kind == Kind.INFORMATION) information("cached") else volumes("cached")
    val fresh: Any = if (kind == Kind.INFORMATION) information("fresh") else volumes("fresh")
    var reply: suspend () -> Result<Any, WebRequestError> = { Err(offline) }

    init {
        web.information = { reply().map { it as BookInformation } }
        web.volumes = { reply().map { it as BookVolumes } }
    }

    fun cache(value: Any? = cached) {
        if (kind == Kind.INFORMATION) local.information = value as BookInformation?
        else local.volumes = value as BookVolumes?
    }

    fun flow(): Flow<Result<Any, WebRequestError>> = when (kind) {
        Kind.INFORMATION -> repository.getBookInformationFlow("3492", WebDataSourcePriority.High)
        Kind.VOLUMES -> repository.getBookVolumesFlow("3492", WebDataSourcePriority.High)
    }

    fun checkRequested() {
        check(web.requests == listOf("3492" to WebDataSourcePriority.High)) { "Refresh was skipped or priority changed: ${web.requests}" }
    }
}

fun main() = runBlocking {
    val failures = mutableListOf<String>()
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) {
        BuildConfig.BENCHMARK = false
        Log.errors.clear()
        try {
            withTimeout(5_000) { block() }
            passed++
            println("PASS $name")
        } catch (failure: Throwable) {
            failures += "$name: $failure"
            println("FAIL ${failures.last()}")
        } finally {
            BuildConfig.BENCHMARK = false
        }
    }

    for (kind in Kind.entries) {
        test("$kind cached survives remote error once") {
            val f = Fixture(kind).apply { cache() }
            val values = f.flow().toList()
            f.checkRequested()
            check(values == listOf(Ok(processed(f.cached)))) { "Cached success was replaced: $values" }
            check(f.local.saved.isEmpty())
            check(f.text.inputs == listOf(f.cached))
            check(Log.errors.single().contains(offline.message))
        }
        test("$kind local first, remote refresh persists raw data before processing") {
            val f = Fixture(kind).apply {
                cache()
                bookshelf.metadata = Metadata(updatedAt.minusDays(1), listOf(10, 20))
            }
            val release = CompletableDeferred<Unit>()
            f.reply = { release.await(); Ok(f.fresh) }
            val values = mutableListOf<Result<Any, WebRequestError>>()
            coroutineScope {
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { f.flow().toList(values) }
                check(values == listOf(Ok(processed(f.cached)))) { "Cache must precede remote completion: $values" }
                f.checkRequested()
                check(f.local.saved.isEmpty())
                release.complete(Unit)
                collector.join()
            }
            check(values == listOf(Ok(processed(f.cached)), Ok(processed(f.fresh))))
            check(f.local.saved == listOf(f.fresh)) { "Persist raw remote data, not processed output" }
            check(f.text.inputs == listOf(f.cached, f.fresh))
            val expectedEvents = if (kind == Kind.INFORMATION) listOf(
                "process", "request", "persist", "timestamp", "mark-updated", "timestamp", "mark-updated", "process"
            ) else listOf("process", "request", "persist", "process")
            check(f.events == expectedEvents) { "Side-effect order changed: ${f.events}" }
            if (kind == Kind.INFORMATION) {
                check(f.bookshelf.updated == listOf(10 to "3492", 20 to "3492"))
                check(f.bookshelf.timestamps == List(2) { "3492" to updatedAt })
            }
        }
        test("$kind null cache keeps remote error visible") {
            val f = Fixture(kind)
            check(f.flow().toList() == listOf(Err(offline)))
            f.checkRequested()
            check(f.text.inputs.isEmpty() && f.local.saved.isEmpty())
            check(Log.errors.single().contains(offline.message))
        }
        test("$kind cache miss remote success persists and processes") {
            val f = Fixture(kind).apply { reply = { Ok(fresh) } }
            check(f.flow().toList() == listOf(Ok(processed(f.fresh))))
            f.checkRequested()
            check(f.local.saved == listOf(f.fresh))
            check(f.text.inputs == listOf(f.fresh))
            check(f.events == listOf("request", "persist", "process"))
        }
        for (failure in listOf(CancellationException("Cancelled refresh"), IllegalStateException("Thrown source failure"))) {
            test("$kind propagates ${failure.javaClass.simpleName}") {
                val f = Fixture(kind).apply { cache(); reply = { throw failure } }
                val values = mutableListOf<Result<Any, WebRequestError>>()
                val thrown = try { f.flow().toList(values); null } catch (caught: Throwable) { caught }
                check(thrown === failure) { "Thrown failure must propagate unchanged: $thrown" }
                check(values == listOf(Ok(processed(f.cached))))
                f.checkRequested()
                check(Log.errors.isEmpty() && f.local.saved.isEmpty())
            }
        }
        test("$kind benchmark cache still skips remote") {
            val f = Fixture(kind).apply { cache() }
            BuildConfig.BENCHMARK = true
            check(f.flow().toList() == listOf(Ok(processed(f.cached))))
            check(f.web.requests.isEmpty() && Log.errors.isEmpty())
        }
        test("$kind benchmark null cache still requests remote") {
            val f = Fixture(kind)
            BuildConfig.BENCHMARK = true
            check(f.flow().toList() == listOf(Err(offline)))
            f.checkRequested()
        }
    }
    test("VOLUMES actual DAO empty cache must not suppress remote error") {
        val empty = BookVolumes("3492", emptyList())
        val f = Fixture(Kind.VOLUMES).apply { cache(empty) }
        // The DAO returns an empty object for a miss. Preserve its existing initial emission.
        check(f.flow().toList() == listOf(Ok(empty), Err(offline)))
        f.checkRequested()
        check(Log.errors.single().contains(offline.message))
    }
    test("VOLUMES known volume without chapters remains usable cache") {
        val emptyVolume = BookVolumes("3492", listOf(Volume("known", "Known volume", emptyList())))
        val f = Fixture(Kind.VOLUMES).apply { cache(emptyVolume) }
        check(f.flow().toList() == listOf(Ok(processed(emptyVolume))))
        f.checkRequested()
    }
    test("VOLUMES benchmark empty object retains existing shortcut") {
        val empty = BookVolumes("3492", emptyList())
        val f = Fixture(Kind.VOLUMES).apply { cache(empty) }
        BuildConfig.BENCHMARK = true
        check(f.flow().toList() == listOf(Ok(empty)))
        check(f.web.requests.isEmpty())
    }
    println("Book metadata checks: $passed passed, ${failures.size} failed")
    check(failures.isEmpty()) { failures.joinToString("\n") }
}
