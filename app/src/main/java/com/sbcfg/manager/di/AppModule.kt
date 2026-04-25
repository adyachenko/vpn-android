package com.sbcfg.manager.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.sbcfg.manager.data.local.AppDatabase
import com.sbcfg.manager.data.local.dao.AppRuleDao
import com.sbcfg.manager.data.local.dao.CustomDomainDao
import com.sbcfg.manager.data.local.dao.ServerConfigDao
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.speedtest.SpeedTestDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "sbcfg.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideServerConfigDao(db: AppDatabase): ServerConfigDao = db.serverConfigDao()

    @Provides
    fun provideCustomDomainDao(db: AppDatabase): CustomDomainDao = db.customDomainDao()

    @Provides
    fun provideAppRuleDao(db: AppDatabase): AppRuleDao = db.appRuleDao()

    @Provides
    fun provideSpeedTestDao(db: AppDatabase): SpeedTestDao = db.speedTestDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideAppPreferences(dataStore: DataStore<Preferences>): AppPreferences =
        AppPreferences(dataStore)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
}
