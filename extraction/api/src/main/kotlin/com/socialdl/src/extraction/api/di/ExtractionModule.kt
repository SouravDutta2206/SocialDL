package com.socialdl.src.extraction.api.di

import com.socialdl.src.extraction.api.ExtractorRegistry
import com.socialdl.src.extraction.api.MediaExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExtractionModule {

    @Provides
    @Singleton
    fun provideExtractorRegistry(
        extractors: Set<@JvmSuppressWildcards MediaExtractor>
    ): ExtractorRegistry = ExtractorRegistry(extractors)
}
