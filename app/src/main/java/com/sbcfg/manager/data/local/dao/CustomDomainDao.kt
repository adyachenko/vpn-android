package com.sbcfg.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomDomainDao {
    @Query("SELECT * FROM custom_domains ORDER BY isFromServer DESC, createdAt ASC")
    fun observeAll(): Flow<List<CustomDomainEntity>>

    @Query("SELECT * FROM custom_domains WHERE mode = :mode")
    suspend fun getByMode(mode: String): List<CustomDomainEntity>

    @Query("SELECT * FROM custom_domains")
    suspend fun getAll(): List<CustomDomainEntity>

    @Insert
    suspend fun insert(domain: CustomDomainEntity)

    @Delete
    suspend fun delete(domain: CustomDomainEntity)

    @Query("DELETE FROM custom_domains WHERE isFromServer = 1")
    suspend fun deleteAllServerDomains()

    @Query("SELECT EXISTS(SELECT 1 FROM custom_domains WHERE domain = :domain)")
    suspend fun exists(domain: String): Boolean
}
