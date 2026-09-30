package io.nightfish.lightnovelreader.source.linovelib

import org.jsoup.Connection

internal fun Connection.acceptLinovelibContentTypes(): Connection =
    ignoreContentType(true)
