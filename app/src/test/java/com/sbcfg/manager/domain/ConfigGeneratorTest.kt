package com.sbcfg.manager.domain

import com.sbcfg.manager.data.local.dao.AppRuleDao
import com.sbcfg.manager.data.local.dao.CustomDomainDao
import com.sbcfg.manager.data.local.dao.ServerConfigDao
import com.sbcfg.manager.data.local.entity.AppRuleEntity
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.data.local.entity.ServerConfigEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfigGeneratorTest {

    private lateinit var serverConfigDao: ServerConfigDao
    private lateinit var customDomainDao: CustomDomainDao
    private lateinit var appRuleDao: AppRuleDao
    private lateinit var generator: ConfigGenerator

    private val testConfigJson = """
        {
          "dns": { "servers": [], "rules": [] },
          "inbounds": [{ "type": "tun", "tag": "tun-in", "exclude_package": [] }],
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

    private fun serverConfigEntity(rawJson: String = testConfigJson) = ServerConfigEntity(
        url = "https://example.com/api/config/token123",
        rawJson = rawJson,
        serverName = "proxy.example.com",
        protocol = "naive",
        fetchedAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        serverConfigDao = mockk()
        customDomainDao = mockk()
        appRuleDao = mockk()
        generator = ConfigGenerator(serverConfigDao, customDomainDao, appRuleDao)
    }

    @Test
    fun testGenerate_NoCustomRules_ReturnsServerConfig() = runTest {
        coEvery { serverConfigDao.get() } returns serverConfigEntity()
        coEvery { customDomainDao.getByMode("direct") } returns emptyList()
        coEvery { customDomainDao.getByMode("proxy") } returns emptyList()
        coEvery { appRuleDao.getByMode("direct") } returns emptyList()
        coEvery { appRuleDao.getByMode("bypass") } returns emptyList()

        val result = generator.generate()
        val json = JSONObject(result)

        // Server config should be preserved as-is
        val route = json.getJSONObject("route")
        val ruleSets = route.getJSONArray("rule_set")
        val sitesDirect = ruleSets.getJSONObject(0)
        assertEquals("sites-direct", sitesDirect.getString("tag"))

        val domainSuffix = sitesDirect.getJSONArray("rules")
            .getJSONObject(0)
            .getJSONArray("domain_suffix")
        assertEquals(2, domainSuffix.length())
        assertEquals("yandex.ru", domainSuffix.getString(0))
        assertEquals("vk.com", domainSuffix.getString(1))
    }

    @Test
    fun testGenerate_DirectDomains_AddedToDomainSuffix() = runTest {
        coEvery { serverConfigDao.get() } returns serverConfigEntity()
        coEvery { customDomainDao.getByMode("direct") } returns listOf(
            CustomDomainEntity(domain = "mail.ru", mode = "direct", isFromServer = false),
            CustomDomainEntity(domain = "ok.ru", mode = "direct", isFromServer = false),
            // Server domain should be filtered out
            CustomDomainEntity(domain = "yandex.ru", mode = "direct", isFromServer = true)
        )
        coEvery { customDomainDao.getByMode("proxy") } returns emptyList()
        coEvery { appRuleDao.getByMode("direct") } returns emptyList()
        coEvery { appRuleDao.getByMode("bypass") } returns emptyList()

        val result = generator.generate()
        val json = JSONObject(result)

        val domainSuffix = json.getJSONObject("route")
            .getJSONArray("rule_set")
            .getJSONObject(0)
            .getJSONArray("rules")
            .getJSONObject(0)
            .getJSONArray("domain_suffix")

        // Original 2 + 2 user domains = 4
        assertEquals(4, domainSuffix.length())
        val allDomains = (0 until domainSuffix.length()).map { domainSuffix.getString(it) }
        assertTrue(allDomains.contains("mail.ru"))
        assertTrue(allDomains.contains("ok.ru"))
    }

    @Test
    fun testGenerate_ProxyDomains_CreatedBeforeSitesDirect() = runTest {
        coEvery { serverConfigDao.get() } returns serverConfigEntity()
        coEvery { customDomainDao.getByMode("direct") } returns emptyList()
        coEvery { customDomainDao.getByMode("proxy") } returns listOf(
            CustomDomainEntity(domain = "netflix.com", mode = "proxy", isFromServer = false),
            CustomDomainEntity(domain = "spotify.com", mode = "proxy", isFromServer = false)
        )
        coEvery { appRuleDao.getByMode("direct") } returns emptyList()
        coEvery { appRuleDao.getByMode("bypass") } returns emptyList()

        val result = generator.generate()
        val json = JSONObject(result)

        val rules = json.getJSONObject("route").getJSONArray("rules")

        // Should have 3 rules now: proxy rule inserted before sites-direct rule + original 2
        assertEquals(3, rules.length())

        // First rule should be the proxy domains rule
        val proxyRule = rules.getJSONObject(0)
        assertEquals("proxy-select", proxyRule.getString("outbound"))
        val proxyDomains = proxyRule.getJSONArray("domain_suffix")
        assertEquals(2, proxyDomains.length())
        assertEquals("netflix.com", proxyDomains.getString(0))
        assertEquals("spotify.com", proxyDomains.getString(1))

        // Second rule should be the original sites-direct rule
        val sitesDirectRule = rules.getJSONObject(1)
        assertTrue(sitesDirectRule.has("rule_set"))
    }

    @Test
    fun testGenerate_DirectApps_AddedToPackageNameAndExclude() = runTest {
        coEvery { serverConfigDao.get() } returns serverConfigEntity()
        coEvery { customDomainDao.getByMode("direct") } returns emptyList()
        coEvery { customDomainDao.getByMode("proxy") } returns emptyList()
        coEvery { appRuleDao.getByMode("direct") } returns listOf(
            AppRuleEntity(packageName = "com.whatsapp", appName = "WhatsApp", mode = "direct")
        )
        coEvery { appRuleDao.getByMode("bypass") } returns emptyList()

        val result = generator.generate()
        val json = JSONObject(result)

        // Check route.rules package_name
        val rules = json.getJSONObject("route").getJSONArray("rules")
        var foundInRoute = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.has("package_name") && rule.optString("outbound") == "direct-out") {
                val pkgs = rule.getJSONArray("package_name")
                val pkgList = (0 until pkgs.length()).map { pkgs.getString(it) }
                assertTrue(pkgList.contains("com.whatsapp"))
                foundInRoute = true
                break
            }
        }
        assertTrue("App should be in route rules", foundInRoute)

        // Check inbounds exclude_package
        val inbounds = json.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        val excludePkg = tun.getJSONArray("exclude_package")
        val excludeList = (0 until excludePkg.length()).map { excludePkg.getString(it) }
        assertTrue(excludeList.contains("com.whatsapp"))
    }

    @Test
    fun testGenerate_BypassApps_OnlyInExcludePackage() = runTest {
        coEvery { serverConfigDao.get() } returns serverConfigEntity()
        coEvery { customDomainDao.getByMode("direct") } returns emptyList()
        coEvery { customDomainDao.getByMode("proxy") } returns emptyList()
        coEvery { appRuleDao.getByMode("direct") } returns emptyList()
        coEvery { appRuleDao.getByMode("bypass") } returns listOf(
            AppRuleEntity(packageName = "com.banking.app", appName = "Banking", mode = "bypass")
        )

        val result = generator.generate()
        val json = JSONObject(result)

        // Should NOT be in route.rules package_name
        val rules = json.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.has("package_name")) {
                val pkgs = rule.getJSONArray("package_name")
                val pkgList = (0 until pkgs.length()).map { pkgs.getString(it) }
                assertTrue(
                    "Bypass app should NOT be in route rules",
                    !pkgList.contains("com.banking.app")
                )
            }
        }

        // Should be in inbounds exclude_package
        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        val excludePkg = tun.getJSONArray("exclude_package")
        val excludeList = (0 until excludePkg.length()).map { excludePkg.getString(it) }
        assertTrue(excludeList.contains("com.banking.app"))
    }

    @Test
    fun testGenerate_ServerDomains_NotDuplicated() = runTest {
        coEvery { serverConfigDao.get() } returns serverConfigEntity()
        // Return server domains — they should be filtered out (isFromServer = true)
        coEvery { customDomainDao.getByMode("direct") } returns listOf(
            CustomDomainEntity(domain = "yandex.ru", mode = "direct", isFromServer = true),
            CustomDomainEntity(domain = "vk.com", mode = "direct", isFromServer = true)
        )
        coEvery { customDomainDao.getByMode("proxy") } returns emptyList()
        coEvery { appRuleDao.getByMode("direct") } returns emptyList()
        coEvery { appRuleDao.getByMode("bypass") } returns emptyList()

        val result = generator.generate()
        val json = JSONObject(result)

        val domainSuffix = json.getJSONObject("route")
            .getJSONArray("rule_set")
            .getJSONObject(0)
            .getJSONArray("rules")
            .getJSONObject(0)
            .getJSONArray("domain_suffix")

        // Should still have only original 2 domains, no duplicates
        assertEquals(2, domainSuffix.length())
    }

    @Test
    fun testGenerate_EmptyServerConfig_Throws() = runTest {
        coEvery { serverConfigDao.get() } returns null

        try {
            generator.generate()
            assertTrue("Should have thrown IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Конфиг не загружен"))
        }
    }
}
