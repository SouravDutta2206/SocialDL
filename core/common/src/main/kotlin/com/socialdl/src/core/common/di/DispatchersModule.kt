package com.socialdl.src.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Dedicated single-thread dispatcher for Python/Chaquopy calls.
 * Python's GIL means parallel Python calls do not help, so they are queued here.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PythonDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Singleton
    @PythonDispatcher
    fun providesPythonDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "python-bridge").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
