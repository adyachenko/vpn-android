package com.sbcfg.manager.viewmodel

import android.app.Application
import app.cash.turbine.test
import com.sbcfg.manager.MainDispatcherRule
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.model.ConfigState
import com.sbcfg.manager.ui.main.MainViewModel
import com.sbcfg.manager.ui.main.SideEffect
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `toggle VPN - generates config and emits RequestVpnPermission`() = runTest {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val app = mockk<Application>(relaxed = true)

        every { configManager.observeConfigState() } returns flowOf(ConfigState.NotConfigured)
        coEvery { configManager.generateConfigJson() } returns """{"dns":{}}"""

        val viewModel = MainViewModel(
            configManager = configManager,
            app = app
        )

        viewModel.sideEffect.test {
            viewModel.onToggleVpn()

            val effect = awaitItem()
            assertTrue(effect is SideEffect.RequestVpnPermission)
            assertEquals("""{"dns":{}}""", viewModel.pendingConfigJson)
        }
    }
}
