package com.sbcfg.manager.speedtest

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SpeedTestDao {
    @Insert
    suspend fun insert(result: SpeedTestResult)

    @Query("SELECT * FROM speed_test_results ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): SpeedTestResult?

    @Query("SELECT * FROM speed_test_results ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<SpeedTestResult>

    @Query("DELETE FROM speed_test_results WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
