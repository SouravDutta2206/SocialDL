package com.socialdl.src.core.cookies.di

import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.cookies.CookieStoreImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CookieModule {

    @Binds
    @Singleton
    abstract fun bindCookieStore(
        impl: CookieStoreImpl
    ): CookieStore
}
