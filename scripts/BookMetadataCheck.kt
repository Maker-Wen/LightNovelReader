import android.util.Log
import androidx.work.WorkManager
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.Metadata
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.ControlledSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.ControlledRelatedSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.ControlledWebProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.EmptyWebDataSource
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
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
    val provider = ControlledWebProvider(web)
    val bookshelf = BookshelfRepository(events)
    val text = TextProcessingRepository(events)
    val repository = BookRepository(provider, local, bookshelf, text, WorkManager())
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
    test("INFORMATION source projection keeps raw identity and author while displaying once") {
        val f = Fixture(Kind.INFORMATION)
        val cached = information("cached").copy(author = "  川原 礫  ")
        val fresh = information("fresh").copy(author = "  鎌池 和馬  ")
        f.cache(cached)
        f.reply = { Ok(fresh) }
        f.text.informationTransform = {
            it.copy(id = "display:${it.id}", title = "display:${it.title}", author = "display:${it.author}")
        }
        val values = f.repository.getSourceBookInformationFlow("3492", WebDataSourcePriority.High)
            .toList().map { checkNotNull(it.get()) }
        check(values.map { it.rawBookId } == listOf("3492", "3492"))
        check(values.map { it.rawAuthor } == listOf(cached.author, fresh.author))
        check(values.map { it.sourceId } == List(2) { f.web.id.toString() })
        check(values.all { it.supportedRelatedBookKinds == setOf(RelatedBookKind.AUTHOR) })
        check(values.map { it.information } == listOf(cached, fresh).map(f.text.informationTransform))
        check(f.local.saved == listOf(fresh) && f.local.information == fresh)
        check(f.text.inputs == listOf(cached, fresh))
        f.checkRequested()

        val publicValues = f.repository.getBookInformationFlow("3492").toList()
        check(publicValues == List(2) { Ok(f.text.informationTransform(fresh)) }) {
            "Public flow must unwrap the projection without applying it twice: $publicValues"
        }
        check(f.local.saved == List(2) { fresh })
        check(f.text.inputs == listOf(cached, fresh, fresh, fresh))
    }
    test("INFORMATION refresh retains originating source and capability snapshot") {
        val f = Fixture(Kind.INFORMATION).apply { cache() }
        val origin = f.web.origin as ControlledRelatedSource
        val supported = mutableSetOf(RelatedBookKind.AUTHOR)
        origin.supportedRelatedBookKinds = supported
        val release = CompletableDeferred<Unit>()
        f.reply = { release.await(); Ok(f.fresh) }
        val originalId = f.web.id.toString()
        val replacement = ControlledSource(f.events, ControlledRelatedSource(
            Identifier("metadata_check", "replacement"), setOf(RelatedBookKind.TAG)
        ))
        val values = coroutineScope {
            val collector = async(start = CoroutineStart.UNDISPATCHED) {
                f.repository.getSourceBookInformationFlow("3492", WebDataSourcePriority.High).toList()
            }
            f.checkRequested()
            supported.clear()
            f.provider.current = replacement
            check(f.repository.sourceId == replacement.id.toString())
            check(f.repository.supportedRelatedBookKinds == setOf(RelatedBookKind.TAG))
            release.complete(Unit)
            collector.await().map { checkNotNull(it.get()) }
        }
        check(values.map { it.sourceId } == List(2) { originalId })
        check(values.all { it.supportedRelatedBookKinds == setOf(RelatedBookKind.AUTHOR) })
        check(values.map { it.information } == listOf(processed(f.cached), processed(f.fresh)))
        check(replacement.requests.isEmpty()) { "An active flow refreshed from a replacement source" }
    }
    test("INFORMATION unavailable and empty sources cannot enable related queries") {
        for (useEmpty in listOf(false, true)) {
            val f = Fixture(Kind.INFORMATION).apply { cache() }
            if (useEmpty) f.web.origin = EmptyWebDataSource else f.provider.found = false
            val values = f.repository.getSourceBookInformationFlow("3492").toList()
                .map { checkNotNull(it.get()) }
            check(values.single().sourceId == null)
            check(values.single().supportedRelatedBookKinds.isEmpty())
            check(f.repository.sourceId == null && f.repository.supportedRelatedBookKinds.isEmpty())
            val request = RelatedBooksRequest("3492", RelatedBookKind.AUTHOR, "Author")
            check(runCatching { f.repository.createRelatedBooksPage(f.web.id.toString(), request) }
                .exceptionOrNull() is IllegalStateException)
            check(f.local.saved.isEmpty() && f.text.inputs == listOf(f.cached))
        }
    }
    test("RELATED pages validate current source and forward raw requests to independent sessions") {
        val f = Fixture(Kind.INFORMATION)
        val origin = f.web.origin as ControlledRelatedSource
        val id = f.repository.sourceId!!
        val request = RelatedBooksRequest("3492", RelatedBookKind.AUTHOR, "  川原 礫  ")
        check(runCatching { f.repository.createRelatedBooksPage("metadata_check:other", request) }
            .exceptionOrNull() is IllegalStateException)
        check(runCatching { f.repository.createRelatedBooksPage(id, request.copy(kind = RelatedBookKind.TAG)) }
            .exceptionOrNull() is IllegalArgumentException)
        check(runCatching { f.repository.createRelatedBooksPage(id, request.copy(value = " \n ")) }
            .exceptionOrNull() is IllegalArgumentException)
        check(origin.relatedRequests.isEmpty() && f.web.requests.isEmpty())
        val first = f.repository.createRelatedBooksPage(id, request)
        val second = f.repository.createRelatedBooksPage(id, request)
        check(first !== second && first.title == request.value && second.title == request.value)
        check(origin.relatedRequests == listOf(request, request))
        check(f.web.requests.isEmpty()) { "Creating an author page must not request metadata" }

        f.web.origin = object : WebBookDataSource by EmptyWebDataSource {
            override val id = Identifier("metadata_check", "legacy")
        }
        check(f.repository.sourceId == "metadata_check:legacy")
        check(f.repository.supportedRelatedBookKinds.isEmpty())
        check(runCatching { f.repository.createRelatedBooksPage(f.repository.sourceId!!, request) }
            .exceptionOrNull() is IllegalStateException)
        check(origin.relatedRequests == listOf(request, request))
    }
    println("Book metadata checks: $passed passed, ${failures.size} failed")
    check(failures.isEmpty()) { failures.joinToString("\n") }
}
