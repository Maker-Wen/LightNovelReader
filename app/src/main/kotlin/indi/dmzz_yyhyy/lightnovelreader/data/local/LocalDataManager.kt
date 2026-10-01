package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.content.Context
import androidx.room.withTransaction
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.*
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.storage.StorageUsageRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.convertOldId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDataManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val webDataSourceProvider: WebBookDataSourceProvider,
    private val bookBookInformationDao: BookInformationDao,
    private val bookRecordDao: BookRecordDao,
    private val dailyCountDao: DailyCountDao,
    private val bookshelfDao: BookshelfDao,
    private val chapterContentDao: ChapterContentDao,
    private val bookVolumesDao: BookVolumesDao,
    private val formattingRuleDao: FormattingRuleDao,
    private val userReadingDataDao: UserReadingDataDao,
    private val userDataDao: UserDataDao,
    private val storageUsageRepository: StorageUsageRepository,
    private val database: LightNovelReaderDatabase,
    private val operations: LocalDataOperationCoordinator
) {
    companion object { const val TAG = "LocalDataManager" }

    val currentAppDataVersion = 0
    val localDataDir get() = operations.store.directory
    val webDataSourceUserDataPathSet = mutableSetOf<String>()

    fun registerWebDataSourceUserData(path: String) {
        webDataSourceUserDataPathSet.add(path)
    }

    suspend fun exportAppLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true
    ): Result<AppLocalData, Throwable> = attempt {
        operations.exclusive {
            val activeId = currentSourceId()
            // Read inactive snapshots first; the active archive is deliberately ignored.
            val snapshots = operations.store.readAll(setOf(activeId)).toMutableMap()
            val (active, global) = database.withTransaction {
                currentSnapshot(localBookCache, bookshelf, readingRecord, settings) to globalSnapshot()
            }
            snapshots[activeId] = active
            AppLocalData(
                version = currentAppDataVersion,
                localDataList = snapshots.values.map {
                    filterLocalData(it, localBookCache, bookshelf, readingRecord, settings)
                },
                globalLocalData = if (settings) global else LocalData.empty()
            )
        }
    }

    suspend fun exportCurrentLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true
    ): Result<LocalData, Throwable> = attempt {
        operations.exclusive {
            database.withTransaction {
                currentSnapshot(localBookCache, bookshelf, readingRecord, settings)
            }
        }
    }

    suspend fun importAppLocalData(
        appLocalData: AppLocalData,
        overwrite: Boolean = false
    ): Result<Unit, Throwable> = attempt {
        // Validate before touching the current database or any stored source archive.
        val backup = validateBackup(appLocalData)
        operations.exclusive {
            val activeId = currentSourceId()
            val incoming = backup.localDataList.associateBy { it.webBookDataSourceId!! }
            val saved = if (overwrite) emptyMap() else operations.store.readAll(setOf(activeId))
            val replacements = incoming.filterKeys { it != activeId }.mapValues { (id, value) ->
                saved[id]?.let { mergeLocalData(it, value) } ?: value
            }
            var activeToWrite: LocalData? = null
            var globalToWrite: LocalData? = null
            operations.commit(
                replaceAll = overwrite,
                files = {
                    val active = if (overwrite) {
                        incoming[activeId] ?: emptySource(activeId)
                    } else {
                        incoming[activeId]?.let { mergeLocalData(currentSnapshot(), it) }
                            ?: currentSnapshot()
                    }
                    val global = if (overwrite) backup.globalLocalData
                        else mergeLocalData(globalSnapshot(), backup.globalLocalData)
                    activeToWrite = active
                    globalToWrite = global
                    // Repair a stale active archive from the same authoritative DB snapshot.
                    if (overwrite) replacements else replacements + (activeId to active)
                },
                updateDatabase = {
                    clearSourceData()
                    if (overwrite) userDataDao.clear()
                    writeSnapshot(requireNotNull(activeToWrite))
                    writeSnapshot(requireNotNull(globalToWrite))
                    // Source selection describes the live DB, not whichever source a backup
                    // happened to be taken from. Keep it coherent until restart.
                    setSelectedSource(activeId)
                    userDataDao.remove(UserDataPath.Settings.Data.StorageUsageSnapshot.path)
                }
            )
            invalidateStorageSnapshot()
        }
    }

    suspend fun importLocalData(localData: LocalData): Result<Unit, Throwable> =
        importAppLocalData(AppLocalData(currentAppDataVersion, listOf(localData), LocalData.empty()))

    suspend fun importLocalDataToFile(localData: LocalData): Result<Unit, Throwable> =
        importLocalData(localData)

    suspend fun importLocalDataToDatabase(localData: LocalData): Result<Unit, Throwable> =
        if (localData.webBookDataSourceId == null) attempt {
            validateLocalData(localData, global = true)
            operations.exclusive {
                database.withTransaction { writeSnapshot(mergeLocalData(globalSnapshot(), localData)) }
            }
        } else importLocalData(localData)

    /** Saves the current source and restores the target as one recoverable operation. */
    suspend fun switchSource(target: Identifier): Result<Unit, Throwable> = attempt {
        val targetId = SourceSnapshotStore.normalizeSourceId(target)
        operations.exclusive {
            val activeId = currentSourceId()
            if (targetId != activeId) {
                val restored = operations.store.read(targetId) ?: emptySource(targetId)
                validateLocalData(restored, global = false)
                operations.commit(
                    files = { mapOf(activeId to currentSnapshot()) },
                    updateDatabase = {
                        clearSourceData()
                        writeSnapshot(restored)
                        setSelectedSource(targetId)
                        userDataDao.remove(UserDataPath.Settings.Data.StorageUsageSnapshot.path)
                    }
                )
                invalidateStorageSnapshot()
            }
        }
    }

    suspend fun cleanDatabaseWithoutGlobalUserData() = operations.exclusive {
        database.withTransaction { clearSourceData() }
        invalidateStorageSnapshot()
    }

    private fun currentSourceId(): Identifier {
        // The selected source is committed with the DB replacement. During the brief
        // restart handoff, the running provider may still point at the previous source.
        val selected = userDataDao.getEntity(UserDataPath.Settings.Data.WebDataSourceId.path)
            ?.value?.convertOldId() ?: webDataSourceProvider.value.id
        return SourceSnapshotStore.normalizeSourceId(selected)
    }

    private suspend fun currentSnapshot(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true
    ): LocalData {
        val option = ExportOptionLocalData(
            bookBookInformationDao, bookRecordDao, dailyCountDao, bookshelfDao,
            chapterContentDao, bookVolumesDao, formattingRuleDao, userReadingDataDao,
            userDataDao, webDataSourceUserDataPathSet
        ).apply {
            this.localBookCache.enable = localBookCache
            this.bookshelf.enable = bookshelf
            this.readingRecord.enable = readingRecord
            this.settings.enable = settings
        }
        option.solve()
        return LocalData(
            currentSourceId(), option.bookInformationEntities, option.bookRecordEntities,
            option.dailyCountEntities, option.bookshelfEntities, option.bookshelfBookMetadataEntities,
            option.chapterContentEntities, option.chapterInformationEntities, option.formattingRuleEntities,
            option.userDataEntities, option.userReadingDataEntities, option.volumeEntities
        )
    }

    private fun globalSnapshot() = LocalData.empty().copy(
        userDataEntities = userDataDao.getAllEntities().filter {
            it.path !in webDataSourceUserDataPathSet &&
                it.path != UserDataPath.Settings.Data.StorageUsageSnapshot.path &&
                it.path != SourceSnapshotStore.COMMIT_MARKER_PATH
        }
    )

    private suspend fun clearSourceData() {
        bookBookInformationDao.clear()
        bookRecordDao.clear()
        dailyCountDao.clear()
        bookshelfDao.clear()
        bookVolumesDao.clear()
        chapterContentDao.clear()
        formattingRuleDao.clear()
        userReadingDataDao.clear()
        for (path in webDataSourceUserDataPathSet) userDataDao.remove(path)
    }

    private suspend fun writeSnapshot(data: LocalData) {
        for (entity in data.bookInformationEntities) bookBookInformationDao.insert(entity)
        for (entity in data.bookRecordEntities) bookRecordDao.insertBookRecord(entity)
        for (entity in data.dailyCountEntities) dailyCountDao.insert(entity)
        for (entity in data.bookshelfEntities) bookshelfDao.insertBookshelf(entity)
        for (entity in data.bookshelfBookMetadataEntities) bookshelfDao.insertBookshelfBookMetadata(entity)
        for (entity in data.chapterContentEntities) chapterContentDao.update(entity)
        for (entity in data.chapterInformationEntities) bookVolumesDao.insertChapterInformationEntities(entity)
        for (entity in data.volumeEntities) bookVolumesDao.insertVolume(entity)
        for (entity in data.formattingRuleEntities) formattingRuleDao.update(entity)
        for (entity in data.userReadingDataEntities) userReadingDataDao.insert(entity)
        for (entity in data.userDataEntities) {
            if (entity.path != SourceSnapshotStore.COMMIT_MARKER_PATH &&
                entity.path != UserDataPath.Settings.Data.StorageUsageSnapshot.path &&
                entity.path != UserDataPath.Settings.Data.WebDataSourceId.path) userDataDao.insert(entity)
        }
    }

    private suspend fun setSelectedSource(id: Identifier) {
        userDataDao.insert(UserDataEntity(
            UserDataPath.Settings.Data.WebDataSourceId.path,
            UserDataPath.Settings.Data.WebDataSourceId.path.substringBeforeLast('.'),
            "String", id.toString()
        ))
    }

    private fun validateBackup(backup: AppLocalData): AppLocalData {
        require(backup.version == currentAppDataVersion) { "Unsupported backup version: ${backup.version}" }
        validateLocalData(backup.globalLocalData, global = true)
        val snapshots = linkedMapOf<Identifier, LocalData>()
        for (raw in backup.localDataList) {
            val snapshot = raw.copy(webBookDataSourceId = normalizeLocalDataSourceId(raw.webBookDataSourceId))
            validateLocalData(snapshot, global = false)
            val id = snapshot.webBookDataSourceId!!
            snapshots[id] = snapshots[id]?.let { mergeLocalData(it, snapshot) }
                ?: mergeLocalData(emptySource(id), snapshot)
        }
        return backup.copy(localDataList = snapshots.values.toList())
    }

    private fun validateLocalData(data: LocalData, global: Boolean) {
        if (global) {
            require(data.webBookDataSourceId == null) { "Global data must not have a source ID" }
            require(data.copy(userDataEntities = emptyList()) == LocalData.empty()) {
                "Global snapshot contains source-specific records"
            }
        } else {
            SourceSnapshotStore.normalizeSourceId(requireNotNull(data.webBookDataSourceId) {
                "Missing source ID"
            })
        }
        require(data.bookRecordEntities.all { it.reads >= 0 && it.seconds >= 0 }) { "Invalid reading counters" }
        require(data.userReadingDataEntities.all { row ->
            row.totalReadTime >= 0 && row.readingProgress.isFinite() && row.readingProgress in 0f..1f &&
                (row.currentChapterReadingProgressMap.values + row.maxChapterReadingProgressMap.values)
                    .all { it.isFinite() && it in 0f..1f }
        }) { "Invalid reading progress" }
    }

    private fun emptySource(id: Identifier) = LocalData.empty().copy(webBookDataSourceId = id)

    private suspend fun invalidateStorageSnapshot() {
        // Derived UI state must not turn a successful restore into a failed WorkManager job.
        try { storageUsageRepository.invalidateSnapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T, Throwable> = try {
        Ok(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Err(failure)
    }

    init {
        registerWebDataSourceUserData(UserDataPath.Settings.Data.WebDataSourceId.path)
        registerWebDataSourceUserData(UserDataPath.ReadingBooks.path)
        registerWebDataSourceUserData(UserDataPath.CompletedDownloadBookList.path)
        registerWebDataSourceUserData(UserDataPath.Search.History.path)
    }
}
