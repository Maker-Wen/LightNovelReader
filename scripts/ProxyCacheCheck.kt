import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyCachedWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.runBlocking

private data class Call(
    val operation: String,
    val book: String,
    val chapter: String?,
    val priority: WebDataSourcePriority
)

private class RecordingSource(cache: Cache? = Cache()) : ProxyWebBookDataSource {
    override val origin = WebBookDataSource("source-id", cache)
    override val proxiedWebBookDataSource get() = this
    val calls = mutableListOf<Call>()
    var fail = false
    val failure = WebRequestError("Unavailable", "Try again")

    private fun <T> respond(call: Call, result: () -> T): Result<T, WebRequestError> {
        calls += call
        return if (fail) Err(failure) else Ok(result())
    }

    override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) =
        respond(Call("information", id, null, priority)) { BookInformation(id, calls.size) }

    override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) =
        respond(Call("volumes", id, null, priority)) { BookVolumes(id, calls.size) }

    override suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) =
        respond(Call("chapter", bookId, chapterId, priority)) { ChapterContent(chapterId, bookId, calls.size) }
}

private suspend fun repeatedRequestsHitCache() {
    val source = RecordingSource()
    val proxy = ProxyCachedWebBookDataSource(source)
    val information = proxy.getBookInformation("book", WebDataSourcePriority.High)
    val volumes = proxy.getBookVolumes("book", WebDataSourcePriority.Default)
    val chapter = proxy.getChapterContent("chapter", "book", WebDataSourcePriority.Low)
    check(source.calls.size == 3)
    repeat(3) {
        check(proxy.getBookInformation("book", WebDataSourcePriority.Low) == information)
        check(proxy.getBookVolumes("book", WebDataSourcePriority.High) == volumes)
        check(proxy.getChapterContent("chapter", "book", WebDataSourcePriority.Default) == chapter)
    }
    check(source.calls.size == 3) { "Cached reads must not invoke the source again" }
    check(source.calls.map { it.priority } == WebDataSourcePriority.entries)
}

private suspend fun idsAndResponseTypesAreIsolated() {
    val source = RecordingSource(Cache(maxCountEachType = 20))
    val proxy = ProxyCachedWebBookDataSource(source)
    val priority = WebDataSourcePriority.Default
    val firstBook = proxy.getBookInformation("book-a", priority)
    val secondBook = proxy.getBookInformation("book-b", priority)
    val firstVolumes = proxy.getBookVolumes("book-a", priority)
    val secondVolumes = proxy.getBookVolumes("book-b", priority)
    check(firstBook != secondBook)
    check(firstVolumes != secondVolumes)
    check(proxy.getBookInformation("book-a", priority) == firstBook)
    check(proxy.getBookVolumes("book-a", priority) == firstVolumes)
    check(proxy.getBookInformation("book-b", priority) == secondBook)
    check(proxy.getBookVolumes("book-b", priority) == secondVolumes)

    // These pairs used to share the same concatenated chapterId + bookId key.
    val pairs = listOf("ab" to "c", "a" to "bc", "chapter" to "book-a", "chapter" to "book-b",
        "other" to "book-a", "" to "ab", "ab" to "", "1:a" to "b", "1" to ":ab")
    val results = pairs.map { (chapter, book) -> proxy.getChapterContent(chapter, book, priority) }
    check(results.toSet().size == pairs.size)
    pairs.forEachIndexed { index, (chapter, book) ->
        check(proxy.getChapterContent(chapter, book, priority) == results[index])
    }
    check(source.calls.size == 4 + pairs.size) { "Book and chapter keys must remain isolated" }
}

private suspend fun expiredEntriesAreRefetched() {
    val cache = Cache(timeout = 30_000)
    val source = RecordingSource(cache)
    val proxy = ProxyCachedWebBookDataSource(source)
    val priority = WebDataSourcePriority.Default
    val first = proxy.getChapterContent("chapter", "book", priority)
    check(proxy.getChapterContent("chapter", "book", priority) == first)
    val staleTimestamp = System.currentTimeMillis() - cache.timeout - 1
    cache.cacheMap.values.forEach { entries ->
        entries.replaceAll { _, value -> value.copy(time = staleTimestamp) }
    }
    val refreshed = proxy.getChapterContent("chapter", "book", priority)
    check(first != refreshed)
    check(proxy.getChapterContent("chapter", "book", priority) == refreshed)
    check(source.calls.size == 2) { "Expired entries should refetch once then cache the new result" }
}

private suspend fun failuresAreNotCached() {
    val source = RecordingSource()
    val proxy = ProxyCachedWebBookDataSource(source)
    val priority = WebDataSourcePriority.High
    source.fail = true
    repeat(2) {
        check(proxy.getBookInformation("book", priority) == Err(source.failure))
        check(proxy.getBookVolumes("book", priority) == Err(source.failure))
        check(proxy.getChapterContent("chapter", "book", priority) == Err(source.failure))
    }
    check(source.calls.size == 6)
    check(source.origin.cache!!.cacheMap.isEmpty())
    source.fail = false
    val information = proxy.getBookInformation("book", priority)
    val volumes = proxy.getBookVolumes("book", priority)
    val chapter = proxy.getChapterContent("chapter", "book", priority)
    check(information.isOk && volumes.isOk && chapter.isOk)
    check(proxy.getBookInformation("book", priority) == information)
    check(proxy.getBookVolumes("book", priority) == volumes)
    check(proxy.getChapterContent("chapter", "book", priority) == chapter)
    check(source.calls.size == 9) { "A retry must be able to replace an error with cached success" }
}

private suspend fun disabledCacheAndPrioritiesPassThrough() {
    val source = RecordingSource(null)
    val proxy = ProxyCachedWebBookDataSource(source)
    WebDataSourcePriority.entries.forEach { priority ->
        repeat(2) {
            proxy.getBookInformation("book", priority)
            proxy.getBookVolumes("book", priority)
            proxy.getChapterContent("chapter", "book", priority)
        }
    }
    check(source.calls.size == 18)
    check(source.calls.map { it.priority } == WebDataSourcePriority.entries.flatMap { priority -> List(6) { priority } })
    check(source.calls.all { it.book == "book" && (it.operation != "chapter" || it.chapter == "chapter") })
}

fun main() = runBlocking {
    repeatedRequestsHitCache()
    println("PASS repeated requests hit cache and preserve miss priority")
    idsAndResponseTypesAreIsolated()
    println("PASS book, response type, chapter and ambiguous concatenation isolation")
    expiredEntriesAreRefetched()
    println("PASS deterministic expiration and refresh")
    failuresAreNotCached()
    println("PASS errors remain retryable for every operation")
    disabledCacheAndPrioritiesPassThrough()
    println("PASS disabled cache forwards every request and every priority")
    println("Proxy cache checks: 5/5 passed (production proxy and Cache, stub source and DTOs)")
}
