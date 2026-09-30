package io.nightfish.lightnovelreader.source.linovelib

import android.content.Context
import android.net.Uri
import androidx.navigation3.runtime.NavKey
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.content.builder.buildContent
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.paragraph
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.text.TextProcessingRepositoryApi
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/** Stable API 4 identifier.  Old integer identifiers are intentionally not migrated. */
internal val LINOVELIB_SOURCE_ID = Identifier("lightnovelreader", "linovelib")

@WebDataSource(
    name = "Linovelib",
    provider = "linovelib.com"
)
class LinovelibWebDataSource(
    private val context: Context,
    localBookDataSource: LocalBookDataSourceApi,
    textProcessingRepository: TextProcessingRepositoryApi,
    bookshelfRepository: BookshelfRepositoryApi,
    webDataSourceManager: WebBookDataSourceManagerApi
) : WebBookDataSource {
    private val coverData = LinovelibCoverData(
        localBookDataSource,
        textProcessingRepository,
        bookshelfRepository
    ) { webDataSourceManager.getWebDataSource() === this }
    private val parser = LinovelibHtmlParser(host = LinovelibUrls.HOST)
    private val diagnostics = LinovelibDiagnostics()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val offlineStateFlow = MutableStateFlow(true)
    private val requestCoordinator = LinovelibRequestCoordinator()
    private val sessionCookies = mutableMapOf<String, String>()
    private var offlineMonitorJob: Job? = null

    override val permits: Int = 1
    override val cache: Cache = Cache(
        maxCountEachType = LinovelibDataSourceConfiguration.cacheEntriesPerType,
        timeout = LinovelibDataSourceConfiguration.cacheTimeoutMillis
    )
    override val id: Identifier = LINOVELIB_SOURCE_ID
    override val offLine: Boolean get() = offlineStateFlow.value
    override val isOffLineFlow: StateFlow<Boolean> = offlineStateFlow

    private val linovelibSearchProvider = LinovelibSearchProvider(
        ::getHtml,
        ::getSearchHtml,
        parser,
        diagnostics,
        LinovelibUrls.HOST
    )
    override val searchProvider: SearchProvider = linovelibSearchProvider
    private val linovelibExplorePageProvider = LinovelibExplorePageProvider(
        ::getHtml,
        parser,
        linovelibSearchProvider::searchBookIds,
        LinovelibUrls.HOST
    )
    override val explorePageProvider: ExplorePageProvider = linovelibExplorePageProvider
    private val imageStore = LinovelibImageStore(
        directory = File(context.filesDir, "linovelib/chapter-images"),
        diagnostics = diagnostics,
        referer = LinovelibUrls.HOST
    )
    override val imageHeader: Map<String, String> = mapOf(
        "User-Agent" to CONTENT_USER_AGENT,
        "Referer" to LinovelibUrls.HOST,
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    )

    @Synchronized
    override fun onLoad() {
        coverData.onLoad()
        if (!LinovelibDataSourceConfiguration.shouldStartOfflineMonitor(offlineMonitorJob?.isActive == true)) return
        offlineMonitorJob = scope.launch { offlineStateFlow.value = isOffLine() }
    }

    override suspend fun isOffLine(): Boolean = withContext(Dispatchers.IO) {
        val offline = runCatching {
            executeRequest(
                url = LinovelibUrls.HOST,
                userAgent = USER_AGENT,
                referrer = LinovelibUrls.HOST,
                acceptLanguage = ACCEPT_LANGUAGE
            ).statusCode() !in 200..399
        }.getOrElse { true }
        offlineStateFlow.value = offline
        offline
    }

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> =
        withContext(Dispatchers.IO) {
            requestResult("书本信息请求失败", "无法获取书本的信息(id=$id)", "BOOK_ERROR") {
                val parsed = parser.parseBookInformation(id, getHtml(LinovelibUrls.book(LinovelibUrls.HOST, id)))
                require(parsed.title.isNotBlank()) { "Book title was not found" }
                parsed.toBookInformation()
            }
        }

    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> =
        withContext(Dispatchers.IO) {
            requestResult("目录请求失败", "无法获取书本的目录(id=$id)", "CATALOG_ERROR") {
                val parsed = parser.parseCatalog(id, getHtml(LinovelibUrls.catalog(LinovelibUrls.HOST, id)))
                require(parsed.volumes.isNotEmpty()) { "No catalog volumes were found" }
                parsed.toBookVolumes()
            }
        }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ): Result<ChapterContent, WebRequestError> = withContext(Dispatchers.IO) {
        val currentChapterId = LinovelibChapterIds.forApp(chapterId)
        requestResult("章节请求失败", "无法获取章节内容(id=$chapterId)", "CHAPTER_ERROR") {
            getChapterContentPages(currentChapterId, bookId).toChapterContent()
        }
    }

    override suspend fun getCoverUriInVolume(
        bookId: String,
        volume: Volume,
        volumeChapterContentMap: MutableMap<String, ChapterContent>,
        context: Context
    ): Uri? = findVolumeCoverUri(volume, volumeChapterContentMap)?.let(Uri::parse)

    override fun progressBookTagClick(tag: String): NavKey? {
        val pageId = linovelibExplorePageProvider.registerRelatedPage(tag)
        diagnostics.info("RELATED_NAV_START", mapOf("tag" to tag, "pageId" to pageId))
        return LinovelibRelatedNavigation.createExpandedRoute(pageId)
    }

    private suspend fun <T> requestResult(
        title: String,
        message: String,
        event: String,
        block: suspend () -> T
    ): Result<T, WebRequestError> = try {
        Ok(block())
    } catch (throwable: Throwable) {
        throwable.rethrowIfCancellation()
        diagnostics.error(event, throwable)
        Err(WebRequestError(title, message, throwable))
    }

    private suspend fun getHtml(url: String): String = executeRequest(
        url = url,
        userAgent = USER_AGENT,
        referrer = LinovelibUrls.HOST,
        acceptLanguage = ACCEPT_LANGUAGE
    ).body()

    private suspend fun getContentHtml(url: String): Connection.Response = executeRequest(
        url = url,
        userAgent = CONTENT_USER_AGENT,
        referrer = "${LinovelibUrls.HOST}/",
        acceptLanguage = ACCEPT_LANGUAGE,
        cookies = mapOf("night" to "0"),
        cacheControl = true
    )

    private suspend fun getSearchHtml(keyword: String): LinovelibSearchResponse {
        val guardJs = executeRequest(
            url = "${LinovelibUrls.HOST}/search.html?search_guard=js",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${LinovelibUrls.HOST}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cacheControl = true,
            includeSessionCookies = false
        )
        val jsToken = LinovelibSearchGuard.extractCookieValue(guardJs.body(), "jieqiSearchJs")
        require(jsToken.isNotEmpty()) { "Missing jieqiSearchJs search guard cookie" }

        val guardCss = executeRequest(
            url = "${LinovelibUrls.HOST}/search.html?search_guard=css",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${LinovelibUrls.HOST}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cacheControl = true,
            includeSessionCookies = false
        )
        val cssToken = guardCss.cookies()["jieqiSearchCss"].orEmpty()
        require(cssToken.isNotEmpty()) { "Missing jieqiSearchCss search guard cookie" }

        val redeem = executeRequest(
            url = "${LinovelibUrls.HOST}/search.html?search_guard=redeem&r=${System.currentTimeMillis()}",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${LinovelibUrls.HOST}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cookies = LinovelibSearchGuard.guardCookies(jsToken, cssToken),
            cacheControl = true,
            includeSessionCookies = false
        )
        val ticketToken = redeem.cookies()["jieqiSearchTicket"].orEmpty()
        require(ticketToken.isNotEmpty()) { "Missing jieqiSearchTicket search cookie" }
        diagnostics.info("SEARCH_GUARD_OK", mapOf("keyword" to keyword))

        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val response = executeRequest(
            url = "${LinovelibUrls.HOST}/search.html?searchkey=$encodedKeyword",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${LinovelibUrls.HOST}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cookies = LinovelibSearchGuard.ticketCookies(ticketToken),
            cacheControl = true,
            includeSessionCookies = false
        )
        return LinovelibSearchResponse(response.url().toString(), response.body())
    }

    private suspend fun executeRequest(
        url: String,
        userAgent: String,
        referrer: String,
        acceptLanguage: String,
        cookies: Map<String, String> = emptyMap(),
        cacheControl: Boolean = false,
        includeSessionCookies: Boolean = true
    ): Connection.Response {
        val startedAt = System.nanoTime()
        return try {
            var lastNetworkError: Throwable? = null
            for (attempt in 0 until LinovelibRequestPolicy.maxAttempts) {
                val response = try {
                    executeSingleRequest(
                        url, userAgent, referrer, acceptLanguage, cookies,
                        cacheControl, includeSessionCookies, attempt
                    )
                } catch (throwable: Throwable) {
                    throwable.rethrowIfCancellation()
                    lastNetworkError = throwable
                    if (throwable !is IOException || attempt == LinovelibRequestPolicy.maxAttempts - 1) throw throwable
                    diagnostics.info(
                        "HTTP_RETRY",
                        mapOf(
                            "requested" to url,
                            "attempt" to attempt + 1,
                            "delayMs" to LinovelibRequestPolicy.networkRetryDelayMillis(attempt),
                            "reason" to throwable.javaClass.simpleName
                        )
                    )
                    continue
                }
                val inspection = diagnostics.inspectHtml(response.body())
                val successful = diagnostics.isSuccessfulHttpStatus(response.statusCode())
                diagnostics.info(
                    if (successful) "HTTP_OK" else "HTTP_STATUS_ERROR",
                    linkedMapOf(
                        "requested" to url,
                        "final" to response.url().toString(),
                        "status" to response.statusCode(),
                        "chars" to inspection.characters,
                        "elapsedMs" to elapsedMilliseconds(startedAt),
                        "loadFailure" to inspection.hasLoadFailure
                    )
                )
                if (successful) {
                    offlineStateFlow.value = false
                    return response
                }
                val retryDelay = LinovelibRequestPolicy.retryDelayMillis(
                    response.statusCode(), response.header("Retry-After"), attempt
                )
                if (retryDelay == null || attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                    throw IOException("HTTP ${response.statusCode()} for ${response.url()}")
                }
                diagnostics.info(
                    "HTTP_RETRY",
                    mapOf("requested" to url, "status" to response.statusCode(), "attempt" to attempt + 1, "delayMs" to retryDelay)
                )
            }
            throw lastNetworkError ?: IOException("Request attempts exhausted for $url")
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            if (throwable is IOException) offlineStateFlow.value = true
            diagnostics.error("HTTP_ERROR", throwable, mapOf("requested" to url, "elapsedMs" to elapsedMilliseconds(startedAt)))
            throw throwable
        }
    }

    private suspend fun executeSingleRequest(
        url: String,
        userAgent: String,
        referrer: String,
        acceptLanguage: String,
        cookies: Map<String, String>,
        cacheControl: Boolean,
        includeSessionCookies: Boolean,
        attempt: Int
    ): Connection.Response = requestCoordinator.execute(
        cooldownAfter = { result ->
            if (attempt == LinovelibRequestPolicy.maxAttempts - 1) null else result.fold(
                onSuccess = { response ->
                    if (diagnostics.isSuccessfulHttpStatus(response.statusCode())) null
                    else LinovelibRequestPolicy.retryDelayMillis(response.statusCode(), response.header("Retry-After"), attempt)
                },
                onFailure = { throwable ->
                    throwable.takeIf { it is IOException }?.let { LinovelibRequestPolicy.networkRetryDelayMillis(attempt) }
                }
            )
        }
    ) {
        val requestCookies = if (includeSessionCookies) sessionCookies.toMutableMap().apply { putAll(cookies) } else cookies
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", acceptLanguage)
            .referrer(referrer)
            .cookies(requestCookies)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .acceptLinovelibContentTypes()
            .timeout(12_000)
        if (cacheControl) connection.header("Cache-Control", "no-cache")
        val response = withContext(Dispatchers.IO) { connection.execute() }
        currentCoroutineContext().ensureActive()
        response.also { if (includeSessionCookies) sessionCookies.putAll(it.cookies()) }
    }

    private suspend fun getChapterContentPages(chapterId: String, bookId: String): ParsedChapterContent {
        val websiteChapterId = LinovelibChapterIds.forWebsite(chapterId)
        val pages = mutableListOf<ParsedChapterContent>()
        val visitedUrls = mutableSetOf<String>()
        var nextUrl = LinovelibUrls.fullChapter(LinovelibUrls.HOST, bookId, websiteChapterId)
        var pageCount = 0
        while (true) {
            if (nextUrl.isBlank() || pageCount >= MAX_CHAPTER_PAGES || !visitedUrls.add(nextUrl)) break
            pageCount++
            val response = getContentHtml(nextUrl)
            val finalUrl = response.url().toString()
            val page = parser.parseChapterContent(websiteChapterId, response.body(), finalUrl, restoreParagraphOrder = true)
            pages += page
            val candidate = page.nextPageUrl.takeIf(String::isNotBlank)
                ?.let { URI(finalUrl).resolve(it).toString() }.orEmpty()
            if (candidate.isBlank() || parser.chapterIdFromHref(candidate) != websiteChapterId) break
            nextUrl = candidate
        }
        val first = pages.firstOrNull() ?: error("Chapter page was empty")
        val last = pages.last()
        return ParsedChapterContent(
            id = chapterId,
            title = first.title,
            previousChapterId = LinovelibChapterIds.forApp(first.previousChapterId),
            nextChapterId = LinovelibChapterIds.forApp(last.nextChapterId),
            nextPageUrl = last.nextPageUrl,
            blocks = pages.flatMap { it.blocks }
        )
    }

    private fun elapsedMilliseconds(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun ParsedBookInformation.toBookInformation(): BookInformation = BookInformation(
        id = id,
        title = title,
        subtitle = subtitle,
        coverUri = coverUrl.takeIf(String::isNotEmpty)?.let(Uri::parse) ?: Uri.EMPTY,
        author = author,
        description = description,
        tags = LinovelibRelatedSearch.displayTags(author, tags, publishingHouse),
        publishingHouse = "",
        wordCount = WordCount(wordCount),
        lastUpdated = lastUpdated.atStartOfDay(),
        isComplete = isComplete
    )

    private fun ParsedCatalog.toBookVolumes(): BookVolumes = BookVolumes(
        bookId = bookId,
        volumes = volumes.mapIndexed { index, volume ->
            Volume(
                volumeId = "$bookId-${index + 1}",
                volumeTitle = volume.title,
                chapters = volume.chapters.map { ChapterInformation(LinovelibChapterIds.forApp(it.id), it.title) }
            )
        }
    )

    private suspend fun ParsedChapterContent.toChapterContent(): ChapterContent {
        val localizedBlocks = imageStore.localize(LinovelibContentFormatter.format(blocks))
        val content = buildContent {
            localizedBlocks.forEach { block ->
                when (block) {
                    is ParsedContentBlock.Text -> paragraph { text(block.text) }
                    is ParsedContentBlock.Image -> image(Uri.parse(block.url))
                }
            }
        }
        require(title.isNotBlank()) { "Chapter title was not found" }
        require(localizedBlocks.isNotEmpty()) { "Chapter content was empty" }
        return ChapterContent(
            id = id,
            title = title,
            content = content,
            prevChapter = previousChapterId.takeIf(String::isNotBlank),
            nextChapter = nextChapterId.takeIf(String::isNotBlank)
        )
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"
        const val CONTENT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36 EdgA/135.0.0.0"
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.7"
        const val MAX_CHAPTER_PAGES = 100
    }
}

internal fun findVolumeCoverUri(
    volume: Volume,
    volumeChapterContentMap: Map<String, ChapterContent>
): String? = volume.chapters
    .asSequence()
    .filter { chapter -> chapter.title.trim().lowercase() in ILLUSTRATION_TITLES }
    .mapNotNull { chapter -> volumeChapterContentMap[chapter.id] }
    .flatMap { chapter -> chapter.content["components"]?.jsonArray.orEmpty().asSequence() }
    .mapNotNull { component ->
        component.jsonObject
            .takeIf { it["id"]?.jsonPrimitive?.content == io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData.id.toString() }
            ?.get("data")?.jsonObject?.get("uri")?.jsonPrimitive?.content
    }
    .firstOrNull()

private val ILLUSTRATION_TITLES = setOf(
    "插图", "插圖", "插画", "插畫", "彩页", "彩頁", "彩图", "彩圖",
    "illustration", "illustrations"
)
