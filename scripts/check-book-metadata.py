#!/usr/bin/env python3
"""Check the production BookRepository metadata flows with controlled dependencies.

Compiles the whole repository and its actual API models. Android, Room, HTTP,
text processing and WorkManager are boundary doubles; this is not device proof.
"""
from kotlin_check import root, run_check, versions


stubs = {
    "Uri": """package android.net
class Uri { companion object { val EMPTY = Uri() } }
""",
    "Compose": """package androidx.compose.runtime
annotation class Stable
""",
    "Annotations": """package androidx.annotation
annotation class StringRes
""",
    "Navigation": """package androidx.navigation3.runtime
interface NavKey
""",
    "Inject": """package javax.inject
annotation class Inject
annotation class Singleton
""",
    "Log": """package android.util
object Log {
 val errors = mutableListOf<String>()
 fun e(tag: String, message: String): Int { errors += "$tag: $message"; return 0 }
}
""",
    "BuildConfig": """package indi.dmzz_yyhyy.lightnovelreader
object BuildConfig { var BENCHMARK = false }
""",
    "Work": """package androidx.work
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
enum class ExistingWorkPolicy { KEEP }
class OneTimeWorkRequest
class OneTimeWorkRequestBuilder<T> {
 fun setInputData(data: Map<String, Any?>) = this
 fun build() = OneTimeWorkRequest()
}
fun workDataOf(vararg values: Pair<String, Any?>) = mapOf(*values)
class WorkManager {
 fun getWorkInfoByIdFlow(id: UUID) = flowOf(Unit)
 fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {}
}
""",
    "CacheWork": """package indi.dmzz_yyhyy.lightnovelreader.data.work
class CacheBookWork { companion object { fun ofId(id: String) = id } }
""",
    "Probe": """package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader
object ReaderBenchmarkProbe {
 var beforeChapterLoad: (suspend (String, String) -> Unit)? = null
 fun beginChapterFlow(id: String, priority: String) {}
 fun endSection() {}
}
""",
    "Local": """package indi.dmzz_yyhyy.lightnovelreader.data.local
import io.nightfish.lightnovelreader.api.book.*
import kotlinx.coroutines.flow.flowOf
class LocalBookDataSource(val events: MutableList<String>) {
 var information: BookInformation? = null
 var volumes: BookVolumes? = null
 val saved = mutableListOf<Any>()
 suspend fun getBookInformation(id: String) = information
 suspend fun getBookVolumes(id: String) = volumes
 suspend fun updateBookInformation(value: BookInformation) {
  events += "persist"; saved += value; information = value
 }
 suspend fun updateBookVolumes(value: BookVolumes) {
  events += "persist"; saved += value; volumes = value
 }
 suspend fun getChapterContent(id: String): ChapterContent? = null
 suspend fun updateChapterContent(value: ChapterContent) {}
 suspend fun getUserReadingData(id: String) = UserReadingData(id)
 fun getUserReadingDataFlow(id: String) = flowOf(UserReadingData(id))
 suspend fun getAllUserReadingData() = emptyList<UserReadingData>()
 suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {}
 suspend fun isChapterContentExists(id: String) = false
}
""",
    "Bookshelf": """package indi.dmzz_yyhyy.lightnovelreader.data.bookshelf
import java.time.LocalDateTime
data class Metadata(val lastUpdate: LocalDateTime, val bookShelfIds: List<Int>)
class BookshelfRepository(val events: MutableList<String>) {
 var metadata: Metadata? = null
 val updated = mutableListOf<Pair<Int, String>>()
 val timestamps = mutableListOf<Pair<String, LocalDateTime>>()
 suspend fun getBookshelfBookMetadata(id: String) = metadata
 suspend fun updateBookshelfBookMetadataLastUpdateTime(id: String, time: LocalDateTime) {
  events += "timestamp"; timestamps += id to time
 }
 suspend fun addUpdatedBooksIntoBookShelf(shelfId: Int, id: String) {
  events += "mark-updated"; updated += shelfId to id
 }
}
""",
    "TextProcessing": """package indi.dmzz_yyhyy.lightnovelreader.data.text
import io.nightfish.lightnovelreader.api.book.*
class TextProcessingRepository(val events: MutableList<String>) {
 val inputs = mutableListOf<Any>()
 fun processBookInformation(block: () -> BookInformation): BookInformation {
  val value = block(); inputs += value; events += "process"
  return value.copy(title = "processed:" + value.title)
 }
 fun processBookVolumes(block: () -> BookVolumes): BookVolumes {
  val value = block(); inputs += value; events += "process"
  return value.copy(volumes = value.volumes.map { it.copy(volumeTitle = "processed:" + it.volumeTitle) })
 }
 fun processChapterContent(id: String, block: () -> ChapterContent) = block()
}
""",
    "Web": """package indi.dmzz_yyhyy.lightnovelreader.data.web
import androidx.navigation3.runtime.NavKey
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
class WebBookDataSourceProvider(val value: ControlledSource)
class ControlledSource(val events: MutableList<String>) {
 val requests = mutableListOf<Pair<String, WebDataSourcePriority>>()
 var information: suspend () -> Result<BookInformation, WebRequestError> = { error("Unexpected request") }
 var volumes: suspend () -> Result<BookVolumes, WebRequestError> = { error("Unexpected request") }
 suspend fun getBookInformation(id: String, priority: WebDataSourcePriority): Result<BookInformation, WebRequestError> {
  requests += id to priority; events += "request"; return information()
 }
 suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority): Result<BookVolumes, WebRequestError> {
  requests += id to priority; events += "request"; return volumes()
 }
 suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority): Result<ChapterContent, WebRequestError> = error("Unexpected chapter request")
 fun progressBookTagClick(tag: String): NavKey? = null
}
""",
}

api = root / "api/src/main/kotlin/io/nightfish/lightnovelreader/api"
sources = [api / "book" / f"{name}.kt" for name in (
    "BookRepositoryApi", "BookInformation", "BookVolumes", "Volume", "ChapterInformation",
    "ChapterContent", "UserReadingData", "WordCount",
)]
sources += [api / "error/WebRequestError.kt", api / "web/WebDataSourcePriority.kt",
            root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/BookRepository.kt",
            root / "scripts/BookMetadataCheck.kt"]
run_check("BookMetadataCheckKt", sources, dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["kotlinxCoroutinesCore"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["kotlinSerialization"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", versions["kotlinSerialization"]),
    ("com.michael-bull.kotlin-result", "kotlin-result-jvm", versions["kotlinResult"]),
], stubs=stubs)
