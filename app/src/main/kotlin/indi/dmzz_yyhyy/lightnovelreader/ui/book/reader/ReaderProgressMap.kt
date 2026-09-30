package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPosition
import java.util.Collections

data class ReaderProgressChapter(val id: String, val title: String, val pageCount: Int?)

class ReaderProgressMap(chapters: List<ReaderProgressChapter>) {
    val chapters: List<ReaderProgressChapter> = Collections.unmodifiableList(chapters.toList())
    val isEmpty: Boolean get() = chapters.isEmpty()
    private val boundaries: FloatArray

    init {
        val samples = this.chapters.mapNotNull { it.pageCount?.takeIf { count -> count > 0 } }.sorted()
        val median = if (samples.isEmpty()) 1.0 else
            (samples[(samples.size - 1) / 2].toDouble() + samples[samples.size / 2]) / 2
        val weights = this.chapters.map { it.pageCount?.takeIf { count -> count > 0 }?.toDouble() ?: median }
        val total = weights.sum()
        var sum = 0.0
        boundaries = FloatArray(weights.size + 1) { index ->
            if (index == 0) 0f else {
                sum += weights[index - 1]
                (sum / total).toFloat()
            }
        }
    }

    fun target(progress: Float): ReaderPosition? {
        if (isEmpty) return null
        val value = progress.unitFraction()
        if (value == 1f) return ReaderPosition(chapters.last().id, 1f)
        val boundary = boundaries.binarySearch(value)
        val index = if (boundary >= 0) boundary else -boundary - 2
        val width = boundaries[index + 1] - boundaries[index]
        return ReaderPosition(
            chapterId = chapters[index].id,
            fraction = if (width > 0f) ((value - boundaries[index]) / width).unitFraction() else 0f
        )
    }

    fun progress(position: ReaderPosition): Float {
        val index = chapters.indexOfFirst { it.id == position.chapterId }
        if (index == -1) return 0f
        val start = boundaries[index]
        val fraction = position.fraction.unitFraction()
        if (fraction == 0f) return start
        if (fraction == 1f) return boundaries[index + 1]
        return (start + (boundaries[index + 1] - start) * fraction).unitFraction()
    }

    fun title(chapterId: String): String = chapters.firstOrNull { it.id == chapterId }?.title.orEmpty()

    private fun Float.unitFraction(): Float = if (this > 0f) coerceAtMost(1f) else 0f
}
