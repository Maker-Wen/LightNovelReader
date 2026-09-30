package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

/** Chapter-local geometry. The exposed arrays are read-only after construction. */
internal class ScrollGeometryIndex private constructor(
    val tops: FloatArray,
    val heights: IntArray,
    val totalHeight: Int,
) {
    // Fractional padding can put a bottom slightly beyond the next zero-height component.
    // Prefix maxima preserve the first matching component while allowing binary search.
    private val bottomUpperBounds = FloatArray(tops.size).also { bounds ->
        var maximum = Float.NEGATIVE_INFINITY
        for (index in tops.indices) {
            maximum = maxOf(maximum, tops[index] + heights[index])
            bounds[index] = maximum
        }
    }

    fun componentAt(offset: Float): Int {
        var low = 0
        var high = bottomUpperBounds.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (bottomUpperBounds[middle] > offset) high = middle else low = middle + 1
        }
        return low.coerceAtMost(tops.lastIndex)
    }

    fun pageOffsets(viewportHeight: Int): IntArray {
        require(viewportHeight > 0) { "Viewport height must be positive" }
        val end = (totalHeight - viewportHeight).coerceAtLeast(0)
        val fullSteps = if (end == 0) 0 else (end - 1) / viewportHeight + 1
        return IntArray(fullSteps + 1) { index ->
            if (index == fullSteps) end else index * viewportHeight
        }
    }

    /** Maps all pages in O(components + pages); an empty chapter passes component index -1. */
    fun <T> mapPages(viewportHeight: Int, anchor: (componentIndex: Int, offset: Float) -> T): List<T> {
        var componentIndex = 0
        return pageOffsets(viewportHeight).map { pageOffset ->
            val offset = pageOffset.toFloat()
            while (componentIndex < tops.lastIndex && tops[componentIndex] + heights[componentIndex] <= offset) {
                componentIndex++
            }
            anchor(if (tops.isEmpty()) -1 else componentIndex, offset)
        }
    }

    companion object {
        fun fromMeasurements(
            headerHeight: Int,
            componentHeights: IntArray,
            topPaddings: FloatArray,
        ): ScrollGeometryIndex {
            require(headerHeight >= 0) { "Header height must be nonnegative" }
            require(componentHeights.size == topPaddings.size) { "Each component needs a top padding" }
            val tops = FloatArray(componentHeights.size)
            val heights = IntArray(componentHeights.size)
            var measuredHeight = headerHeight.toLong()
            for (index in componentHeights.indices) {
                val componentHeight = componentHeights[index]
                val padding = topPaddings[index]
                require(componentHeight >= 0) { "Component height must be nonnegative" }
                require(padding.isFinite() && padding >= 0f) { "Top padding must be finite and nonnegative" }
                tops[index] = measuredHeight.toFloat() + padding
                heights[index] = (componentHeight - padding.toInt()).coerceAtLeast(0)
                measuredHeight += componentHeight
                require(measuredHeight <= Int.MAX_VALUE) { "Chapter height exceeds the layout coordinate range" }
            }
            return ScrollGeometryIndex(tops, heights, measuredHeight.toInt())
        }
    }
}

/** Returns the last page at or before offset. Offsets must be in nondecreasing order. */
internal fun scrollPageAt(offsets: FloatArray, offset: Float): Int {
    var low = 0
    var high = offsets.size
    while (low < high) {
        val middle = low + (high - low) / 2
        if (offsets[middle] <= offset) low = middle + 1 else high = middle
    }
    return (low - 1).coerceAtLeast(0)
}
