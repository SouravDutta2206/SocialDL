package com.socialdl.src.core.database.di

import android.content.Context
import androidx.room.Room
import com.socialdl.src.core.database.SocialDLDatabase
import com.socialdl.src.core.database.dao.DownloadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

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
        ).fallbackToDestructiveMigrationFrom(1).build()
    }

    @Provides
    @Singleton
    fun provideDownloadDao(database: SocialDLDatabase): DownloadDao {
        return database.downloadDao()
    }
}
