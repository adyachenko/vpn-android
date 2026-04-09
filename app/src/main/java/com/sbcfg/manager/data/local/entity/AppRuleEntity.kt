package com.sbcfg.manager.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_rules",
    indices = [Index(value = ["packageName"], unique = true)]
)
data class AppRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val mode: String,
    val isFromServer: Boolean = false
)
