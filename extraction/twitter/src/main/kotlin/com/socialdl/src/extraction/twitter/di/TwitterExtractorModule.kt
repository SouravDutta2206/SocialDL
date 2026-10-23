package com.socialdl.src.extraction.twitter.di

import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.twitter.TwitterExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class TwitterExtractorModule {

    @Binds
    @IntoSet
    abstract fun bindTwitterExtractor(extractor: TwitterExtractor): MediaExtractor
}
