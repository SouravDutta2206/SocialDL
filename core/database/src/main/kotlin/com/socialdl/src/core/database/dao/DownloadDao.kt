package com.socialdl.src.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.socialdl.src.core.database.entity.DownloadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_history ORDER BY completedAt DESC")
    fun getAllHistory(): Flow<List<DownloadHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entity: DownloadHistoryEntity)

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteHistory(id: String)

    @Query("DELETE FROM download_history WHERE id IN (:ids)")
    suspend fun deleteHistory(ids: List<String>)

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getHistoryById(id: String): DownloadHistoryEntity?

    @Query("DELETE FROM download_history")
    suspend fun clearHistory()
}
