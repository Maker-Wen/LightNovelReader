package indi.dmzz_yyhyy.lightnovelreader.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookRecordEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookshelfBookMetadataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookshelfEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.DailyCountEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserReadingDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.VolumeEntity
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.Count
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.paragraph
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import io.nightfish.lightnovelreader.api.userdata.BooleanUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking

class BenchmarkFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val result = when (intent.action) {
                    ACTION_SEEK_PROBE -> ReaderSeekBenchmarkController.handle(intent)

                    ACTION_SEED -> {
                        val paragraphCount = intent.getIntExtra("paragraphCount", PROGRESS_PARAGRAPH_COUNT)
                            .coerceIn(1, 1000)
                        runBlocking {
                            seed(
                                LightNovelReaderDatabase.getInstance(context),
                                paragraphCount,
                                singleChapter = intent.getBooleanExtra("singleChapter", false),
                                imageUri = intent.getStringExtra("imageUri")?.takeIf { it.isNotBlank() },
                                simplifiedTraditional = intent.getBooleanExtra("simplifiedTraditional", false),
                                paged = intent.getBooleanExtra("paged", false),
                                popupTitles = intent.getBooleanExtra("popupTitles", false),
                            )
                        }
                        "seed=SUCCEEDED"
                    }

                    ACTION_REEMIT_CHAPTER -> {
                        runBlocking {
                            val database = LightNovelReaderDatabase.getInstance(context)
                            database.chapterContentDao().get(CHAPTER_ONE_ID)?.let {
                                database.chapterContentDao().update(it)
                            }
                        }
                        "reemit=SUCCEEDED"
                    }

                    ACTION_REPORT_PROGRESS -> {
                        val chapterId = intent.getStringExtra("chapterId") ?: CHAPTER_ONE_ID
                        val (readingData, locationHash) = runBlocking {
                            val database = LightNovelReaderDatabase.getInstance(context)
                            database.userReadingDataDao().getEntity(BOOK_ID) to
                                database.userDataDao().get("reader.reading_location.$BOOK_ID.$chapterId")
                        }
                        val progress = readingData?.currentChapterReadingProgressMap?.get(chapterId)
                        val maxProgress = readingData?.maxChapterReadingProgressMap?.get(chapterId)
                        "progress=${progress ?: -1f},maxProgress=${maxProgress ?: -1f},locationHash=$locationHash"
                    }

                    ACTION_EXTEND_RAPID_CHAPTER_CHAIN -> {
                        runBlocking {
                            extendRapidChapterChain(LightNovelReaderDatabase.getInstance(context))
                        }
                        "rapid-chapters=SUCCEEDED"
                    }

                    ACTION_EMPTY_LAST_CHAPTER -> {
                        runBlocking {
                            val dao = LightNovelReaderDatabase.getInstance(context).chapterContentDao()
                            val chapter = requireNotNull(dao.get(CHAPTER_TWO_ID))
                            dao.update(chapter.copy(content = ContentBuilder().build()))
                        }
                        "empty-last-chapter=SUCCEEDED"
                    }

