package com.socialdl.src.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.database.entity.DownloadHistoryEntity

@Database(
    entities = [
        DownloadHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SocialDLDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}
