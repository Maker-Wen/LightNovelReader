import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollGeometryIndex
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.scrollPageAt
import kotlin.random.Random

private data class ReferenceGeometry(val tops: FloatArray, val heights: IntArray, val totalHeight: Int) {
    fun componentAt(offset: Float): Int = tops.indices.firstOrNull { tops[it] + heights[it] > offset }
        ?: tops.lastIndex

    fun pageOffsets(viewportHeight: Int): IntArray {
        val end = (totalHeight - viewportHeight).coerceAtLeast(0)
        return (0 until end step viewportHeight).toList().plus(end).toIntArray()
    }
}

private fun reference(header: Int, measurements: IntArray, paddings: FloatArray): ReferenceGeometry {
    var y = header
    val tops = FloatArray(measurements.size)
    val heights = IntArray(measurements.size)
    measurements.indices.forEach { index ->
        tops[index] = y.toFloat() + paddings[index]
        heights[index] = (measurements[index] - paddings[index].toInt()).coerceAtLeast(0)
        y += measurements[index]
    }
    return ReferenceGeometry(tops, heights, y)
}

private fun compare(
    header: Int,
    measurements: IntArray,
    paddings: FloatArray,
    viewport: Int,
    offsets: List<Float>,
) {
    val expected = reference(header, measurements, paddings)
    val actual = ScrollGeometryIndex.fromMeasurements(header, measurements, paddings)
    check(actual.tops.contentEquals(expected.tops))
    check(actual.heights.contentEquals(expected.heights))
    check(actual.totalHeight == expected.totalHeight)
    for (offset in offsets + expected.tops.toList() + expected.tops.indices.flatMap {
        val end = expected.tops[it] + expected.heights[it]
        listOf(end - .01f, end, end + .01f)
    }) {
        check(actual.componentAt(offset) == expected.componentAt(offset)) {
            "Component mismatch at $offset: ${actual.componentAt(offset)} != ${expected.componentAt(offset)}"
        }
    }
    val pageOffsets = expected.pageOffsets(viewport)
    check(actual.pageOffsets(viewport).contentEquals(pageOffsets))
    var mapperCalls = 0
    val mapped = actual.mapPages(viewport) { index, offset ->
        mapperCalls++
        index to offset
    }
    check(mapperCalls == pageOffsets.size)
    check(mapped == pageOffsets.map { expected.componentAt(it.toFloat()) to it.toFloat() })
    val sortedOffsets = pageOffsets.map { it.toFloat() }.toFloatArray()
    for (offset in offsets + sortedOffsets.toList()) {
        val expectedPage = sortedOffsets.indexOfLast { it <= offset }.coerceAtLeast(0)
        check(scrollPageAt(sortedOffsets, offset) == expectedPage)
    }
}

private fun checkBoundaryCases() {
    val offsets = listOf(Float.NEGATIVE_INFINITY, -1f, 0f, .25f, 1f, 50f, 100f, 200f,
        Float.POSITIVE_INFINITY, Float.NaN)
    compare(0, intArrayOf(), floatArrayOf(), 100, offsets)
    compare(150, intArrayOf(), floatArrayOf(), 100, offsets)
    compare(0, intArrayOf(0), floatArrayOf(0f), 100, offsets)
    compare(12, intArrayOf(20), floatArrayOf(2.75f), 100, offsets)
    compare(0, intArrayOf(100), floatArrayOf(0f), 100, offsets)
    compare(0, intArrayOf(200), floatArrayOf(0f), 100, offsets)
    compare(0, intArrayOf(201), floatArrayOf(0f), 100, offsets)
    compare(10, intArrayOf(0, 0, 1, 0, 90), floatArrayOf(0f, .75f, .5f, 0f, 1.25f), 1, offsets)

    // A real bottom may decrease across a zero-height node. Binary search still finds the first match.
    val fractional = ScrollGeometryIndex.fromMeasurements(0, intArrayOf(10, 0, 5), floatArrayOf(.75f, 0f, 0f))
    check(fractional.componentAt(10.5f) == 0)
    check(fractional.componentAt(10.75f) == 2)
    compare(0, intArrayOf(10, 0, 5), floatArrayOf(.75f, 0f, 0f), 1, offsets)
    // Padding beyond a temporary zero-size measurement must retain the old clamping behavior.
    compare(0, intArrayOf(0, 5, 0, 10), floatArrayOf(15.5f, .25f, 3.75f, 0f), 2, offsets)

    val duplicates = floatArrayOf(0f, 10f, 10f, 20f)
    check(scrollPageAt(duplicates, 10f) == 2)
    check(scrollPageAt(duplicates, -1f) == 0)
    check(scrollPageAt(duplicates, 30f) == 3)
    check(scrollPageAt(floatArrayOf(), 30f) == 0)
}

