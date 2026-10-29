package com.socialdl.src.core.download.di

import android.content.Context
import com.socialdl.src.core.download.ContentResolverMediaStoreGateway
import com.socialdl.src.core.download.DownloadWorkScheduler
import com.socialdl.src.core.download.MediaStoreGateway
import com.socialdl.src.core.download.WorkManagerDownloadScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    abstract fun bindDownloadWorkScheduler(
        scheduler: WorkManagerDownloadScheduler
    ): DownloadWorkScheduler
}

/**
 * Phase 5 seam binding: [com.socialdl.src.core.download.MediaStoreExporter] depends on
 * the narrow [MediaStoreGateway] instead of a `Context`, so the export cancellation
 * and compensation paths are unit-testable with a fake store.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaStoreModule {

    @Provides
    @Singleton
    fun provideMediaStoreGateway(@ApplicationContext context: Context): MediaStoreGateway =
        ContentResolverMediaStoreGateway(context.contentResolver)
}
