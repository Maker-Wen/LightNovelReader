package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import indi.dmzz_yyhyy.lightnovelreader.LightNovelReaderApplication
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookRecordEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookshelfEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.FormattingRuleEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.VolumeEntity
import indi.dmzz_yyhyy.lightnovelreader.data.storage.StorageUsageRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.MutableWebDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.NotFoundWebDataSource
import indi.dmzz_yyhyy.lightnovelreader.utils.writeAppLocalData
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Real Room and archive integration checks. Every test uses an in-memory
 * database and its own files directory; installed app user data is untouched.
 */
@OptIn(ExperimentalSerializationApi::class)
@RunWith(AndroidJUnit4::class)
class LocalDataBackupTest {
    private val currentId = Identifier("backup_fixture", "active")
    private val otherId = Identifier("backup_fixture", "inactive")
    private val removedId = Identifier("backup_fixture", "removed")
    private lateinit var directory: File
    private lateinit var isolatedContext: Context
    private lateinit var database: LightNovelReaderDatabase
    private lateinit var provider: MutableWebDataSourceProvider
    private lateinit var manager: LocalDataManager
    private lateinit var operations: LocalDataOperationCoordinator

    @Before
    fun setUp() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as LightNovelReaderApplication
        directory = File(application.cacheDir, "local-data-test-${UUID.randomUUID()}")
            .also { check(it.mkdirs()) }
        val context = object : ContextWrapper(application) {
            override fun getDataDir(): File = directory
            override fun getFilesDir(): File = directory.resolve("files").also { it.mkdirs() }
            override fun getCacheDir(): File = directory.resolve("cache").also { it.mkdirs() }
            override fun getDatabasePath(name: String): File =
                directory.resolve("databases/$name").also { it.parentFile?.mkdirs() }
        }
        isolatedContext = context
        database = Room.inMemoryDatabaseBuilder(
            application,
            LightNovelReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        provider = MutableWebDataSourceProvider().apply {
            update(NotFoundWebDataSource(currentId))
        }
        val storage = StorageUsageRepository(
            context,
            database,
            application.pluginManager,
            UserDataRepository(database.userDataDao())
        )
        operations = LocalDataOperationCoordinator(context, database)
        manager = LocalDataManager(
            context = context,
            webDataSourceProvider = provider,
            bookBookInformationDao = database.bookInformationDao(),
            bookRecordDao = database.bookRecordDao(),
            dailyCountDao = database.dailyCountDao(),
            bookshelfDao = database.bookshelfDao(),
            chapterContentDao = database.chapterContentDao(),
            bookVolumesDao = database.bookVolumesDao(),
            formattingRuleDao = database.formattingRuleDao(),
            userReadingDataDao = database.userReadingDataDao(),
            userDataDao = database.userDataDao(),
            storageUsageRepository = storage,
            database = database,
            operations = operations
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    @Test
    fun mergeKeepsNewBooksAndRepeatedImportsDoNotIncreaseStatistics() = runBlocking {
        importSnapshot(snapshot(currentId, "existing"), snapshot(otherId, "existing"))
        val backup = appData(
            snapshot(currentId, "existing", "incoming"),
            snapshot(otherId, "existing", "incoming")
        )
        manager.importAppLocalData(backup).requireSuccess()
        manager.importAppLocalData(backup).requireSuccess()

        val exported = manager.exportAppLocalData().requireSuccess()
        for (sourceId in listOf(currentId, otherId)) {
            val source = exported.localDataList.single { it.webBookDataSourceId == sourceId }
            assertEquals(setOf("existing", "incoming"), source.bookInformationEntities.map { it.id }.toSet())
            assertEquals(2, source.bookRecordEntities.single { it.bookId == "existing" }.reads)
            assertEquals(120, source.bookRecordEntities.single { it.bookId == "existing" }.seconds)
            assertEquals(listOf("existing", "incoming"), source.bookshelfEntities.single().allBookIds)
        }
    }

    @Test
    fun exportUsesCurrentDatabaseOnceAndFiltersInactiveSourcesToo() = runBlocking {
        importSnapshot(snapshot(currentId, "live"))
        writeLegacySource(snapshot(currentId, "stale"), zip = true)
        writeLegacySource(snapshot(otherId, "other"), zip = false)

        val full = manager.exportAppLocalData().requireSuccess()
        assertEquals(2, full.localDataList.size)
        assertEquals(listOf("live"), full.localDataList.single {
            it.webBookDataSourceId == currentId
        }.bookInformationEntities.map { it.id })

        val shelvesOnly = manager.exportAppLocalData(
            localBookCache = false,
            bookshelf = true,
            readingRecord = false,
            settings = false
        ).requireSuccess()
        for (source in shelvesOnly.localDataList) {
            assertTrue(source.bookInformationEntities.isEmpty())
            assertTrue(source.bookRecordEntities.isEmpty())
            assertTrue(source.dailyCountEntities.isEmpty())
            assertTrue(source.userReadingDataEntities.isEmpty())
            assertTrue(source.formattingRuleEntities.isEmpty())
            assertFalse(source.bookshelfEntities.isEmpty())
        }
        assertTrue(shelvesOnly.globalLocalData.userDataEntities.isEmpty())

        // The live DB remains authoritative even if its older on-disk snapshot is damaged.
        directory.resolve("local_data/$currentId").writeBytes(byteArrayOf(0, 1, 2, 3))
        assertEquals(listOf("live"), manager.exportAppLocalData().requireSuccess().localDataList.single {
            it.webBookDataSourceId == currentId
        }.bookInformationEntities.map { it.id })
        importSnapshot(snapshot(currentId, "incoming"))
        assertEquals(setOf("live", "incoming"), manager.exportAppLocalData().requireSuccess().localDataList.single {
            it.webBookDataSourceId == currentId
        }.bookInformationEntities.map { it.id }.toSet())
    }

    @Test
    fun legacyZipAndRawCborSnapshotsSurviveMergeAndExport() = runBlocking {
        writeLegacySource(snapshot(otherId, "zip-book"), zip = true)
        writeLegacySource(snapshot(removedId, "raw-book"), zip = false)
        importSnapshot(snapshot(otherId, "new-book"))

        val exported = manager.exportAppLocalData().requireSuccess()
        assertEquals(setOf("zip-book", "new-book"), exported.localDataList.single {
            it.webBookDataSourceId == otherId
        }.bookInformationEntities.map { it.id }.toSet())
        assertEquals(listOf("raw-book"), exported.localDataList.single {
            it.webBookDataSourceId == removedId
        }.bookInformationEntities.map { it.id })
    }

    @Test
    fun unsupportedOverwriteVersionKeepsDatabaseAndFiles() = runBlocking {
        importSnapshot(snapshot(currentId, "original"), snapshot(otherId, "original"))
        val before = archiveBytes()
        val result = manager.importAppLocalData(
            appData(snapshot(currentId, "replacement")).copy(version = Int.MAX_VALUE),
            overwrite = true
        )

        assertNotNull(result.component2())
        assertNotNull(database.bookInformationDao().getEntity("original"))
        assertNull(database.bookInformationDao().getEntity("replacement"))
        assertEquals(before, archiveBytes())
    }

    @Test
    fun overwriteReplacesOldSourcesGlobalSettingsAndFormattingRules() = runBlocking {
        val old = appData(
            snapshot(currentId, "original"),
            snapshot(otherId, "inactive-original"),
            snapshot(removedId, "to-remove")
        ).copy(globalLocalData = LocalData.empty().copy(userDataEntities = listOf(setting("fixture.old", "old"))))
        manager.importAppLocalData(old).requireSuccess()
        val replacement = appData(
            snapshot(currentId, "replacement"),
            snapshot(otherId, "inactive-new")
        ).copy(globalLocalData = LocalData.empty().copy(userDataEntities = listOf(setting("fixture.new", "new"))))
        manager.importAppLocalData(replacement, overwrite = true).requireSuccess()

        val exported = manager.exportAppLocalData().requireSuccess()
        assertEquals(setOf(currentId, otherId), exported.localDataList.map { it.webBookDataSourceId }.toSet())
        assertEquals(listOf("replacement"), database.bookInformationDao().getAllEntities().map { it.id })
        assertEquals(listOf("replacement"), database.formattingRuleDao().getAllBookRuleEntity().map { it.bookId })
        assertNull(database.userDataDao().get("fixture.old"))
        assertEquals("new", database.userDataDao().get("fixture.new"))
    }

    @Test
    fun failedDatabaseRestoreRollsBackBothDatabaseAndSourceArchives() = runBlocking {
        importSnapshot(snapshot(currentId, "original"), snapshot(otherId, "inactive-original"))
        val before = archiveBytes()
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_fixture BEFORE INSERT ON book_information
                WHEN NEW.id = 'reject' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END"""
        )

        val result = manager.importAppLocalData(
            appData(snapshot(currentId, "reject"), snapshot(otherId, "inactive-new")),
            overwrite = true
        )
        assertNotNull(result.component2())
        assertNotNull(database.bookInformationDao().getEntity("original"))
        assertNull(database.bookInformationDao().getEntity("reject"))
        assertEquals(before, archiveBytes())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_fixture")
        val exported = manager.exportAppLocalData().requireSuccess()
        assertEquals(listOf("inactive-original"), exported.localDataList.single {
            it.webBookDataSourceId == otherId
        }.bookInformationEntities.map { it.id })
    }

    @Test
    fun corruptInactiveArchiveRejectsExportAndMergeWithoutChangingDatabase() = runBlocking {
        importSnapshot(snapshot(currentId, "original"))
        directory.resolve("local_data/$otherId").writeBytes(byteArrayOf(0, 1, 2, 3))
        val before = archiveBytes()

        assertNotNull(manager.exportAppLocalData().component2())
        assertNotNull(manager.importAppLocalData(appData(snapshot(currentId, "incoming"))).component2())
        assertNotNull(database.bookInformationDao().getEntity("original"))
        assertNull(database.bookInformationDao().getEntity("incoming"))
        assertEquals(before, archiveBytes())
    }

    @Test
    fun recoveryWithoutCommittedRoomTokenRestoresPreviousArchives() = runBlocking {
        importSnapshot(snapshot(currentId, "original"), snapshot(otherId, "inactive-original"))
        val before = archiveBytes()
        // Persist the state left by interruption between file apply and Room commit.
        val pending = operations.store.prepare(mapOf(otherId to snapshot(otherId, "uncommitted")))
        pending.apply()
        LocalDataOperationCoordinator(isolatedContext, database).recover()

        assertEquals(before, archiveBytes())
        assertFalse(operations.store.transactionDirectory.exists())
        assertNotNull(database.bookInformationDao().getEntity("original"))
        assertEquals(listOf("inactive-original"), manager.exportAppLocalData().requireSuccess().localDataList.single {
            it.webBookDataSourceId == otherId
        }.bookInformationEntities.map { it.id })
    }

    @Test
    fun recoveryWithCommittedRoomTokenKeepsNewArchivesAndCleansJournal() = runBlocking {
        importSnapshot(snapshot(currentId, "original"), snapshot(otherId, "inactive-original"))
        // Persist the state left by interruption after Room commit and before file cleanup.
        val pending = operations.store.prepare(mapOf(otherId to snapshot(otherId, "committed")))
        pending.apply()
        database.withTransaction {
            database.userDataDao().insert(setting(SourceSnapshotStore.COMMIT_MARKER_PATH, pending.id))
        }
        LocalDataOperationCoordinator(isolatedContext, database).recover()

        assertFalse(operations.store.transactionDirectory.exists())
        assertNull(database.userDataDao().get(SourceSnapshotStore.COMMIT_MARKER_PATH))
        assertNotNull(database.bookInformationDao().getEntity("original"))
        assertEquals(listOf("committed"), manager.exportAppLocalData().requireSuccess().localDataList.single {
            it.webBookDataSourceId == otherId
        }.bookInformationEntities.map { it.id })
    }

    @Test
    fun switchAwayAndBackPreservesBothSources() = runBlocking {
        importSnapshot(snapshot(currentId, "active-book"), snapshot(otherId, "inactive-book"))
        manager.switchSource(otherId).requireSuccess()
        provider.update(NotFoundWebDataSource(otherId))
        assertEquals(listOf("inactive-book"), database.bookInformationDao().getAllEntities().map { it.id })
        manager.switchSource(currentId).requireSuccess()
        provider.update(NotFoundWebDataSource(currentId))
        assertEquals(listOf("active-book"), database.bookInformationDao().getAllEntities().map { it.id })
        val exported = manager.exportAppLocalData().requireSuccess()
        assertEquals(setOf(currentId, otherId), exported.localDataList.map { it.webBookDataSourceId }.toSet())
        assertEquals(listOf("inactive-book"), exported.localDataList.single {
            it.webBookDataSourceId == otherId
        }.bookInformationEntities.map { it.id })
    }

    @Test
    fun inactiveSourceChapterAdditionsRemainReachableAfterSwitchingAndRepeatedImport() = runBlocking {
        fun cached(bookId: String, chapters: List<String>) = snapshot(otherId, bookId).copy(
            volumeEntities = listOf(VolumeEntity(bookId, "$bookId-volume", "Volume", chapters, 0)),
            chapterInformationEntities = chapters.map { ChapterInformationEntity(it, it) },
            chapterContentEntities = chapters.mapIndexed { index, id ->
                ChapterContentEntity(id, id, JsonObject(emptyMap()),
                    chapters.getOrNull(index - 1).orEmpty(), chapters.getOrNull(index + 1).orEmpty())
            }
        )
        val partialBodies = cached("body-book", listOf("b1", "b2")).let { data ->
            data.copy(chapterContentEntities = listOf(data.chapterContentEntities.first().copy(nextChapter = "")))
        }
        importSnapshot(snapshot(currentId, "active"), cached("book", listOf("c1", "c3")), partialBodies)
        val backup = appData(cached("book", listOf("c1", "c2", "c3")), cached("body-book", listOf("b1", "b2")))
        manager.importAppLocalData(backup).requireSuccess()
        manager.importAppLocalData(backup).requireSuccess()
        manager.switchSource(otherId).requireSuccess()
        provider.update(NotFoundWebDataSource(otherId))

        val directory = database.bookVolumesDao().getBookVolumes("book")!!
        assertEquals(listOf("c1", "c2", "c3"), directory.volumes.single().chapters.map { it.id })
        assertEquals("c2", database.chapterContentDao().get("c1")!!.nextChapter)
        assertEquals("c1", database.chapterContentDao().get("c2")!!.prevChapter)
        assertEquals("c3", database.chapterContentDao().get("c2")!!.nextChapter)
        assertEquals("c2", database.chapterContentDao().get("c3")!!.prevChapter)
        assertEquals("b2", database.chapterContentDao().get("b1")!!.nextChapter)
        assertEquals("b1", database.chapterContentDao().get("b2")!!.prevChapter)
    }

    @Test
    fun committedSourceSelectionLabelsExportsWhileProviderStillHasPreviousSource() = runBlocking {
        importSnapshot(snapshot(currentId, "active-book"), snapshot(otherId, "inactive-book"))
        manager.switchSource(otherId).requireSuccess()
        // Activity restart has not updated the source provider yet.
        assertEquals(currentId, provider.value.id)
        val active = manager.exportCurrentLocalData().requireSuccess()
        assertEquals(otherId, active.webBookDataSourceId)
        assertEquals(listOf("inactive-book"), active.bookInformationEntities.map { it.id })
        val exported = manager.exportAppLocalData().requireSuccess()
        assertEquals(2, exported.localDataList.size)
        assertEquals(listOf("active-book"), exported.localDataList.single {
            it.webBookDataSourceId == currentId
        }.bookInformationEntities.map { it.id })
        assertEquals(listOf("inactive-book"), exported.localDataList.single {
            it.webBookDataSourceId == otherId
        }.bookInformationEntities.map { it.id })
    }

    @Test
    fun concurrentImportsAndExportsKeepDatabaseAndInactiveSnapshotsConsistent() = runBlocking {
        importSnapshot(snapshot(currentId, "initial"), snapshot(otherId, "initial"))
        coroutineScope {
            (1..8).flatMap { index ->
                listOf(
                    async(Dispatchers.Default) {
                        val id = "concurrent-$index"
                        importSnapshot(snapshot(currentId, id), snapshot(otherId, id))
                    },
                    async(Dispatchers.Default) {
                        val exported = manager.exportAppLocalData().requireSuccess()
                        val books = exported.localDataList.associate {
                            it.webBookDataSourceId to it.bookInformationEntities.map { book -> book.id }.toSet()
                        }
                        assertEquals(books[currentId], books[otherId])
                    }
                )
            }.awaitAll()
        }
        val expectedIds = setOf("initial") + (1..8).map { "concurrent-$it" }
        val exported = manager.exportAppLocalData().requireSuccess()
        assertEquals(2, exported.localDataList.size)
        exported.localDataList.forEach { source ->
            assertEquals(expectedIds, source.bookInformationEntities.map { it.id }.toSet())
            assertEquals(expectedIds, source.bookshelfEntities.single().allBookIds.toSet())
        }
    }

    private suspend fun importSnapshot(vararg snapshots: LocalData) =
        manager.importAppLocalData(appData(*snapshots)).requireSuccess()

    private fun appData(vararg snapshots: LocalData) = AppLocalData(
        version = manager.currentAppDataVersion,
        localDataList = snapshots.toList(),
        globalLocalData = LocalData.empty()
    )

    private fun snapshot(sourceId: Identifier, vararg books: String): LocalData = LocalData.empty().copy(
        webBookDataSourceId = sourceId,
        bookInformationEntities = books.map(::book),
        bookRecordEntities = books.map { id ->
            BookRecordEntity(id, LocalDate.of(2026, 9, 30), 2, 120, firstSeen = LocalTime.NOON, lastSeen = LocalTime.of(12, 2))
        },
        bookshelfEntities = listOf(
            BookshelfEntity(1, "Fixture shelf", "Id", autoCache = false, systemUpdateReminder = false,
                allBookIds = books.toList(), pinnedBookIds = emptyList(), updatedBookIds = emptyList())
        ),
        formattingRuleEntities = books.mapIndexed { index, id ->
            FormattingRuleEntity(index + 1, id, "Fixture rule", false, "fixture", "replacement", true)
        }
    )

    private fun book(id: String) = BookInformationEntity(
        id, id, "", Uri.EMPTY, "Fixture", "", emptyList(), "", WordCount(1),
        LocalDateTime.of(2026, 9, 30, 12, 0), false
    )

    private fun setting(path: String, value: String) =
        UserDataEntity(path, path.substringBeforeLast('.'), "String", value)

    private fun writeLegacySource(data: LocalData, zip: Boolean) {
        val file = directory.resolve("local_data/${data.webBookDataSourceId}")
        file.parentFile?.mkdirs()
        val bytes = Cbor.encodeToByteArray(data)
        if (zip) file.outputStream().use { it.writeAppLocalData(bytes) }
        else file.writeBytes(bytes)
    }

    private fun archiveBytes(): Map<String, List<Byte>> = directory.resolve("local_data")
        .walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(directory).path to it.readBytes().toList() }

    private fun <T> Result<T, Throwable>.requireSuccess(): T = getOrElse { throw it }
}
