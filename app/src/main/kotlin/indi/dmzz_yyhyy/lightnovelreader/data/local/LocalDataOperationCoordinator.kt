package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserDataEntity
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes source snapshots and uses a DB commit token to recover file changes after a crash. */
@Singleton
class LocalDataOperationCoordinator @Inject constructor(
    @param:ApplicationContext context: Context,
    private val database: LightNovelReaderDatabase
) {
    val store = SourceSnapshotStore(context.dataDir.resolve("local_data"))
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            block()
        }
    }

    suspend fun recover() = exclusive { }

    private suspend fun recoverLocked() {
        val dao = database.userDataDao()
        store.recover(dao.get(SourceSnapshotStore.COMMIT_MARKER_PATH))
        dao.remove(SourceSnapshotStore.COMMIT_MARKER_PATH)
    }

    /** Call inside [exclusive]. File replacement and the commit token share the DB transaction. */
    suspend fun commit(
        replaceAll: Boolean = false,
        files: suspend () -> Map<Identifier, LocalData>,
        updateDatabase: suspend () -> Unit
    ) {
        var pending: SourceSnapshotStore.PendingChange? = null
        try {
            database.withTransaction {
                val change = store.prepare(files(), replaceAll).also { pending = it }
                change.apply()
                updateDatabase()
                database.userDataDao().insert(
                    UserDataEntity(
                        path = SourceSnapshotStore.COMMIT_MARKER_PATH,
                        group = "internal",
                        type = "String",
                        value = change.id
                    )
                )
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    store.recover(database.userDataDao().get(SourceSnapshotStore.COMMIT_MARKER_PATH))
                    database.userDataDao().remove(SourceSnapshotStore.COMMIT_MARKER_PATH)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
        // The token was committed with the database. A cleanup failure must not roll back
        // committed data; startup/the next operation can finish this journal safely.
        withContext(NonCancellable) {
            try {
                pending?.finish()
                database.userDataDao().remove(SourceSnapshotStore.COMMIT_MARKER_PATH)
            } catch (failure: Exception) {
                Log.w("LocalDataRecovery", "Committed restore cleanup will be retried", failure)
            }
        }
    }
}
