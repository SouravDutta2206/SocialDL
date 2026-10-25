package com.socialdl.src.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.socialdl.src.core.database.SocialDLDatabase
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.database.dao.DownloadJobDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v2 → v3: introduce the durable `download_jobs` table (P0-4). Room becomes the
     * source of truth for the download lifecycle; WorkManager Data now carries only
     * the job id, so the full job config (including signed CDN URLs) lives here.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_jobs` (" +
                    "`id` TEXT NOT NULL, " +
                    "`workId` TEXT, " +
                    "`sourceUrl` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`author` TEXT, " +
                    "`thumbnailUrl` TEXT, " +
                    "`platform` TEXT NOT NULL, " +
                    "`carouselIndex` INTEGER NOT NULL, " +
                    "`formatLabel` TEXT NOT NULL, " +
                    "`formatKind` TEXT NOT NULL, " +
                    "`container` TEXT NOT NULL, " +
                    "`groupId` TEXT, " +
                    "`status` TEXT NOT NULL, " +
                    "`progress` REAL NOT NULL, " +
                    "`downloadedBytes` INTEGER NOT NULL, " +
                    "`totalBytes` INTEGER, " +
                    "`errorMessage` TEXT, " +
                    "`outputUri` TEXT, " +
                    "`fileSizeBytes` INTEGER NOT NULL, " +
                    "`pendingUri` TEXT, " +
                    "`directUrl` TEXT, " +
                    "`requiresMuxing` INTEGER NOT NULL, " +
                    "`audioDirectUrl` TEXT, " +
                    "`audioContainer` TEXT, " +
                    "`muxStrategy` TEXT, " +
                    "`videoChunkSize` INTEGER NOT NULL, " +
                    "`audioChunkSize` INTEGER NOT NULL, " +
                    "`isPlaceholder` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_download_jobs_status` " +
                    "ON `download_jobs` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_download_jobs_sourceUrl` " +
                    "ON `download_jobs` (`sourceUrl`)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SocialDLDatabase {
        return Room.databaseBuilder(
            context,
            SocialDLDatabase::class.java,
            "socialdl.db"
        // v1→v2 shipped without any Migration, so only versions at or below 1 may be
        // destructively recreated. Future version bumps must ship a real Migration —
        // otherwise Room fails loudly instead of silently wiping download history.
        // (Room 2.6.1 supports the vararg `fallbackToDestructiveMigrationFrom` overload.)
        ).fallbackToDestructiveMigrationFrom(1)
            .addMigrations(MIGRATION_2_3)
            .build()
    }

    @Provides
    @Singleton
    fun provideDownloadDao(database: SocialDLDatabase): DownloadDao {
        return database.downloadDao()
    }

    @Provides
    @Singleton
    fun provideDownloadJobDao(database: SocialDLDatabase): DownloadJobDao {
        return database.downloadJobDao()
    }
}
