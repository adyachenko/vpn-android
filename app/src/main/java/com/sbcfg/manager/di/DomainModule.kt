package com.sbcfg.manager.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    // ConfigManager, ConfigGenerator, AppResolver are @Singleton @Inject constructor
    // Hilt resolves them automatically, no @Provides needed
}
