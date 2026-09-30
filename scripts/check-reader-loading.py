#!/usr/bin/env python3
"""Run the production reader engines against controlled chapter flows.

Android/Compose, the chapter repository and component decoding are test doubles.
The checks exercise engine state/cancellation, not real text layout, Room, HTTP or
Compose snapshot/frame scheduling. No Android device or new Gradle dependency is needed.
"""
from kotlin_check import root, run_check, versions


stubs = {
    "Compose": """package androidx.compose.runtime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KProperty
annotation class Stable
class State<T>(var value: T)
fun <T> mutableStateOf(value: T) = State(value)
fun mutableLongStateOf(value: Long) = State(value)
fun mutableIntStateOf(value: Int) = State(value)
fun mutableFloatStateOf(value: Float) = State(value)
fun <T> mutableStateListOf(vararg values: T) = values.toMutableList()
operator fun <T> State<T>.getValue(receiver: Any?, property: KProperty<*>) = value
operator fun <T> State<T>.setValue(receiver: Any?, property: KProperty<*>, next: T) { value = next }
// Polling is only a substitute for snapshot invalidation, never evidence of Compose correctness.
fun <T> snapshotFlow(block: () -> T) = flow { while (true) { emit(block()); delay(2) } }.distinctUntilChanged()
""",
    "Snapshot": """package androidx.compose.runtime.snapshots
object Snapshot { fun <T> withMutableSnapshot(block: () -> T): T = block() }
""",
    "Android": """package android.os
object SystemClock { fun uptimeMillis() = System.nanoTime() / 1000000 }
object Build { object VERSION { const val SDK_INT = 35 } }
object Trace {
 fun isEnabled() = false
 fun beginSection(name: String) {}
 fun endSection() {}
 fun beginAsyncSection(name: String, id: Int) {}
 fun endAsyncSection(name: String, id: Int) {}
}
""",
    "BuildConfig": """package indi.dmzz_yyhyy.lightnovelreader
object BuildConfig { const val BENCHMARK = false }
""",
    "Text": """package androidx.compose.ui.text
class TextLayoutResult {
 val layoutInput = Input()
 fun getLineTop(line: Int) = line.toFloat()
 fun getLineForOffset(offset: Int) = offset
 fun getLineForVerticalPosition(value: Float) = value.toInt()
 fun getLineStart(line: Int) = line
}
class Input { val text = "text" }
""",
    "Foundation": """package androidx.compose.foundation
@RequiresOptIn annotation class ExperimentalFoundationApi
""",
    "LazyCache": """package androidx.compose.foundation.lazy.layout
class LazyLayoutCacheWindow(aheadFraction: Float, behindFraction: Float)
""",
    "Lazy": """package androidx.compose.foundation.lazy
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import kotlinx.coroutines.yield
data class LazyListItemInfo(val key: Any, val offset: Int = 0, val size: Int = 1000)
class LayoutInfo {
 var totalItemsCount = 0
 var visibleItemsInfo = emptyList<LazyListItemInfo>()
 var viewportStartOffset = 0
 var viewportEndOffset = 500
}
class LazyListState(cacheWindow: LazyLayoutCacheWindow? = null) {
 val layoutInfo = LayoutInfo()
 var isScrollInProgress = false
 var lastScrolledBackward = false
 var lastScrolledForward = false
 var firstVisibleItemIndex = 1
 var firstVisibleItemScrollOffset = 0
 var scrollCalls = 0
 suspend fun scrollToItem(index: Int, offset: Int = 0) { requestScrollToItem(index, offset); yield() }
 fun requestScrollToItem(index: Int, offset: Int = 0) {
  scrollCalls++
  firstVisibleItemIndex = index
  firstVisibleItemScrollOffset = offset
  layoutInfo.visibleItemsInfo = layoutInfo.visibleItemsInfo.map { it.copy(offset = -offset) }
 }
}
""",
    "Data": """package io.nightfish.lightnovelreader.api.content.component.data
open class AbstractContentComponentData(val value: String) { override fun hashCode() = value.hashCode() }
class ParagraphComponentData(value: String): AbstractContentComponentData(value) { val paragraph = Paragraph(listOf(TextNode(value))) }
class Paragraph(val textNodes: List<TextNode>)
class TextNode(val text: String)
""",
    "Error": """package io.nightfish.lightnovelreader.api.error
data class WebRequestError(val title: String, val message: String, val throwable: Throwable? = null)
""",
    "Priority": """package io.nightfish.lightnovelreader.api.web
enum class WebDataSourcePriority { High, Default }
""",
    "Book": """package io.nightfish.lightnovelreader.api.book
data class ChapterContent(val id: String, val title: String, val content: String,
 val prevChapter: String? = null, val nextChapter: String? = null)
class UserReadingData(val currentChapterReadingProgressMap: Map<String, Float> = emptyMap())
""",
    "Parser": """package indi.dmzz_yyhyy.lightnovelreader.data.content
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
class ContentComponentRepository {
 fun getContentDataListFromJson(value: String): List<AbstractContentComponentData> {
  if (value == "THROW") error("Controlled decoding failure")
  return if (value.isEmpty()) emptyList() else value.split("|").map(::AbstractContentComponentData)
 }
}
""",
    "Pagination": """package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
data class ReaderPaginationLayout(val width: Int = 500, val height: Int = 500, val mode: String = "flip") { val key get() = "$mode:$width:$height" }
fun readerContentKey(content: List<AbstractContentComponentData>) = content.joinToString("|") { it.value }
""",
    "MeasurementWindow": """package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip
internal class ChapterMeasurementWindow
""",
}

reader = root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content"
sources = [reader / name for name in (
    "ReaderPosition.kt", "ContentViewModel.kt", "ContentUiState.kt", "ChapterContentUiState.kt",
    "flip/FlipPageContentViewModel.kt", "flip/FlipPageContentUiState.kt",
    "scroll/ScrollContentViewModel.kt", "scroll/ScrollContentUiState.kt", "scroll/ScrollGeometryIndex.kt",
)]
sources += [reader.parent / "ReaderPageIndexKey.kt", root / "scripts/ReaderLoadingCheck.kt", root / "scripts/ReaderLoadingRepository.kt"]
run_check("ReaderLoadingCheckKt", sources, dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["kotlinxCoroutinesCore"]),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-test-jvm", versions["kotlinxCoroutinesCore"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["kotlinSerialization"]),
    ("com.michael-bull.kotlin-result", "kotlin-result-jvm", versions["kotlinResult"]),
], stubs=stubs)
