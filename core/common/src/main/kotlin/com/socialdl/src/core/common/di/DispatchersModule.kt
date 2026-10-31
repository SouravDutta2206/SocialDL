package com.socialdl.src.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Dedicated, bounded dispatcher for Python/Chaquopy calls.
 *
 * Python's GIL serializes bytecode, but it is released while waiting on network I/O —
 * which is most of an extraction. Two threads keep one slow or cancelled extraction
 * from serializing every later call; more would not help, because the GIL is the real
 * limiter for CPU work.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PythonDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    private const val PYTHON_THREADS = 2
    private val threadIds = AtomicInteger()

    @Provides
    @Singleton
    @PythonDispatcher
    fun providesPythonDispatcher(): CoroutineDispatcher =
        Executors.newFixedThreadPool(PYTHON_THREADS) { runnable ->
            Thread(runnable, "python-bridge-${threadIds.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
