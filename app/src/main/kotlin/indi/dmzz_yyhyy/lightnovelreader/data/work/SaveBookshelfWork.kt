package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.getOrElse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalDataManager
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.utils.writeAppLocalData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.FileOutputStream

@HiltWorker
class SaveBookshelfWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localDataManager: LocalDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "SaveBookshelfWork"
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        val id = inputData.getInt("bookshelfId", -1)
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val snapshot = localDataManager.exportCurrentLocalData(
            localBookCache = false, bookshelf = true, readingRecord = false, settings = false
        ).getOrElse {
            Log.e(TAG, "Failed to snapshot bookshelves", it)
            return Result.failure()
        }
        val bookshelfEntityList = snapshot.bookshelfEntities.filter { id == -1 || it.id == id }
        if (bookshelfEntityList.isEmpty() && id != -1) {
            Log.e(TAG, "Bookshelf doesn't exit (id=$id)")
            return Result.failure()
        }
        val bookshelfIds = bookshelfEntityList.map { it.id }
        val bookshelfBookMetadataEntities = mutableListOf<String>().apply {
            for (entity in bookshelfEntityList) {
                this.addAll(entity.allBookIds)
            }
        }.distinct()
            .let { bookIds -> snapshot.bookshelfBookMetadataEntities.filter { it.id in bookIds } }
            .map { entity ->
                entity.copy(
                    bookShelfIds = entity.bookShelfIds.filter { bookshelfIds.contains(it) }
                )
            }
        val appLocalData = AppLocalData(
            version = localDataManager.currentAppDataVersion,
            localDataList = listOf(
                LocalData.empty().copy(
                    webBookDataSourceId = snapshot.webBookDataSourceId,
                    bookshelfEntities = bookshelfEntityList,
                    bookshelfBookMetadataEntities = bookshelfBookMetadataEntities
                )
            ),
            globalLocalData = LocalData.empty()
        )
        try {
            val parcelFileDescriptor = applicationContext.contentResolver
                .openFileDescriptor(uri, "w")
                ?: error("Unable to open export URI: $uri")
            parcelFileDescriptor.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use {
                    it.writeAppLocalData(Cbor.encodeToByteArray(appLocalData))
                }
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file")
            e.printStackTrace()
            return Result.failure()
        }
    }
}