                    else -> "unsupported-action=${intent.action}"
                }
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = result
            } catch (throwable: Throwable) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData =
                    "exception=${throwable::class.java.simpleName}:${throwable.message}"
            } finally {
                pending.finish()
            }
        }.start()
    }

    private suspend fun seed(
        database: LightNovelReaderDatabase,
        paragraphCount: Int,
        singleChapter: Boolean,
        imageUri: String?,
        simplifiedTraditional: Boolean,
        paged: Boolean,
        popupTitles: Boolean,
    ) {
        if (simplifiedTraditional) {
            BooleanUserData(
                UserDataPath.Reader.EnableSimplifiedTraditionalTransform.path,
                database.userDataDao(),
            ).set(true)
        }
        if (paged) {
            BooleanUserData(UserDataPath.Reader.IsUsingFlipPage.path, database.userDataDao()).set(true)
        }
        val firstTitle = if (popupTitles) "短章" else "Benchmark Chapter One"
        val secondTitle = if (popupTitles) {
            "这是用于验证阅读进度浮窗宽高与居中位置在超长章节名称下保持稳定的第二章标题"
        } else "Benchmark Chapter Two"
        val now = LocalDateTime.now()
        val book = BookInformationEntity(
            id = BOOK_ID,
            title = "Benchmark Sample Novel",
            subtitle = "A deterministic local test fixture",
            coverUri = Uri.EMPTY,
            author = "Benchmark Author",
            description = "Local content used to exercise every book and reader workflow without network access.",
            tags = listOf("Benchmark", "Local", "Automation"),
            publishingHouse = "Benchmark Press",
            wordCount = WordCount(12_345),
            lastUpdated = now.minusDays(1),
            isComplete = false,
        )
        database.bookInformationDao().insert(book)
        database.bookInformationDao().insert(
            BookInformationEntity(
                id = SECOND_BOOK_ID,
                title = "Second Benchmark Novel",
                subtitle = "Independent progress restoration fixture",
                coverUri = Uri.EMPTY,
                author = "Second Benchmark Author",
                description = "A second local book used to prove that chapters and progress do not leak between books.",
                tags = listOf("Benchmark", "Progress"),
                publishingHouse = "Benchmark Press",
                wordCount = WordCount(9_876),
                lastUpdated = now.minusDays(2),
                isComplete = false,
            )
        )

        if (singleChapter) {
            // Tests can reseed after the standard @Before fixture. Remove its second volume so
            // every progress-track destination is structurally guaranteed to stay in chapter one.
            database.bookVolumesDao().deleteByBookIds(listOf(BOOK_ID))
        }
        database.bookVolumesDao().insertVolume(
            VolumeEntity(
                bookId = BOOK_ID,
                volumeId = VOLUME_ID,
                volumeTitle = "Benchmark Volume",
                chapterIds = listOf(CHAPTER_ONE_ID),
                index = 0,
            )
        )
        if (!singleChapter) {
            database.bookVolumesDao().insertVolume(
                VolumeEntity(
                    bookId = BOOK_ID,
                    volumeId = SECOND_VOLUME_ID,
                    volumeTitle = "Benchmark Bonus Volume",
                    chapterIds = listOf(CHAPTER_TWO_ID),
                    index = 1,
                )
            )
        }
        database.bookVolumesDao().insertChapterInformationEntities(
            ChapterInformationEntity(CHAPTER_ONE_ID, firstTitle),
            ChapterInformationEntity(CHAPTER_TWO_ID, secondTitle),
        )
        database.bookVolumesDao().insertVolume(
            VolumeEntity(
                bookId = SECOND_BOOK_ID,
                volumeId = SECOND_BOOK_VOLUME_ID,
                volumeTitle = "Second Benchmark Volume",
                chapterIds = listOf(SECOND_BOOK_CHAPTER_ONE_ID, SECOND_BOOK_CHAPTER_TWO_ID),
                index = 0,
            )
        )
        database.bookVolumesDao().insertChapterInformationEntities(
            ChapterInformationEntity(SECOND_BOOK_CHAPTER_ONE_ID, "Second Book Chapter One"),
            ChapterInformationEntity(SECOND_BOOK_CHAPTER_TWO_ID, "Second Book Chapter Two"),
        )

        val firstContent = ContentBuilder().apply {
            if (imageUri != null) {
                paragraph { text("Benchmark before delayed image.") }
                component(ImageComponentData(Uri.parse(imageUri)))
                paragraph { text("Benchmark after delayed image.") }
            }
            appendProgressParagraphs("Benchmark progress paragraph", paragraphCount, simplifiedTraditional)
        }
            .paragraph { text(CHAPTER_ONE_END_MARKER) }
            .build()
        val secondContent = ContentBuilder()
            .paragraph { text(CHAPTER_TWO_START_MARKER) }
            .apply {
                appendProgressParagraphs("Benchmark chapter two progress paragraph", paragraphCount, simplifiedTraditional)
            }
            .build()
        database.chapterContentDao().update(
            ChapterContentEntity(
                id = CHAPTER_ONE_ID,
                title = firstTitle,
                content = firstContent,
                prevChapter = "",
                nextChapter = if (singleChapter) "" else CHAPTER_TWO_ID,
            )
        )
        database.chapterContentDao().update(
            ChapterContentEntity(
                id = CHAPTER_TWO_ID,
                title = secondTitle,
                content = secondContent,
                prevChapter = CHAPTER_ONE_ID,
                nextChapter = "",
            )
        )
        val secondBookFirstContent = ContentBuilder().apply {
            appendProgressParagraphs("Second book chapter one progress paragraph", paragraphCount)
        }.build()
        val secondBookSecondContent = ContentBuilder().apply {
            appendProgressParagraphs("Second book chapter two progress paragraph", paragraphCount)
        }.build()
        database.chapterContentDao().update(
            ChapterContentEntity(
                id = SECOND_BOOK_CHAPTER_ONE_ID,
                title = "Second Book Chapter One",
                content = secondBookFirstContent,
                prevChapter = "",
                nextChapter = SECOND_BOOK_CHAPTER_TWO_ID,
            )
        )
        database.chapterContentDao().update(
            ChapterContentEntity(
                id = SECOND_BOOK_CHAPTER_TWO_ID,
                title = "Second Book Chapter Two",
                content = secondBookSecondContent,
                prevChapter = SECOND_BOOK_CHAPTER_ONE_ID,
                nextChapter = "",
            )
        )

        database.userReadingDataDao().insert(
            UserReadingDataEntity(
                id = BOOK_ID,
                lastReadTime = now,
                totalReadTime = 3_600,
                readingProgress = if (imageUri != null || popupTitles) 0f else 0.25f,
                lastReadChapterId = CHAPTER_ONE_ID,
                lastReadChapterTitle = firstTitle,
                currentChapterReadingProgressMap = mapOf(CHAPTER_ONE_ID to if (imageUri != null || popupTitles) 0f else 0.25f),
                maxChapterReadingProgressMap = mapOf(CHAPTER_ONE_ID to 0.5f),
            )
        )
        database.userReadingDataDao().insert(
            UserReadingDataEntity(
                id = SECOND_BOOK_ID,
                lastReadTime = now.minusHours(1),
                totalReadTime = 1_800,
                readingProgress = 0.7f,
                lastReadChapterId = SECOND_BOOK_CHAPTER_TWO_ID,
                lastReadChapterTitle = "Second Book Chapter Two",
                currentChapterReadingProgressMap = mapOf(
                    SECOND_BOOK_CHAPTER_ONE_ID to 0.15f,
                    SECOND_BOOK_CHAPTER_TWO_ID to 0.7f,
                ),
                maxChapterReadingProgressMap = mapOf(
                    SECOND_BOOK_CHAPTER_ONE_ID to 0.4f,
                    SECOND_BOOK_CHAPTER_TWO_ID to 0.8f,
                ),
            )
        )
        database.userDataDao().insert(
            UserDataEntity(
                path = "reading_books",
                group = "",
                type = "StringList",
                value = "$BOOK_ID,$SECOND_BOOK_ID",
            )
        )
        database.userDataDao().insert(
            UserDataEntity(
                path = "bookshelf_order",
                group = "",
                type = "StringList",
                value = BOOKSHELF_ID.toString(),
            )
        )
        database.bookshelfDao().insertBookshelf(
            BookshelfEntity(
                id = BOOKSHELF_ID,
                name = "Benchmark Shelf",
                sortType = "default",
                sortReversed = false,
                autoCache = false,
                systemUpdateReminder = false,
                allBookIds = listOf(BOOK_ID, SECOND_BOOK_ID),
                pinnedBookIds = emptyList(),
                updatedBookIds = listOf(BOOK_ID, SECOND_BOOK_ID),
            )
        )
        database.bookshelfDao().insertBookshelfBookMetadata(
            BookshelfBookMetadataEntity(
                id = BOOK_ID,
                lastUpdate = now,
                bookShelfIds = listOf(BOOKSHELF_ID),
            )
        )
        database.bookshelfDao().insertBookshelfBookMetadata(
            BookshelfBookMetadataEntity(
                id = SECOND_BOOK_ID,
                lastUpdate = now.minusHours(1),
                bookShelfIds = listOf(BOOKSHELF_ID),
            )
        )

        val today = LocalDate.now()
        database.bookRecordDao().insertBookRecord(
            BookRecordEntity(
                bookId = BOOK_ID,
                date = today,
                reads = 2,
                seconds = 3_600,
                isFavorited = true,
                firstSeen = LocalTime.of(9, 0),
                lastSeen = LocalTime.of(10, 0),
            )
        )
        val count = Count().apply {
            setMinute(9, 30)
            setMinute(10, 30)
        }
        database.dailyCountDao().insert(DailyCountEntity(today, count))
    }

    private fun ContentBuilder.appendProgressParagraphs(
        prefix: String,
        paragraphCount: Int = PROGRESS_PARAGRAPH_COUNT,
        simplifiedTraditional: Boolean = false,
    ) {
        val conversionMarker = if (simplifiedTraditional) "汉语测试。 " else ""
        repeat(paragraphCount) { index ->
            paragraph {
                text(
                    "$prefix ${index + 1}. ${conversionMarker}This deterministic component exercises layout, " +
                        "scrolling, pagination, progress restoration, and formatting."
                )
            }
        }
    }

    private suspend fun extendRapidChapterChain(database: LightNovelReaderDatabase) {
        val extraChapterIds = (3..12).map { "benchmark-chapter-$it" }
        database.bookVolumesDao().insertVolume(
            VolumeEntity(
                bookId = BOOK_ID,
                volumeId = SECOND_VOLUME_ID,
                volumeTitle = "Benchmark Bonus Volume",
                chapterIds = listOf(CHAPTER_TWO_ID) + extraChapterIds,
                index = 1,
            )
        )
        database.bookVolumesDao().insertChapterInformationEntities(
            *extraChapterIds.mapIndexed { index, id ->
                ChapterInformationEntity(id, "Benchmark Chapter ${index + 3}")
            }.toTypedArray()
        )

        database.chapterContentDao().get(CHAPTER_TWO_ID)?.let { chapterTwo ->
            database.chapterContentDao().update(
                chapterTwo.copy(nextChapter = extraChapterIds.first())
            )
        }
        extraChapterIds.forEachIndexed { index, id ->
            val chapterNumber = index + 3
            val content = ContentBuilder()
                .paragraph { text("Benchmark rapid chapter $chapterNumber start marker.") }
                .apply { appendProgressParagraphs("Benchmark rapid chapter $chapterNumber paragraph") }
                .build()
            database.chapterContentDao().update(
                ChapterContentEntity(
                    id = id,
                    title = "Benchmark Chapter $chapterNumber",
                    content = content,
                    prevChapter = if (index == 0) CHAPTER_TWO_ID else extraChapterIds[index - 1],
                    nextChapter = extraChapterIds.getOrNull(index + 1).orEmpty(),
                )
            )
        }
    }

    companion object {
        const val ACTION_SEEK_PROBE = "indi.dmzz_yyhyy.lightnovelreader.benchmark.SEEK_PROBE"
        const val ACTION_SEED = "indi.dmzz_yyhyy.lightnovelreader.benchmark.SEED"
        const val ACTION_REEMIT_CHAPTER =
            "indi.dmzz_yyhyy.lightnovelreader.benchmark.REEMIT_CHAPTER"
        const val ACTION_REPORT_PROGRESS =
            "indi.dmzz_yyhyy.lightnovelreader.benchmark.REPORT_PROGRESS"
        const val ACTION_EXTEND_RAPID_CHAPTER_CHAIN =
            "indi.dmzz_yyhyy.lightnovelreader.benchmark.EXTEND_RAPID_CHAPTER_CHAIN"
        const val ACTION_EMPTY_LAST_CHAPTER =
            "indi.dmzz_yyhyy.lightnovelreader.benchmark.EMPTY_LAST_CHAPTER"

        const val BOOK_ID = "9999999"
        const val SECOND_BOOK_ID = "9999998"
        const val VOLUME_ID = "benchmark-volume"
        const val SECOND_VOLUME_ID = "benchmark-volume-2"
        const val CHAPTER_ONE_ID = "benchmark-chapter-1"
        const val CHAPTER_TWO_ID = "benchmark-chapter-2"
        const val SECOND_BOOK_VOLUME_ID = "second-benchmark-volume"
        const val SECOND_BOOK_CHAPTER_ONE_ID = "second-benchmark-chapter-1"
        const val SECOND_BOOK_CHAPTER_TWO_ID = "second-benchmark-chapter-2"
        const val CHAPTER_ONE_END_MARKER = "Benchmark chapter one end marker."
        const val CHAPTER_TWO_START_MARKER = "Benchmark chapter two start marker."
        const val BOOKSHELF_ID = 1_000_001
        const val PROGRESS_PARAGRAPH_COUNT = 30
    }
}
