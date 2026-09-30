#!/usr/bin/env python3
"""Verify the production cache proxy and Cache using cached Kotlin dependencies.

The source interface and response DTOs are minimal JVM test doubles. The cache
proxy, Cache, request errors and priorities are compiled from production sources.
This checks cache behavior and priority forwarding, not HTTP or priority scheduling.
"""
from kotlin_check import root, run_check, versions


stubs = {
    "Book": """package io.nightfish.lightnovelreader.api.book
data class BookInformation(val id: String, val revision: Int)
data class BookVolumes(val id: String, val revision: Int)
data class ChapterContent(val id: String, val bookId: String, val revision: Int)
""",
    "Source": """package io.nightfish.lightnovelreader.api.web
import io.nightfish.lightnovelreader.api.util.Cache
class WebBookDataSource(val id: String, val cache: Cache?)
""",
    "Proxy": """package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
interface ProxyWebBookDataSource {
 val proxiedWebBookDataSource: ProxyWebBookDataSource
 val origin: WebBookDataSource get() = proxiedWebBookDataSource.origin
 val id get() = origin.id
 suspend fun getBookInformation(id: String, priority: WebDataSourcePriority): Result<BookInformation, WebRequestError>
 suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority): Result<BookVolumes, WebRequestError>
 suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority): Result<ChapterContent, WebRequestError>
}
"""
}

api = root / "api/src/main/kotlin/io/nightfish/lightnovelreader/api"
sources = [root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/web/proxy/ProxyCachedWebBookDataSource.kt",
           api / "util/Cache.kt", api / "error/WebRequestError.kt", api / "web/WebDataSourcePriority.kt",
           root / "scripts/ProxyCacheCheck.kt"]
run_check("ProxyCacheCheckKt", sources, dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["kotlinxCoroutinesCore"]),
    ("com.michael-bull.kotlin-result", "kotlin-result-jvm", versions["kotlinResult"]),
], stubs=stubs)
