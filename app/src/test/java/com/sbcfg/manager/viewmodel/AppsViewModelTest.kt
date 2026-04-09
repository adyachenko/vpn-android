package com.sbcfg.manager.viewmodel

import com.sbcfg.manager.MainDispatcherRule
import com.sbcfg.manager.domain.AppResolver
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.model.AppMode
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `set app mode - updates rule via ConfigManager`() = runTest {
        val configManager = mockk<ConfigManager>(relaxed = true)
        val appResolver = mockk<AppResolver>()

        every { configManager.observeAppRules() } returns flowOf(emptyList())
        every { appResolver.getInstalledApps() } returns emptyList()

        val viewModel = com.sbcfg.manager.ui.main.AppsViewModel(
            configManager = configManager,
            appResolver = appResolver
        )

        viewModel.onSetAppMode("com.test.app", "Test App", AppMode.BYPASS)

        coVerify { configManager.setAppMode("com.test.app", "Test App", AppMode.BYPASS) }
    }
}
