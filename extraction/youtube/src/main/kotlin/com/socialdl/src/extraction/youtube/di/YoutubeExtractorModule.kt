package com.socialdl.src.extraction.youtube.di

import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.youtube.YoutubeExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class YoutubeExtractorModule {

    @Binds
    @IntoSet
    abstract fun bindYoutubeExtractor(extractor: YoutubeExtractor): MediaExtractor
}
