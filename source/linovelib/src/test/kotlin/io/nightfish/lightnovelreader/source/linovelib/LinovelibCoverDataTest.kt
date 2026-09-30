package io.nightfish.lightnovelreader.source.linovelib

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfBookMetadata
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessingRepositoryApi
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDateTime

class LinovelibCoverDataTest {
    @Test
    fun startupChecksEachReadingAndBookshelfBookOnceWithoutAccessingCatalogsOrProgress() {
        val calls = mutableListOf<String>()
        val processing = FakeTextProcessing()
        val data = LinovelibCoverData(
            localData(calls, "42", "43"), processing, bookshelves("42", "44")
        ) { true }

        data.onLoad()

        assertEquals(
            listOf("getAllUserReadingData", "getBookInformation:42", "getBookInformation:43", "getBookInformation:44"),
            calls
        )
        assertSame(data, processing.processors.single().second)
    }

    @Test
    fun directCoverRepairOnlyLooksUpBookInformation() = runBlocking {
        val calls = mutableListOf<String>()
        val data = LinovelibCoverData(localData(calls), FakeTextProcessing(), bookshelves()) { true }

        data.repairCover("42")

        assertEquals(listOf("getBookInformation:42"), calls)
    }

    @Test
    fun registeredProcessorPreservesCatalogAndContentAndFollowsTheActiveSource() {
        var active = true
        val calls = mutableListOf<String>()
        val processing = FakeTextProcessing()
        LinovelibCoverData(localData(calls), processing, bookshelves()) { active }.onLoad()
        calls.clear()
        val processor = processing.processors.single().second
        val volumes = BookVolumes(
            "42",
            listOf(
                Volume(
                    "42-1",
                    "Formatted volume title",
                    listOf(ChapterInformation("linovelib-v3:101", "Formatted chapter title"))
                )
            )
        )
        val json = buildJsonObject { put("custom", "keep all fields") }
        val chapter = ChapterContent("linovelib-v3:101", "Title", json, null, "linovelib-v3:102")
        val componentProcessor = ComponentProcessor(emptyMap(), emptyMap(), json)

        assertSame(volumes, processor.processBookVolumes(volumes))
        assertSame(chapter, processor.processChapterContent("42", chapter, componentProcessor))
        assertSame(json, chapter.content)
        assertSame(json, componentProcessor.content)
        assertEquals("文本", processor.processText("文本"))
        assertTrue(processor.enabled)
        active = false
        assertFalse(processor.enabled)
        assertTrue(processing.processors.none { it.second.enabled })
        active = true
        assertTrue(processor.enabled)
        assertTrue(calls.isEmpty())
    }

    private fun localData(calls: MutableList<String>, vararg readingIds: String): LocalBookDataSourceApi =
        Proxy.newProxyInstance(
            LocalBookDataSourceApi::class.java.classLoader,
            arrayOf(LocalBookDataSourceApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllUserReadingData" -> {
                    calls.add(method.name)
                    readingIds.map { UserReadingData(it) }
                }
                "getBookInformation" -> {
                    calls.add("${method.name}:${args!![0]}")
                    null
                }
                else -> error("Unexpected local data access: ${method.name}")
            }
        } as LocalBookDataSourceApi

    private fun bookshelves(vararg bookIds: String): BookshelfRepositoryApi = Proxy.newProxyInstance(
        BookshelfRepositoryApi::class.java.classLoader,
        arrayOf(BookshelfRepositoryApi::class.java)
    ) { _, method, _ ->
        check(method.name == "getAllBookshelfBooksMetadata") { "Unexpected bookshelf access: ${method.name}" }
        bookIds.map { BookshelfBookMetadata(it, LocalDateTime.MIN, listOf(1)) }
    } as BookshelfRepositoryApi

    private class FakeTextProcessing : TextProcessingRepositoryApi {
        val processors = mutableListOf<Pair<Identifier, TextProcessor>>()

        override fun registerProcessors(identifier: Identifier, processor: TextProcessor) {
            processors += identifier to processor
        }
    }
}
