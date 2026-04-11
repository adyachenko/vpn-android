package com.sbcfg.manager.update

import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    private val updateChecker: GitHubUpdateChecker,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    suspend fun checkForUpdate() {
        _state.value = UpdateState.Checking
        try {
            val info = updateChecker.check()
            _state.value = if (info != null) {
                UpdateState.UpdateAvailable(info)
            } else {
                UpdateState.Idle
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Update check failed", e)
            _state.value = UpdateState.Error("Ошибка проверки: ${e.message}")
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo) {
        var progressJob: Job? = null
        try {
            _state.value = UpdateState.Downloading(0f)

            progressJob = CoroutineScope(Dispatchers.Default).launch {
                apkDownloader.progress.collect { progress ->
                    _state.value = UpdateState.Downloading(progress)
                }
            }

            val apkFile = apkDownloader.download(info.downloadUrl)
            progressJob.cancel()

            _state.value = UpdateState.Installing
            apkInstaller.install(apkFile)

            // After commit, the system will show install dialog
            _state.value = UpdateState.Idle
        } catch (e: Exception) {
            progressJob?.cancel()
            AppLog.e(TAG, "Download/install failed", e)
            _state.value = UpdateState.Error("Ошибка загрузки: ${e.message}")
        }
    }

    fun resetState() {
        _state.value = UpdateState.Idle
    }

    companion object {
        private const val TAG = "UpdateManager"
    }
}
