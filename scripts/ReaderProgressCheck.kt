import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderProgressChapter
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderProgressMap
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.readerScrollPageIndexKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterPosition
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.singlePageNavigationFraction
import kotlin.math.abs

fun main() {
    val chapters = mutableListOf(
        ReaderProgressChapter("short", "Short", 1),
        ReaderProgressChapter("long", "Long", 9)
    )
    val map = ReaderProgressMap(chapters)
    chapters.clear()
    check(map.chapters.size == 2 && map.title("long") == "Long")
    check(map.title("missing").isEmpty())
    check(runCatching { (map.chapters as MutableList).clear() }.isFailure)
    check(map.target(0f) == ReaderPosition("short", 0f))
    check(map.target(1f) == ReaderPosition("long", 1f))
    check(map.target(.1f) == ReaderPosition("long", 0f))
    check(abs(map.target(.55f)!!.fraction - .5f) < .000001f)
    check(map.target(map.progress(ReaderPosition("short", 1f))) == ReaderPosition("long", 0f))
    for (step in 0..1000) {
        val value = step / 1000f
        check(abs(map.progress(map.target(value)!!) - value) < .000001f)
    }

    val mixed = ReaderProgressMap(listOf(
        ReaderProgressChapter("a", "", 2),
        ReaderProgressChapter("b", "", null),
        ReaderProgressChapter("c", "", 0),
        ReaderProgressChapter("d", "", 10)
    ))
    // The two unknown lengths each use six pages: at page 12, c is two thirds read.
    check(mixed.target(.5f)!!.let { it.chapterId == "c" && abs(it.fraction - 2f / 3) < .000001f })
    check(mixed.target(mixed.progress(ReaderPosition("d", 0f))) == ReaderPosition("d", 0f))
    val rounded = ReaderProgressMap(listOf(
        ReaderProgressChapter("a", "", 3), ReaderProgressChapter("b", "", 12),
        ReaderProgressChapter("c", "", 985)
    ))
    check(rounded.target(rounded.progress(ReaderPosition("b", 1f))) == ReaderPosition("c", 0f))
    val equal = ReaderProgressMap(listOf(
        ReaderProgressChapter("a", "", null), ReaderProgressChapter("b", "", -1)
    ))
    check(equal.target(.5f) == ReaderPosition("b", 0f))
    val reordered = ReaderProgressMap(map.chapters.reversed())
    check(reordered.target(0f) == ReaderPosition("long", 0f))
    check(map.target(0f) == ReaderPosition("short", 0f))

    val single = ReaderProgressMap(listOf(ReaderProgressChapter("one", "", 1)))
    check(single.target(0f) == ReaderPosition("one", 0f))
    check(single.target(1f) == ReaderPosition("one", 1f))
    check(single.target(.5f) == ReaderPosition("one", .5f))
    for (fraction in listOf(0f, .5f, 1f)) {
        check(singlePageNavigationFraction(ChapterPosition.Relative(fraction), 1f) == fraction)
        check(singlePageNavigationFraction(ChapterPosition.Exact(ReaderPosition("one", fraction)), 0f) == fraction)
    }
    check(singlePageNavigationFraction(null, 0f) == 0f)
    check(singlePageNavigationFraction(null, .9f) == 0f)
    check(singlePageNavigationFraction(null, 1f) == 1f)
    check(singlePageNavigationFraction(null, Float.NaN) == 0f)
    check(singlePageNavigationFraction(ChapterPosition.Relative(Float.NaN), 0f) == 0f)
    check(singlePageNavigationFraction(ChapterPosition.Relative(2f), 0f) == 1f)
    check(single.progress(ReaderPosition("one", singlePageNavigationFraction(ChapterPosition.Relative(1f), 0f))) == 1f)
    val empty = ReaderProgressMap(emptyList())
    check(empty.isEmpty && empty.target(.5f) == null)
    check(empty.progress(ReaderPosition("missing", .5f)) == 0f)
    check(map.progress(ReaderPosition("missing", .5f)) == 0f)
    check(map.target(Float.NaN) == map.target(0f))
    check(map.target(-0f) == map.target(0f))
    check(map.target(Float.NEGATIVE_INFINITY) == map.target(0f))
    check(map.target(Float.POSITIVE_INFINITY) == map.target(1f))
    check(map.progress(ReaderPosition("long", Float.NaN)) == .1f)
    check(map.progress(ReaderPosition("long", Float.POSITIVE_INFINITY)) == 1f)

    val bodyKey = "a".repeat(64)
    val title = "第一章：开始"
    val pageIndexKey = readerScrollPageIndexKey(bodyKey, title)
    check(pageIndexKey == readerScrollPageIndexKey(bodyKey, title))
    check(pageIndexKey != bodyKey) // Old scroll indices only stored the body key.
    check(pageIndexKey != readerScrollPageIndexKey(bodyKey, "$title\n新的长标题"))
    check(pageIndexKey != readerScrollPageIndexKey("b".repeat(64), title))
    check(readerScrollPageIndexKey("a", "bc") != readerScrollPageIndexKey("ab", "c"))

    checkReaderSeekGesture()
    println("Reader progress, page index identity and seek gesture checks passed")
}
