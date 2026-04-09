package com.sbcfg.manager.domain

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppResolverTest {

    private lateinit var app: Application
    private lateinit var pm: PackageManager
    private lateinit var resolver: AppResolver

    private fun createAppInfo(packageName: String): ApplicationInfo {
        return ApplicationInfo().apply {
            this.packageName = packageName
        }
    }

    @Before
    fun setUp() {
        app = mockk()
        pm = mockk()
        every { app.packageManager } returns pm
        every { app.packageName } returns "com.sbcfg.manager"
        resolver = AppResolver(app)
    }

    @Test
    fun testGetInstalledApps_FiltersSystemApps() {
        val userApp = createAppInfo("com.user.app")
        val systemApp = createAppInfo("com.android.systemui")

        every { pm.getInstalledApplications(0) } returns listOf(userApp, systemApp)
        // User app has launch intent, system app does not
        every { pm.getLaunchIntentForPackage("com.user.app") } returns mockk<Intent>()
        every { pm.getLaunchIntentForPackage("com.android.systemui") } returns null
        every { pm.getApplicationLabel(userApp) } returns "User App"
        every { pm.getApplicationIcon(userApp) } returns ColorDrawable()

        val apps = resolver.getInstalledApps()

        assertEquals(1, apps.size)
        assertEquals("com.user.app", apps[0].packageName)
    }

    @Test
    fun testGetInstalledApps_ExcludesOwnPackage() {
        val ownApp = createAppInfo("com.sbcfg.manager")
        val otherApp = createAppInfo("com.other.app")

        every { pm.getInstalledApplications(0) } returns listOf(ownApp, otherApp)
        every { pm.getLaunchIntentForPackage("com.sbcfg.manager") } returns mockk<Intent>()
        every { pm.getLaunchIntentForPackage("com.other.app") } returns mockk<Intent>()
        every { pm.getApplicationLabel(otherApp) } returns "Other App"
        every { pm.getApplicationIcon(otherApp) } returns ColorDrawable()

        val apps = resolver.getInstalledApps()

        assertEquals(1, apps.size)
        assertEquals("com.other.app", apps[0].packageName)
    }

    @Test
    fun testGetInstalledApps_SortedByName() {
        val appC = createAppInfo("com.app.c")
        val appA = createAppInfo("com.app.a")
        val appB = createAppInfo("com.app.b")

        every { pm.getInstalledApplications(0) } returns listOf(appC, appA, appB)
        every { pm.getLaunchIntentForPackage(any()) } returns mockk<Intent>()
        every { pm.getApplicationLabel(appC) } returns "Charlie"
        every { pm.getApplicationLabel(appA) } returns "Alpha"
        every { pm.getApplicationLabel(appB) } returns "Bravo"
        every { pm.getApplicationIcon(any<ApplicationInfo>()) } returns ColorDrawable()

        val apps = resolver.getInstalledApps()

        assertEquals(3, apps.size)
        assertEquals("Alpha", apps[0].appName)
        assertEquals("Bravo", apps[1].appName)
        assertEquals("Charlie", apps[2].appName)
    }
}
