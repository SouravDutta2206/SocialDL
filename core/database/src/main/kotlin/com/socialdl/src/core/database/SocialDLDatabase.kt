package com.socialdl.src.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.database.dao.DownloadJobDao
import com.socialdl.src.core.database.entity.DownloadHistoryEntity
import com.socialdl.src.core.database.entity.DownloadJobEntity

@Database(
    entities = [
        DownloadHistoryEntity::class,
        DownloadJobEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class SocialDLDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadJobDao(): DownloadJobDao
}
