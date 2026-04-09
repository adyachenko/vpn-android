package com.sbcfg.manager.viewmodel

import app.cash.turbine.test
import com.sbcfg.manager.MainDispatcherRule
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.model.ServerInfo
import com.sbcfg.manager.ui.setup.SetupViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var configManager: ConfigManager
    private lateinit var viewModel: SetupViewModel

    @Before
    fun setup() {
        configManager = mockk(relaxed = true)
        viewModel = SetupViewModel(configManager)
    }

    @Test
    fun `submit valid URL - loading then success`() = runTest {
        val serverInfo = ServerInfo(
            serverName = "test-server",
            protocol = "naive",
            url = "https://example.com/api/config/abc"
        )
        coEvery { configManager.fetchAndSaveConfig(any()) } returns Result.success(serverInfo)

        viewModel.onUrlChanged("https://example.com/api/config/abc")
        viewModel.onSubmit()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertTrue(state.isConfigured)
            assertNull(state.error)
        }
    }

    @Test
    fun `submit URL that fails - loading then error`() = runTest {
        coEvery { configManager.fetchAndSaveConfig(any()) } returns
            Result.failure(RuntimeException("Network error"))

        viewModel.onUrlChanged("https://example.com/api/config/abc")
        viewModel.onSubmit()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertFalse(state.isConfigured)
            assertEquals("Network error", state.error)
        }
    }

    @Test
    fun `submit empty URL - no request made`() = runTest {
        viewModel.onUrlChanged("")
        viewModel.onSubmit()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertFalse(state.isConfigured)
            assertNull(state.error)
        }

        coVerify(exactly = 0) { configManager.fetchAndSaveConfig(any()) }
    }
}
