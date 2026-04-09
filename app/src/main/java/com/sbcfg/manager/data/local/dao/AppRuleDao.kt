package com.sbcfg.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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

    @Upsert
    suspend fun upsert(rule: AppRuleEntity)

    @Query("DELETE FROM app_rules WHERE isFromServer = 0")
    suspend fun deleteAllUserRules()

    @Query("DELETE FROM app_rules WHERE isFromServer = 1")
    suspend fun deleteAllServerRules()
}
