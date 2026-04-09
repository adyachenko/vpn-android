package com.sbcfg.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.sbcfg.manager.data.local.entity.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {
    @Query("SELECT * FROM server_config WHERE id = 1")
    suspend fun get(): ServerConfigEntity?

    @Query("SELECT * FROM server_config WHERE id = 1")
    fun observe(): Flow<ServerConfigEntity?>

    @Upsert
    suspend fun upsert(config: ServerConfigEntity)
}
