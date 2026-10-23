package com.socialdl.src.core.python.di

import com.socialdl.src.core.common.di.PythonDispatcher
import com.socialdl.src.core.python.PythonEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PythonModule {

    @Provides
    @Singleton
    fun providePythonEngine(
        @PythonDispatcher pythonDispatcher: CoroutineDispatcher
    ): PythonEngine = PythonEngine(pythonDispatcher)
}
