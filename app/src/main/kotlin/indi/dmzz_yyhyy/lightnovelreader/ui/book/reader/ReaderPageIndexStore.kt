package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.readerDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ReaderPageIndex(
    val layoutKey: String,
    // Pagination identity: scroll includes the title; this is not the source anchor's body key.
    val contentKey: String,
    val pageCount: Int
) {
    internal fun isValid(): Boolean = layoutKey.isNotBlank() && contentKey.isNotBlank() && pageCount > 0
}

@Serializable
private data class ReaderBookPageIndices(
    val version: Int = 2,
    val appVersion: Int = BuildConfig.VERSION_CODE,
    val chapters: Map<String, ReaderPageIndex> = emptyMap()
)

/** Disposable, source-scoped derived data; it never requests chapter content. */
@Singleton
class ReaderPageIndexStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.cacheDir, "reader_page_indices")
    // Serialize read-modify-write operations so concurrent chapter updates cannot overwrite each other.
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun readBook(sourceId: String, bookId: String, layoutKey: String): Map<String, ReaderPageIndex> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                load(file(sourceId, bookId)).chapters.filterValues { it.layoutKey == layoutKey && it.isValid() }
            }
        }

    suspend fun write(sourceId: String, bookId: String, chapterId: String, index: ReaderPageIndex) {
        if (sourceId.isBlank() || bookId.isBlank() || chapterId.isBlank() || !index.isValid()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val atomicFile = file(sourceId, bookId)
                val old = load(atomicFile)
                if (old.chapters[chapterId] == index) return@withLock
                val data = old.copy(chapters = old.chapters + (chapterId to index))
                try {
                    val bytes = json.encodeToString(data).toByteArray(Charsets.UTF_8)
                    currentCoroutineContext().ensureActive()
                    atomicFile.baseFile.parentFile?.mkdirs()
                    val output = atomicFile.startWrite()
                    try {
                        output.write(bytes)
                        atomicFile.finishWrite(output)
                    } catch (error: Exception) {
                        atomicFile.failWrite(output)
                        throw error
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Reading continues if storage is full or the disposable cache was removed.
                    Log.w("ReaderPageIndexStore", "Unable to save page index", error)
                }
            }
        }
    }

    private fun file(sourceId: String, bookId: String): AtomicFile = AtomicFile(
        File(File(directory, readerDigest(sourceId)), "${readerDigest(bookId)}.json")
    )

    private fun load(file: AtomicFile): ReaderBookPageIndices = try {
        json.decodeFromString<ReaderBookPageIndices>(file.openRead().bufferedReader().use { it.readText() })
            .takeIf { it.version == 2 && it.appVersion == BuildConfig.VERSION_CODE }
            ?: ReaderBookPageIndices()
    } catch (_: Exception) {
        ReaderBookPageIndices()
    }
}
