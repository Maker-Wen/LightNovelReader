package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import kotlinx.serialization.Serializable

/** A position in the original content; viewportOffset is a fraction of viewport height. */
@Serializable
data class ReaderAnchor(
    val componentIndex: Int,
    val characterOffset: Int = 0,
    val fraction: Float = 0f,
    val viewportOffset: Float = 0f
)

@Serializable
data class ReaderPosition(
    val chapterId: String,
    val fraction: Float,
    val anchor: ReaderAnchor? = null,
    val contentKey: String = "",
    val locationHash: Int? = null
)

sealed interface ChapterPosition {
    data class Relative(val fraction: Float) : ChapterPosition
    data class Exact(val position: ReaderPosition) : ChapterPosition
}

/** A pending null hash invalidates a paged marker; an absent entry falls back to disk. */
internal suspend fun readReaderHistory(
    currentProgress: Float?,
    hasPendingLocationHash: Boolean,
    pendingLocationHash: Int?,
    readSavedProgress: suspend () -> Float,
    readSavedLocationHash: suspend () -> Int?,
): Pair<Float, Int?> = (currentProgress ?: readSavedProgress()) to
    (if (hasPendingLocationHash) pendingLocationHash else readSavedLocationHash())

/** A single visible page can represent both chapter endpoints. */
fun singlePageNavigationFraction(position: ChapterPosition?, legacyProgress: Float): Float {
    val fraction = when (position) {
        is ChapterPosition.Relative -> position.fraction
        is ChapterPosition.Exact -> position.position.fraction
        null -> if (legacyProgress.isFinite() && legacyProgress >= 1f) 1f else 0f
    }
    return (fraction.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f)
}
