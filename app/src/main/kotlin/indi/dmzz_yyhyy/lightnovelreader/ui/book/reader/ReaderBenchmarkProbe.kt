package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.os.Trace

/** Optional benchmark observers. Call sites are guarded by BuildConfig.BENCHMARK. */
object ReaderBenchmarkProbe {
    @Volatile
    var beforeChapterLoad: (suspend (bookId: String, chapterId: String) -> Unit)? = null

    @Volatile
    var onMarkerFrame: ((ReaderMarkerFrame) -> Unit)? = null

    @Volatile
    var onImageSize: ((bookId: String, chapterId: String, componentIndex: Int, heightPx: Int) -> Unit)? = null

    fun beginChapterFlow(chapterId: String, priority: String) {
        Trace.beginSection("ReaderChapterFlow:$priority:$chapterId".take(127))
    }

    fun beginChapterProcessor(chapterId: String, processor: String) {
        Trace.beginSection("ReaderProcessChapter:$chapterId:$processor".take(127))
    }

    fun endSection() {
        Trace.endSection()
    }
}

/** Values from a completed Compose draw, with marker coordinates local to the progress row. */
data class ReaderMarkerFrame(
    val value: Float,
    val displayedOrigin: Float?,
    val formalOrigin: Float?,
    val gestureOrigin: Float?,
    val isPreviewing: Boolean,
    val enabled: Boolean,
    val trackWidthPx: Float,
    val markerCenterXPx: Float?,
)
