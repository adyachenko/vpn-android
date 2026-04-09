package com.sbcfg.manager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_config")
data class ServerConfigEntity(
    @PrimaryKey val id: Int = 1,
    val url: String,
    val rawJson: String,
    val serverName: String,
    val protocol: String,
    val fetchedAt: Long
)
