package com.sbcfg.manager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_domains")
data class CustomDomainEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val mode: String,
    val isFromServer: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
