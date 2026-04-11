package com.sbcfg.manager.domain

import android.content.Context
import com.sbcfg.manager.data.local.dao.AppRuleDao
import com.sbcfg.manager.data.local.dao.CustomDomainDao
import com.sbcfg.manager.data.local.dao.ServerConfigDao
import com.sbcfg.manager.data.local.entity.AppRuleEntity
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.data.local.entity.ServerConfigEntity
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.data.remote.ConfigApiClient
import com.sbcfg.manager.domain.model.AppMode
import com.sbcfg.manager.domain.model.ConfigState
import com.sbcfg.manager.domain.model.DomainMode
import com.sbcfg.manager.domain.model.ServerInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigManager @Inject constructor(
    private val apiClient: ConfigApiClient,
    private val serverConfigDao: ServerConfigDao,
    private val customDomainDao: CustomDomainDao,
    private val appRuleDao: AppRuleDao,
    private val configGenerator: ConfigGenerator,
    private val appPreferences: AppPreferences
) {
    suspend fun fetchAndSaveConfig(configUrl: String): Result<ServerInfo> {
        return try {
            val jsonString = apiClient.fetchConfig(configUrl).getOrThrow()

            // Validate JSON
            val json = try {
                JSONObject(jsonString)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("Получен некорректный конфиг"))
            }

            // Validate required sections
            val requiredSections = listOf("dns", "inbounds", "outbounds", "route")
            for (section in requiredSections) {
                if (!json.has(section)) {
                    return Result.failure(
                        IllegalStateException("Некорректный конфиг: отсутствует секция '$section'")
                    )
                }
            }

            // Extract server info from outbounds
            val serverInfo = extractServerInfo(json, configUrl)
                ?: return Result.failure(IllegalStateException("Не найден прокси-сервер в конфиге"))

            // Save server config
            serverConfigDao.upsert(
                ServerConfigEntity(
                    url = configUrl,
                    rawJson = jsonString,
                    serverName = serverInfo.serverName,
                    protocol = serverInfo.protocol,
                    fetchedAt = System.currentTimeMillis()
                )
            )

            // Extract and save server domains from route.rule_set[tag=sites-direct]
            extractAndSaveServerDomains(json)

            // Extract and save server app rules from inbounds[type=tun].exclude_package
            extractAndSaveServerAppRules(json)

            // Save URL in preferences
            appPreferences.setConfigUrl(configUrl)

            Result.success(serverInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshConfig(): Result<ServerInfo> {
        val url = appPreferences.configUrl.first()
            ?: return Result.failure(IllegalStateException("URL конфига не найден"))

        return try {
            val jsonString = apiClient.fetchConfig(url).getOrThrow()
            val json = JSONObject(jsonString)

            val serverInfo = extractServerInfo(json, url)
                ?: return Result.failure(IllegalStateException("Не найден прокси-сервер в конфиге"))

            // Delete old server domains and rules
            customDomainDao.deleteAllServerDomains()
            appRuleDao.deleteAllServerRules()

            // Save updated config
            serverConfigDao.upsert(
                ServerConfigEntity(
                    url = url,
                    rawJson = jsonString,
                    serverName = serverInfo.serverName,
                    protocol = serverInfo.protocol,
                    fetchedAt = System.currentTimeMillis()
                )
            )

            // Extract new server domains, skip if user already has a rule for same domain
            extractAndSaveServerDomains(json)
            extractAndSaveServerAppRules(json)

            Result.success(serverInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // CRUD for domains
    suspend fun addDomainRule(domain: String, mode: DomainMode): Result<Unit> {
        if (customDomainDao.exists(domain)) {
            return Result.failure(IllegalStateException("Домен уже добавлен"))
        }
        customDomainDao.insert(
            CustomDomainEntity(
                domain = domain,
                mode = mode.name.lowercase()
            )
        )
        return Result.success(Unit)
    }

    suspend fun removeDomainRule(domainEntity: CustomDomainEntity): Result<Unit> {
        if (domainEntity.isFromServer) {
            return Result.failure(IllegalStateException("Нельзя удалить серверный домен"))
        }
        customDomainDao.delete(domainEntity)
        return Result.success(Unit)
    }

    fun observeDomains(): Flow<List<CustomDomainEntity>> = customDomainDao.observeAll()

    // CRUD for apps
    suspend fun setAppMode(packageName: String, appName: String, mode: AppMode) {
        appRuleDao.upsert(
            AppRuleEntity(
                packageName = packageName,
                appName = appName,
                mode = mode.name.lowercase()
            )
        )
    }

    suspend fun removeAppRule(packageName: String) {
        appRuleDao.deleteByPackage(packageName)
    }

    fun observeAppRules(): Flow<List<AppRuleEntity>> = appRuleDao.observeAll()

    // Config generation
    suspend fun generateConfigJson(): String = configGenerator.generate()

    suspend fun generateConfig(context: Context): File = configGenerator.generateToFile(context)

    // State observation
    suspend fun hasConfig(): Boolean = serverConfigDao.get() != null

    fun observeConfigState(): Flow<ConfigState> = serverConfigDao.observe().map { entity ->
        if (entity == null) {
            ConfigState.NotConfigured
        } else {
            ConfigState.Loaded(
                ServerInfo(
                    serverName = entity.serverName,
                    protocol = entity.protocol,
                    url = entity.url
                )
            )
        }
    }

    private val proxyOutboundTypes = setOf(
        "naive", "hysteria2", "hysteria", "vless", "vmess", "trojan",
        "shadowsocks", "shadowtls", "tuic", "wireguard"
    )

    private fun extractServerInfo(json: JSONObject, url: String): ServerInfo? {
        val outbounds = json.getJSONArray("outbounds")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            val type = outbound.optString("type")
            if (type in proxyOutboundTypes) {
                return ServerInfo(
                    serverName = outbound.optString("server", "unknown"),
                    protocol = type,
                    url = url
                )
            }
        }
        return null
    }

    private suspend fun extractAndSaveServerDomains(json: JSONObject) {
        val route = json.optJSONObject("route") ?: return
        val ruleSets = route.optJSONArray("rule_set") ?: return

        for (i in 0 until ruleSets.length()) {
            val ruleSet = ruleSets.getJSONObject(i)
            if (ruleSet.optString("tag") == "sites-direct") {
                val rules = ruleSet.optJSONArray("rules") ?: continue
                if (rules.length() > 0) {
                    val firstRule = rules.getJSONObject(0)
                    val domainSuffix = firstRule.optJSONArray("domain_suffix") ?: continue
                    for (j in 0 until domainSuffix.length()) {
                        val domain = domainSuffix.getString(j)
                        // Skip if user already has a rule for this domain
                        if (!customDomainDao.exists(domain)) {
                            customDomainDao.insert(
                                CustomDomainEntity(
                                    domain = domain,
                                    mode = "direct",
                                    isFromServer = true
                                )
                            )
                        }
                    }
                }
                break
            }
        }
    }

    private suspend fun extractAndSaveServerAppRules(json: JSONObject) {
        val inbounds = json.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") continue

            // New model: server template uses include_package to specify
            // apps that should be tunneled by default. They get "direct" mode
            // (through tunnel + domain-based routing).
            val includePackage = inbound.optJSONArray("include_package")
            if (includePackage != null) {
                for (j in 0 until includePackage.length()) {
                    val pkg = includePackage.getString(j)
                    // Skip if user already has a rule for this package
                    if (!appRuleDao.existsUserRule(pkg)) {
                        appRuleDao.upsert(
                            AppRuleEntity(
                                packageName = pkg,
                                appName = pkg,
                                mode = "direct",
                                isFromServer = true
                            )
                        )
                    }
                }
            }
            break
        }
    }
}
