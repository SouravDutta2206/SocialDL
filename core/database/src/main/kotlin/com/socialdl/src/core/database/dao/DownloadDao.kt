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

    /** Chunked delete: SQLite's default bind limit is ~999; 500 leaves headroom. */
    suspend fun deleteHistoryInChunks(ids: List<String>) {
        ids.chunked(SQLITE_BIND_LIMIT).forEach { deleteHistory(it) }
    }

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getHistoryById(id: String): DownloadHistoryEntity?

    @Query("UPDATE download_history SET missingSince = :missingSince WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>, missingSince: Long)

    @Query("UPDATE download_history SET missingSince = NULL WHERE id IN (:ids)")
    suspend fun clearMissing(ids: List<String>)

    @Query("DELETE FROM download_history")
    suspend fun clearHistory()

    companion object {
        const val SQLITE_BIND_LIMIT = 500
    }
}