private fun checkUpdatedMeasurements() {
    val measurements = intArrayOf(80, 0, 100)
    val paddings = floatArrayOf(8.5f, 0f, 8.5f)
    val before = ScrollGeometryIndex.fromMeasurements(20, measurements, paddings)
    val oldTop = before.tops[2]
    val oldPages = before.pageOffsets(100)
    measurements[1] = 360 // An image finishes loading.
    val afterImage = ScrollGeometryIndex.fromMeasurements(20, measurements, paddings)
    check(afterImage.tops[2] == oldTop + 360)
    check(afterImage.totalHeight == before.totalHeight + 360)
    check(afterImage.pageOffsets(100).size > oldPages.size)
    check(before.tops[2] == oldTop && before.pageOffsets(100).contentEquals(oldPages))
    val afterHeader = ScrollGeometryIndex.fromMeasurements(55, measurements, paddings)
    check(afterHeader.tops.indices.all { afterHeader.tops[it] == afterImage.tops[it] + 35 })
    check(afterHeader.totalHeight == afterImage.totalHeight + 35)
    compare(20, measurements, paddings, 100, listOf(0f, 100f, 180f, 440f, 600f))
    compare(55, measurements, paddings, 100, listOf(0f, 100f, 180f, 440f, 600f))
}

private fun checkRandomMeasurements() {
    val random = Random(0x5343524F)
    repeat(1_500) {
        val count = random.nextInt(0, 81)
        val header = random.nextInt(0, 151)
        val measurements = IntArray(count) { if (random.nextInt(5) == 0) 0 else random.nextInt(1, 301) }
        val paddings = FloatArray(count) { random.nextInt(0, 30) + random.nextInt(0, 4) * .25f }
        val total = header + measurements.sum()
        val offsets = List(40) { random.nextFloat() * (total + 200) - 100 }
        compare(header, measurements, paddings, random.nextInt(20, 2001), offsets)
    }
}

private fun checkLargePageMapping() {
    // This has 50,000 components and 100,017 pages. A per-page linear search would visit
    // billions of components; the monotonic walk emits each page exactly once.
    val count = 50_000
    val index = ScrollGeometryIndex.fromMeasurements(17, IntArray(count) { 2 }, FloatArray(count))
    var mapperCalls = 0
    val pages = index.mapPages(1) { component, offset ->
        val expected = ((offset - 17f).coerceAtLeast(0f) / 2).toInt().coerceAtMost(count - 1)
        check(component == expected)
        mapperCalls++
        component
    }
    check(mapperCalls == 100_017 && pages.size == mapperCalls)
    check(pages.first() == 0 && pages.last() == count - 1)
}

private fun checkInvalidMeasurements() {
    fun rejects(block: () -> Unit) {
        check(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
    rejects { ScrollGeometryIndex.fromMeasurements(-1, intArrayOf(), floatArrayOf()) }
    rejects { ScrollGeometryIndex.fromMeasurements(0, intArrayOf(1), floatArrayOf()) }
    rejects { ScrollGeometryIndex.fromMeasurements(0, intArrayOf(-1), floatArrayOf(0f)) }
    rejects { ScrollGeometryIndex.fromMeasurements(0, intArrayOf(1), floatArrayOf(-.5f)) }
    rejects { ScrollGeometryIndex.fromMeasurements(0, intArrayOf(1), floatArrayOf(Float.NaN)) }
    rejects { ScrollGeometryIndex.fromMeasurements(0, intArrayOf(1), floatArrayOf(Float.POSITIVE_INFINITY)) }
    rejects { ScrollGeometryIndex.fromMeasurements(Int.MAX_VALUE, intArrayOf(1), floatArrayOf(0f)) }
    val empty = ScrollGeometryIndex.fromMeasurements(0, intArrayOf(), floatArrayOf())
    rejects { empty.pageOffsets(0) }
    rejects { empty.mapPages(-1) { _, _ -> Unit } }
}

fun main() {
    checkBoundaryCases()
    checkUpdatedMeasurements()
    checkRandomMeasurements()
    checkLargePageMapping()
    checkInvalidMeasurements()
    println("Scroll geometry checks passed: boundaries, image/header updates, 1,500 randomized cases, 100,017-page linear walk")
}
