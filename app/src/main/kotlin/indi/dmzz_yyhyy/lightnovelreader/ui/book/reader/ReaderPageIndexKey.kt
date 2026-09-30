package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

/** Scroll page counts include the title; source anchors continue to use the body content key. */
internal fun readerScrollPageIndexKey(contentKey: String, title: String): String =
    "scroll-index-1:${contentKey.length}:$contentKey$title"
