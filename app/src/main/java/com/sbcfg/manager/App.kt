package com.sbcfg.manager

import android.app.Application
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        setupLibbox()
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
