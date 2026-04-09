package com.sbcfg.manager.domain

import android.app.Application
import android.graphics.drawable.Drawable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppResolver @Inject constructor(
    private val app: Application
) {
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable
    )

    private var cachedApps: List<AppInfo>? = null

    fun getInstalledApps(): List<AppInfo> {
        cachedApps?.let { return it }

        val pm = app.packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { appInfo ->
                pm.getLaunchIntentForPackage(appInfo.packageName) != null
            }
            .filter { it.packageName != app.packageName }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo)
                )
            }
            .sortedBy { it.appName.lowercase() }

        cachedApps = apps
        return apps
    }
}
