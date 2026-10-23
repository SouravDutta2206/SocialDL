package com.socialdl.src.core.node.di

import com.socialdl.src.core.node.NodeBinaryProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NodeModule {
    // NodeBinaryProvider has @Inject constructor and is annotated with @Singleton
}
