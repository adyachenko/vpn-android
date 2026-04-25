package com.sbcfg.manager.speedtest

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_test_results")
data class SpeedTestResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val serverName: String,
    val pingMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val isBackground: Boolean = false,
)
