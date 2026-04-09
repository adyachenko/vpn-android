package com.sbcfg.manager.viewmodel

import app.cash.turbine.test
import com.sbcfg.manager.MainDispatcherRule
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.model.DomainMode
import com.sbcfg.manager.ui.main.DomainsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var configManager: ConfigManager
    private val domainsFlow = MutableStateFlow<List<CustomDomainEntity>>(emptyList())

    @Before
    fun setup() {
        configManager = mockk(relaxed = true)
        every { configManager.observeDomains() } returns domainsFlow
    }

    @Test
    fun `add valid domain - appears in list`() = runTest {
        coEvery { configManager.addDomainRule(any(), any()) } returns Result.success(Unit)

        val viewModel = DomainsViewModel(configManager)

        val domain = CustomDomainEntity(
            id = 1,
            domain = "example.com",
            mode = "proxy"
        )
        domainsFlow.value = listOf(domain)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.domains.size)
            assertEquals("example.com", state.domains[0].domain)
        }
    }

    @Test
    fun `delete domain - removed from list`() = runTest {
        val domain = CustomDomainEntity(
            id = 1,
            domain = "example.com",
            mode = "proxy"
        )
        domainsFlow.value = listOf(domain)

        val viewModel = DomainsViewModel(configManager)

        viewModel.onDeleteDomain(domain)

        coVerify { configManager.removeDomainRule(domain) }

        // Simulate removal from DB
        domainsFlow.value = emptyList()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.domains.isEmpty())
        }
    }

    @Test
    fun `add invalid domain - shows validation error`() = runTest {
        val viewModel = DomainsViewModel(configManager)

        viewModel.onAddDomain("not a domain", DomainMode.PROXY)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Невалидный домен", state.validationError)
        }

        coVerify(exactly = 0) { configManager.addDomainRule(any(), any()) }
    }
}
