package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import kotlin.math.abs
import kotlin.math.hypot

internal enum class ReaderSeekAction { Commit, Cancel, Return }

/** Both origins come from the same synchronous begin event, before a UI recomposition is required. */
data class ReaderSeekStart(val formalOriginProgress: Float?, val temporaryOriginProgress: Float?)

/** Coordinates and distances are dp; progress is a fraction in 0..1. */
internal class ReaderSeekGesture(
    startProgress: Float,
    originProgress: Float?,
    temporaryOriginProgress: Float?,
    private val downX: Float,
    private val downY: Float,
    private val trackStart: Float,
    trackLength: Float,
    private val touchSlop: Float
) {
    private val start = startProgress.fraction()
    private val formalOrigin = originProgress?.takeIf { it.isFinite() }?.fraction()
    val origin: Float? = formalOrigin ?: temporaryOriginProgress?.takeIf { it.isFinite() }?.fraction()
    private val length = trackLength.coerceAtLeast(1f)
    private val thumbX = trackStart + length * start
    private val originX = origin?.let { trackStart + length * it }
    private val thumbDistance = hypot(downX - thumbX, downY)
    private val originDistance = originX?.let { hypot(downX - it, downY) } ?: Float.POSITIVE_INFINITY
    private val onThumb = thumbDistance <= 12f
    private val inThumbTarget = abs(downX - thumbX) <= 24f && abs(downY) <= 24f
    private val hitOrigin = formalOrigin != null && originX != null &&
        abs(downX - originX) <= 24f && abs(downY) <= 24f &&
        (abs(thumbX - originX) <= 4f ||
            (!onThumb && (originDistance <= 8f || !inThumbTarget || originDistance < thumbDistance)))
    private val hitThumb = inThumbTarget && !hitOrigin
    private val grabOffset = if (hitThumb || (hitOrigin && onThumb)) thumbX - downX else 0f
    private var adjusting = !hitThumb && !hitOrigin
    private var armed = formalOrigin != null || (originX != null && abs(thumbX - originX) > 6f)

    var preview: Float? = if (adjusting) progressAt(downX) else null
        private set
    var snapped: Boolean = false
        private set

    val action: ReaderSeekAction
        get() = when {
            snapped -> if (formalOrigin == null) ReaderSeekAction.Cancel else ReaderSeekAction.Return
            !adjusting -> if (hitOrigin) ReaderSeekAction.Return else ReaderSeekAction.Cancel
            else -> ReaderSeekAction.Commit
        }

    /** Call for pointer-up too, so the final position can release or enter the snap. */
    fun update(x: Float, y: Float): Boolean {
        if (!adjusting) {
            if (hypot(x - downX, y - downY) <= touchSlop) return false
            adjusting = true
        }
        val next = progressAt(x)
        val distance = originX?.let { abs((x + grabOffset).coerceIn(trackStart, trackStart + length) - it) }
        if (distance != null && distance > 6f) armed = true
        val nextSnapped = distance != null && armed && distance <= if (snapped) 6f else 4f
        val entered = nextSnapped && !snapped
        snapped = nextSnapped
        preview = if (snapped) origin else next
        return entered
    }

    private fun progressAt(x: Float) = ((x + grabOffset - trackStart) / length).fraction()
}

private fun Float.fraction(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
