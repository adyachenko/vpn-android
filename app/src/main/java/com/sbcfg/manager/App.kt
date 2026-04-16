package com.sbcfg.manager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.ConfigChangeWatcher
import com.sbcfg.manager.worker.ConfigRefreshWorker
import com.sbcfg.manager.worker.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject lateinit var configChangeWatcher: ConfigChangeWatcher
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        setupLibbox()
        configChangeWatcher.start(appScope)
        schedulePeriodicWork()
    }

    private fun schedulePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val configRefresh = PeriodicWorkRequestBuilder<ConfigRefreshWorker>(
            24, TimeUnit.HOURS
        ).setConstraints(constraints).build()

        val updateCheck = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            1, TimeUnit.HOURS
        ).setConstraints(constraints).build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            ConfigRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            configRefresh
        )
        workManager.enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            updateCheck
        )
        AppLog.i("App", "Scheduled periodic workers (config refresh 24h, update check 1h daytime-only)")
    }

    private fun setupLibbox() {
        try {
            // Use internal storage for basePath — external storage doesn't allow unix sockets
            val baseDir = filesDir
            val workingDir = File(filesDir, "working")
            val tempDir = File(cacheDir, "tmp")
            workingDir.mkdirs()
            tempDir.mkdirs()

            AppLog.i("App", "Setting up libbox: base=$baseDir, working=$workingDir, temp=$tempDir")
            val options = io.nekohasekai.libbox.SetupOptions()
            options.setBasePath(baseDir.path)
            options.setWorkingPath(workingDir.path)
            options.setTempPath(tempDir.path)
            io.nekohasekai.libbox.Libbox.setup(options)
            AppLog.i("App", "libbox setup complete")
        } catch (e: Exception) {
            AppLog.e("App", "libbox setup failed", e)
        }
    }
}
