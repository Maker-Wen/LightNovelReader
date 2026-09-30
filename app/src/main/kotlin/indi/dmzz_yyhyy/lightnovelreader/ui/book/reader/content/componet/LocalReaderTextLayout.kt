package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextLayoutResult

/** The scrolling reader uses the displayed text layout to restore source-character anchors. */
val LocalReaderTextLayout = staticCompositionLocalOf<(TextLayoutResult) -> Unit> { {} }
