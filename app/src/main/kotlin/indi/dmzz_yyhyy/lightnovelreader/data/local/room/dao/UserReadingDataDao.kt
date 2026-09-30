package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.ChapterReadingProgressMapConverter
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.LocalDateTimeConverter
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserReadingDataEntity
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

@Dao
interface UserReadingDataDao {
    @TypeConverters(ChapterReadingProgressMapConverter::class, LocalDateTimeConverter::class)
    @Query(
        "replace into user_reading_data (id, last_read_time, total_read_time, reading_progress, last_read_chapter_id, last_read_chapter_title, current_chapter_reading_progress_map, max_chapter_reading_progress_map) " +
                "values (:id, :lastReadTime, :totalReadTime, :readingProgress, :lastReadChapterId, :lastReadChapterTitle, :currentChapterReadingProgressMap, :maxChapterReadingProgressMap)"
    )
    suspend fun insert(
        id: String,
        lastReadTime: LocalDateTime,
        totalReadTime: Int,
        readingProgress: Float,
        lastReadChapterId: String,
        lastReadChapterTitle: String,
        currentChapterReadingProgressMap: Map<String, Float>,
        maxChapterReadingProgressMap: Map<String, Float>
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(userReading: UserReadingDataEntity)

    @Query("select * from user_reading_data where id = :id")
    suspend fun getEntity(id: String): UserReadingDataEntity?

    @Transaction
    suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
        val current = getEntity(id)?.let {
            UserReadingData(
                it.id,
                it.lastReadTime,
                it.totalReadTime,
                it.readingProgress,
                it.lastReadChapterId,
                it.lastReadChapterTitle,
                it.currentChapterReadingProgressMap,
                it.maxChapterReadingProgressMap
            )
        } ?: UserReadingData(id)
        val new = update(current)
        insert(
            id = new.id,
            lastReadTime = new.lastReadTime ?: LocalDateTime.MIN,
            totalReadTime = new.totalReadTime,
            readingProgress = new.readingProgress,
            lastReadChapterId = new.lastReadChapterId ?: "",
            lastReadChapterTitle = new.lastReadChapterTitle ?: "",
            currentChapterReadingProgressMap = new.currentChapterReadingProgressMap,
            maxChapterReadingProgressMap = new.maxChapterReadingProgressMap
        )
    }

    @Query("select * from user_reading_data where id = :id")
    fun getEntityFlow(id: String): Flow<UserReadingDataEntity?>

    @Query("select * from user_reading_data")
    suspend fun getAll(): List<UserReadingDataEntity>

    @Query("delete from user_reading_data where id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("delete from user_reading_data")
    suspend fun clear()
}
