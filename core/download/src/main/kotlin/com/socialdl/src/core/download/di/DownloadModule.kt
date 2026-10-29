package com.socialdl.src.core.download.di

import com.socialdl.src.core.download.DownloadWorkScheduler
import com.socialdl.src.core.download.WorkManagerDownloadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    abstract fun bindDownloadWorkScheduler(
        scheduler: WorkManagerDownloadScheduler
    ): DownloadWorkScheduler
}
