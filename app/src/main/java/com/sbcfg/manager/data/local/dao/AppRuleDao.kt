package com.sbcfg.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sbcfg.manager.data.local.entity.AppRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules ORDER BY appName ASC")
    fun observeAll(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAll(): List<AppRuleEntity>

    @Query("SELECT * FROM app_rules WHERE mode = :mode")
    suspend fun getByMode(mode: String): List<AppRuleEntity>

    /**
     * Upserts a rule by replacing on packageName conflict (the unique index).
     * Cannot use @Upsert here: with autoGenerate id=0 it always tries INSERT,
     * and the fallback UPDATE matches on the id (also 0) so it never finds
     * the existing row → mode change is silently lost.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AppRuleEntity)

    @Query("DELETE FROM app_rules WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("DELETE FROM app_rules WHERE isFromServer = 0")
    suspend fun deleteAllUserRules()

    @Query("DELETE FROM app_rules WHERE isFromServer = 1")
    suspend fun deleteAllServerRules()
}
