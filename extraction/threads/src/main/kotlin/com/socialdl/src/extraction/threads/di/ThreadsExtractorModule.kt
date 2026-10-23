package com.socialdl.src.extraction.threads.di

import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.threads.ThreadsExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ThreadsExtractorModule {

    @Binds
    @IntoSet
    abstract fun bindThreadsExtractor(extractor: ThreadsExtractor): MediaExtractor
}
