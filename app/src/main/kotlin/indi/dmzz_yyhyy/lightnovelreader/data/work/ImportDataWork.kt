package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.onErr
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalDataManager
import indi.dmzz_yyhyy.lightnovelreader.data.local.SourceSnapshotStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.coroutines.CancellationException
import java.io.FileInputStream

@HiltWorker
class ImportDataWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localDataManager: LocalDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ImportDataWork"
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        val fileUri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val overwrite = inputData.getBoolean("overwrite", false)
        val appLocalData = try {
            applicationContext.contentResolver.openFileDescriptor(fileUri, "r")
                ?.use { parcelFileDescriptor ->
                    FileInputStream(parcelFileDescriptor.fileDescriptor).use { inputStream ->
                        SourceSnapshotStore.readBackup(inputStream)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load file")
            e.printStackTrace()
            return Result.failure()
        } ?: return Result.failure()
        localDataManager.importAppLocalData(appLocalData, overwrite)
            .onErr {
                Log.e(TAG, "Failed to import the data")
                it.printStackTrace()
                return Result.failure()
            }
        return Result.success()
    }
}
