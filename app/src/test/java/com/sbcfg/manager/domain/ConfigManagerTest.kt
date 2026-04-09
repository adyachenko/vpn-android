package com.sbcfg.manager.domain

import com.sbcfg.manager.data.local.dao.AppRuleDao
import com.sbcfg.manager.data.local.dao.CustomDomainDao
import com.sbcfg.manager.data.local.dao.ServerConfigDao
import com.sbcfg.manager.data.local.entity.AppRuleEntity
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.data.local.entity.ServerConfigEntity
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.data.remote.ConfigApiClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfigManagerTest {

    private lateinit var apiClient: ConfigApiClient
    private lateinit var serverConfigDao: ServerConfigDao
    private lateinit var customDomainDao: CustomDomainDao
    private lateinit var appRuleDao: AppRuleDao
    private lateinit var configGenerator: ConfigGenerator
    private lateinit var appPreferences: AppPreferences
    private lateinit var manager: ConfigManager

    private val testUrl = "https://example.com/api/config/token123"

    private val validConfigJson = """
        {
          "dns": { "servers": [], "rules": [] },
          "inbounds": [{ "type": "tun", "tag": "tun-in", "exclude_package": ["com.server.app"] }],
          "outbounds": [
            { "type": "selector", "tag": "proxy-select", "outbounds": ["proxy-out", "direct-out"] },
            { "type": "naive", "tag": "proxy-out", "server": "proxy.example.com", "server_port": 443 },
            { "type": "direct", "tag": "direct-out" }
          ],
          "route": {
            "rules": [
              { "rule_set": ["sites-direct"], "outbound": "direct-out" },
              { "package_name": [], "outbound": "direct-out" }
            ],
            "rule_set": [
              {
                "tag": "sites-direct",
                "type": "inline",
                "rules": [{ "domain_suffix": ["yandex.ru", "vk.com"] }]
              }
            ]
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        apiClient = mockk()
        serverConfigDao = mockk()
        customDomainDao = mockk()
        appRuleDao = mockk()
        configGenerator = mockk()
        appPreferences = mockk()
        manager = ConfigManager(
            apiClient, serverConfigDao, customDomainDao,
            appRuleDao, configGenerator, appPreferences
        )
    }

    @Test
    fun testFetchConfig_ValidUrl_SavesConfig() = runTest {
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success(validConfigJson)
        val configSlot = slot<ServerConfigEntity>()
        coEvery { serverConfigDao.upsert(capture(configSlot)) } just Runs
        coEvery { customDomainDao.exists(any()) } returns false
        coEvery { customDomainDao.insert(any()) } just Runs
        coEvery { appRuleDao.upsert(any()) } just Runs
        coEvery { appPreferences.setConfigUrl(testUrl) } just Runs

        val result = manager.fetchAndSaveConfig(testUrl)

        assertTrue(result.isSuccess)
        val serverInfo = result.getOrThrow()
        assertEquals("proxy.example.com", serverInfo.serverName)
        assertEquals("naive", serverInfo.protocol)

        // Verify config was saved
        coVerify { serverConfigDao.upsert(any()) }
        assertEquals(testUrl, configSlot.captured.url)
        assertEquals("proxy.example.com", configSlot.captured.serverName)
    }

    @Test
    fun testFetchConfig_InvalidJson_ReturnsError() = runTest {
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success("not valid json {{{")

        val result = manager.fetchAndSaveConfig(testUrl)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("некорректный конфиг"))
    }

    @Test
    fun testFetchConfig_MissingSections_ReturnsError() = runTest {
        val incompleteJson = """{ "dns": {}, "inbounds": [] }"""
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success(incompleteJson)

        val result = manager.fetchAndSaveConfig(testUrl)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("отсутствует секция"))
    }

    @Test
    fun testRefreshConfig_PreservesUserRules() = runTest {
        coEvery { appPreferences.configUrl } returns flowOf(testUrl)
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success(validConfigJson)
        coEvery { customDomainDao.deleteAllServerDomains() } just Runs
        coEvery { appRuleDao.deleteAllServerRules() } just Runs
        coEvery { serverConfigDao.upsert(any()) } just Runs
        coEvery { customDomainDao.exists(any()) } returns false
        coEvery { customDomainDao.insert(any()) } just Runs
        coEvery { appRuleDao.upsert(any()) } just Runs

        val result = manager.refreshConfig()

        assertTrue(result.isSuccess)
        // Verify only server domains/rules are deleted, not user ones
        coVerify { customDomainDao.deleteAllServerDomains() }
        coVerify { appRuleDao.deleteAllServerRules() }
        coVerify(exactly = 0) { customDomainDao.delete(any()) }
    }

    @Test
    fun testRefreshConfig_RemovesOldServerDomains() = runTest {
        coEvery { appPreferences.configUrl } returns flowOf(testUrl)
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success(validConfigJson)
        coEvery { customDomainDao.deleteAllServerDomains() } just Runs
        coEvery { appRuleDao.deleteAllServerRules() } just Runs
        coEvery { serverConfigDao.upsert(any()) } just Runs
        coEvery { customDomainDao.exists(any()) } returns false
        coEvery { customDomainDao.insert(any()) } just Runs
        coEvery { appRuleDao.upsert(any()) } just Runs

        manager.refreshConfig()

        coVerify(exactly = 1) { customDomainDao.deleteAllServerDomains() }
    }

    @Test
    fun testRefreshConfig_AddsNewServerDomains() = runTest {
        coEvery { appPreferences.configUrl } returns flowOf(testUrl)
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success(validConfigJson)
        coEvery { customDomainDao.deleteAllServerDomains() } just Runs
        coEvery { appRuleDao.deleteAllServerRules() } just Runs
        coEvery { serverConfigDao.upsert(any()) } just Runs
        coEvery { customDomainDao.exists(any()) } returns false
        val domainSlots = mutableListOf<CustomDomainEntity>()
        coEvery { customDomainDao.insert(capture(domainSlots)) } just Runs
        coEvery { appRuleDao.upsert(any()) } just Runs

        manager.refreshConfig()

        // Should have inserted server domains: yandex.ru, vk.com
        val serverDomains = domainSlots.filter { it.isFromServer }
        assertEquals(2, serverDomains.size)
        assertTrue(serverDomains.any { it.domain == "yandex.ru" })
        assertTrue(serverDomains.any { it.domain == "vk.com" })
    }

    @Test
    fun testRefreshConfig_UserRuleOverridesServerConflict() = runTest {
        coEvery { appPreferences.configUrl } returns flowOf(testUrl)
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.success(validConfigJson)
        coEvery { customDomainDao.deleteAllServerDomains() } just Runs
        coEvery { appRuleDao.deleteAllServerRules() } just Runs
        coEvery { serverConfigDao.upsert(any()) } just Runs
        // yandex.ru already exists as user rule
        coEvery { customDomainDao.exists("yandex.ru") } returns true
        coEvery { customDomainDao.exists("vk.com") } returns false
        val domainSlots = mutableListOf<CustomDomainEntity>()
        coEvery { customDomainDao.insert(capture(domainSlots)) } just Runs
        coEvery { appRuleDao.upsert(any()) } just Runs

        manager.refreshConfig()

        // Only vk.com should be inserted since yandex.ru already exists as user rule
        assertEquals(1, domainSlots.size)
        assertEquals("vk.com", domainSlots[0].domain)
    }

    @Test
    fun testRefreshConfig_ServerError_PreservesExisting() = runTest {
        coEvery { appPreferences.configUrl } returns flowOf(testUrl)
        coEvery { apiClient.fetchConfig(testUrl) } returns Result.failure(
            java.io.IOException("Нет соединения с сервером")
        )

        val result = manager.refreshConfig()

        assertTrue(result.isFailure)
        // Should NOT have tried to delete anything
        coVerify(exactly = 0) { customDomainDao.deleteAllServerDomains() }
        coVerify(exactly = 0) { appRuleDao.deleteAllServerRules() }
        coVerify(exactly = 0) { serverConfigDao.upsert(any()) }
    }
}
