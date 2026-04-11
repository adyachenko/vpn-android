package com.sbcfg.manager.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.util.AppLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ConfigRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val configManager: ConfigManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        AppLog.i(TAG, "Starting periodic config refresh")
        return try {
            configManager.refreshConfig()
                .onSuccess { AppLog.i(TAG, "Config refreshed successfully") }
                .onFailure { AppLog.w(TAG, "Config refresh failed: ${it.message}") }
            Result.success()
        } catch (e: java.io.IOException) {
            AppLog.w(TAG, "Network error, will retry: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            AppLog.e(TAG, "Config refresh error", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ConfigRefreshWorker"
        const val WORK_NAME = "ConfigRefresh"
    }
}
