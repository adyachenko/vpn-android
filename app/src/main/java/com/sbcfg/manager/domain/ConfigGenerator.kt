package com.sbcfg.manager.domain

import android.content.Context
import com.sbcfg.manager.data.local.dao.AppRuleDao
import com.sbcfg.manager.data.local.dao.CustomDomainDao
import com.sbcfg.manager.data.local.dao.ServerConfigDao
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverConfigDao: ServerConfigDao,
    private val customDomainDao: CustomDomainDao,
    private val appRuleDao: AppRuleDao
) {
    suspend fun generate(): String {
        val config = serverConfigDao.get()
            ?: throw IllegalStateException("Конфиг не загружен")

        val json = JSONObject(config.rawJson)

        // Enable logging to file for debugging.
        // Temporary: level=debug to capture issue #001 (silent DNS handler
        // death). Revert to "info" once the root cause is found.
        val log = json.optJSONObject("log") ?: JSONObject().also { json.put("log", it) }
        log.put("level", "debug")
        log.put("output", "${context.filesDir.absolutePath}/sing-box.log")

        // Migrate legacy tun fields (removed in sing-box 1.12.0)
        migrateLegacyTunFields(json)

        // Add "action": "route" to DNS rules (required in sing-box 1.13)
        migrateDnsRuleActions(json)

        // Convert plain DNS to DoH for servers going through proxy
        ensureDnsOverHttps(json)

        // Ensure DNS servers have address_resolver for domain outbound resolution
        ensureDnsAddressResolver(json)

        // Ensure outbounds with domain server addresses have domain_resolver
        ensureOutboundDomainResolver(json)

        // Ensure tun inbound has stack and sniff enabled
        ensureTunSniffing(json)

        // Remove empty package_name rules (they match ALL traffic)
        removeEmptyPackageNameRules(json)

        // Remove auto_detect_interface — handled by platform interface on Android
        val route = json.optJSONObject("route")
        if (route != null && route.has("auto_detect_interface")) {
            route.remove("auto_detect_interface")
            AppLog.i("ConfigGen", "Removed auto_detect_interface (platform handles this)")
        }

        // Ensure DNS and route have proper defaults
        ensureDnsAndRouteDefaults(json)

        // Get user-added domains (not from server)
        val userDirectDomains = customDomainDao.getByMode("direct")
            .filter { !it.isFromServer }
            .map { it.domain }
        val userProxyDomains = customDomainDao.getByMode("proxy")
            .filter { !it.isFromServer }
            .map { it.domain }

        // Get app rules
        val proxyApps = appRuleDao.getByMode("proxy")
            .map { it.packageName }
        val directApps = appRuleDao.getByMode("direct")
            .map { it.packageName }

        // 1. Add direct domains to route.rule_set[tag="sites-direct"].rules[0].domain_suffix
        if (userDirectDomains.isNotEmpty()) {
            addDirectDomains(json, userDirectDomains)
        }

        // 2. Add proxy domains as a new rule BEFORE sites-direct rule in route.rules
        if (userProxyDomains.isNotEmpty()) {
            addProxyDomainsRule(json, userProxyDomains)
        }

        // 3. Configure include_package on TUN inbound
        // - "proxy" apps go through tunnel and ALL traffic forced to proxy outbound
        // - "direct" apps go through tunnel with normal domain-based routing
        // - All other apps (default = bypass) are NOT in include_package -> outside VPN entirely
        // CRITICAL: include_package must be NON-EMPTY, otherwise Android VpnService falls
        // back to "tunnel everything" because no addAllowedApplication() call is made.
        // When user has no apps selected, we use a placeholder package name that doesn't
        // exist on the device — this keeps the whitelist active but matches nothing.
        val tunneledApps = (proxyApps + directApps).distinct().ifEmpty {
            listOf("com.sbcfg.manager.placeholder.no_apps_selected")
        }
        applyIncludePackage(json, tunneledApps)

        // 4. Add route rule forcing proxy outbound for "proxy" apps
        if (proxyApps.isNotEmpty()) {
            addProxyAppsRule(json, proxyApps)
        }

        // Enable Clash API for traffic monitoring (localhost only)
        ensureClashApi(json)

        val result = json.toString(2)
        AppLog.i("ConfigGen", "Config generated, length=${result.length}")
        return result
    }

    suspend fun generateToFile(context: Context): File {
        val configJson = generate()
        val file = File(context.filesDir, "singbox-config.json")
        file.writeText(configJson)
        return file
    }

    /**
     * Remove route rules with empty package_name array — they match ALL traffic.
     */
    private fun removeEmptyPackageNameRules(json: JSONObject) {
        val route = json.optJSONObject("route") ?: return
        val rules = route.optJSONArray("rules") ?: return
        val newRules = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val pkgName = rule.optJSONArray("package_name")
            if (pkgName != null && pkgName.length() == 0) {
                AppLog.i("ConfigGen", "Removed empty package_name rule (matched all traffic)")
                continue
            }
            newRules.put(rule)
        }
        route.put("rules", newRules)
    }

    /**
     * Ensure DNS has final server and route has default final outbound.
     */
    private fun ensureDnsAndRouteDefaults(json: JSONObject) {
        // Ensure dns.final is set
        val dns = json.optJSONObject("dns")
        if (dns != null && !dns.has("final")) {
            val servers = dns.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val firstTag = servers.getJSONObject(0).optString("tag")
                if (firstTag.isNotEmpty()) {
                    dns.put("final", firstTag)
                    AppLog.i("ConfigGen", "Added dns.final=$firstTag")
                }
            }
        }
        if (dns != null && !dns.has("independent_cache")) {
            dns.put("independent_cache", true)
        }

        // Ensure route.final is set (default outbound for unmatched traffic)
        val route = json.optJSONObject("route")
        if (route != null && !route.has("final")) {
            route.put("final", "proxy-select")
            AppLog.i("ConfigGen", "Added route.final=proxy-select")
        }
    }

    /**
     * Ensure tun inbound has stack set and sniffing is configured via route rules.
     * In sing-box 1.13, sniff/sniff_override_destination in inbound are removed —
     * must use route rule actions instead.
     */
    private fun ensureTunSniffing(json: JSONObject) {
        val inbounds = json.optJSONArray("inbounds") ?: return
        var tunTag: String? = null
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") continue

            // Force gvisor TCP/IP stack on Android. The "system" (and "mixed")
            // stack tries to bind real sockets to the underlying interface via
            // setsockopt(SO_BINDTODEVICE), which requires root on Android and
            // fails with "operation not permitted" — as a result TCP packets
            // from tunneled apps never reach sing-box (only UDP/DNS does).
            // gvisor is a pure userspace stack that doesn't need that perm.
            val currentStack = inbound.optString("stack")
            if (currentStack != "gvisor") {
                inbound.put("stack", "gvisor")
                AppLog.i("ConfigGen", "Forced stack=gvisor (was '$currentStack')")
            }

            // strict_route=true on Android + per-app VPN can drop packets that
            // don't match the configured routes. Turn it off so packets from
            // tunneled apps reach the TUN inbound unconditionally.
            if (inbound.optBoolean("strict_route", false)) {
                inbound.put("strict_route", false)
                AppLog.i("ConfigGen", "Disabled strict_route on tun inbound")
            }

            // Remove legacy sniff fields (removed in 1.13)
            if (inbound.has("sniff")) {
                inbound.remove("sniff")
                AppLog.i("ConfigGen", "Removed legacy sniff from tun inbound")
            }
            if (inbound.has("sniff_override_destination")) {
                inbound.remove("sniff_override_destination")
            }
            if (inbound.has("sniff_timeout")) {
                inbound.remove("sniff_timeout")
            }
            if (inbound.has("domain_strategy")) {
                inbound.remove("domain_strategy")
            }

            tunTag = inbound.optString("tag", "tun-in")
        }

        if (tunTag == null) return

        // Add sniff rule action to route.rules if not already present
        val route = json.optJSONObject("route") ?: return
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }

        // Check if sniff action already exists
        var hasSniffAction = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("action") == "sniff") {
                hasSniffAction = true
                break
            }
        }

        if (!hasSniffAction) {
            // Insert sniff + hijack-dns rules at the beginning (before routing rules)
            val sniffRule = JSONObject().apply { put("action", "sniff") }
            val hijackDnsRule = JSONObject().apply {
                put("protocol", "dns")
                put("action", "hijack-dns")
            }
            val newRules = JSONArray()
            newRules.put(sniffRule)
            newRules.put(hijackDnsRule)
            for (i in 0 until rules.length()) {
                newRules.put(rules.get(i))
            }
            route.put("rules", newRules)
            AppLog.i("ConfigGen", "Added sniff + hijack-dns action rules to route.rules")
        } else {
            // Sniff exists, check if hijack-dns exists
            var hasHijackDns = false
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (rule.optString("action") == "hijack-dns") {
                    hasHijackDns = true
                    break
                }
            }
            if (!hasHijackDns) {
                // Insert hijack-dns right after sniff
                val newRules = JSONArray()
                for (i in 0 until rules.length()) {
                    newRules.put(rules.get(i))
                    if (rules.getJSONObject(i).optString("action") == "sniff") {
                        val hijackDnsRule = JSONObject().apply {
                            put("protocol", "dns")
                            put("action", "hijack-dns")
                        }
                        newRules.put(hijackDnsRule)
                    }
                }
                route.put("rules", newRules)
                AppLog.i("ConfigGen", "Added hijack-dns action rule after sniff")
            }
        }
    }

    /**
     * Ensure outbounds with domain server addresses have domain_resolver set.
     * Required in sing-box 1.12+ (mandatory in 1.14+).
     */
    private fun ensureOutboundDomainResolver(json: JSONObject) {
        val outbounds = json.optJSONArray("outbounds") ?: return

        // Find a direct DNS server tag to use as resolver
        val dns = json.optJSONObject("dns")
        val dnsServers = dns?.optJSONArray("servers")
        var directDnsTag: String? = null
        if (dnsServers != null) {
            for (i in 0 until dnsServers.length()) {
                val server = dnsServers.getJSONObject(i)
                val detour = server.optString("detour")
                if (detour == "direct-out" || detour.contains("direct")) {
                    directDnsTag = server.optString("tag")
                    break
                }
            }
        }
        if (directDnsTag == null) return

        val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$|^\[?[0-9a-fA-F:]+]?$""")

        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            val server = outbound.optString("server")
            if (server.isNotEmpty() && !ipPattern.matches(server) && !outbound.has("domain_resolver")) {
                // Server is a domain name — needs domain_resolver
                outbound.put("domain_resolver", directDnsTag)
                AppLog.i("ConfigGen", "Added domain_resolver=$directDnsTag to outbound '${outbound.optString("tag")}'")
            }
        }
    }

    /**
     * Add "action": "route" to DNS rules that have "server" but no "action".
     * Required in sing-box 1.11+ (mandatory in 1.13+).
     */
    private fun migrateDnsRuleActions(json: JSONObject) {
        val dns = json.optJSONObject("dns") ?: return
        val rules = dns.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.has("server") && !rule.has("action")) {
                rule.put("action", "route")
                AppLog.i("ConfigGen", "Added action=route to DNS rule with server='${rule.optString("server")}'")
            }
        }
    }

    /**
     * Ensure DNS servers going through proxy use DoH (not plain UDP).
     * Naive proxy only supports TCP/HTTP — plain DNS over UDP won't work
     * through it. Hysteria2 does carry UDP, but a half-dead QUIC session
     * can black-hole DNS queries silently; DoH over TCP survives that case.
     *
     * Matches pure IP (`1.1.1.1`), explicit `udp://1.1.1.1`, and optional
     * port suffix — all three forms would route as UDP. Already-DoH
     * (`https://...`), DoT (`tls://...`), and TCP (`tcp://...`) addresses
     * are left untouched.
     */
    private fun ensureDnsOverHttps(json: JSONObject) {
        val dns = json.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers") ?: return
        val udpIpPattern = Regex("""^(?:udp://)?(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::\d+)?$""")

        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val detour = server.optString("detour")
            val address = server.optString("address")
            if (detour.isEmpty() || detour.contains("direct")) continue

            val m = udpIpPattern.matchEntire(address) ?: continue
            val ip = m.groupValues[1]
            val dohAddress = "https://$ip/dns-query"
            server.put("address", dohAddress)
            AppLog.i("ConfigGen", "Converted UDP DNS $address to DoH $dohAddress for proxy detour")
        }
    }

    /**
     * Ensure DNS servers that use domain addresses have address_resolver set.
     * Required in sing-box 1.12+ for resolving outbound server domain names.
     */
    private fun ensureDnsAddressResolver(json: JSONObject) {
        val dns = json.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers") ?: return

        // Find a direct DNS server (one that uses direct-out detour or has IP address)
        var directDnsTag: String? = null
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val detour = server.optString("detour")
            if (detour == "direct-out" || detour.contains("direct")) {
                directDnsTag = server.optString("tag")
                break
            }
        }
        if (directDnsTag == null) return

        // Add address_resolver to servers that don't have it and use a proxy detour
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            if (server.optString("tag") == directDnsTag) continue
            if (!server.has("address_resolver")) {
                server.put("address_resolver", directDnsTag)
                AppLog.i("ConfigGen", "Added address_resolver=$directDnsTag to DNS server '${server.optString("tag")}'")
            }
        }
    }

    /**
     * Migrate legacy tun inbound fields to new format.
     * sing-box 1.10.0 deprecated inet4_address/inet6_address string fields,
     * sing-box 1.12.0 removed them. New format uses "address" array.
     */
    private fun migrateLegacyTunFields(json: JSONObject) {
        val inbounds = json.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") continue

            val addresses = JSONArray()

            // Migrate inet4_address (string or array)
            if (inbound.has("inet4_address")) {
                val v = inbound.get("inet4_address")
                if (v is String) addresses.put(v)
                else if (v is JSONArray) for (j in 0 until v.length()) addresses.put(v.getString(j))
                inbound.remove("inet4_address")
                AppLog.i("ConfigGen", "Migrated inet4_address to address[]")
            }

            // Migrate inet6_address (string or array)
            if (inbound.has("inet6_address")) {
                val v = inbound.get("inet6_address")
                if (v is String) addresses.put(v)
                else if (v is JSONArray) for (j in 0 until v.length()) addresses.put(v.getString(j))
                inbound.remove("inet6_address")
                AppLog.i("ConfigGen", "Migrated inet6_address to address[]")
            }

            // Set new "address" field if not already present
            if (addresses.length() > 0 && !inbound.has("address")) {
                inbound.put("address", addresses)
                AppLog.i("ConfigGen", "Set address=$addresses")
            }

            // Also migrate inet4_route_address / inet6_route_address → route_address
            val routeAddresses = JSONArray()
            if (inbound.has("inet4_route_address")) {
                val v = inbound.get("inet4_route_address")
                if (v is String) routeAddresses.put(v)
                else if (v is JSONArray) for (j in 0 until v.length()) routeAddresses.put(v.getString(j))
                inbound.remove("inet4_route_address")
            }
            if (inbound.has("inet6_route_address")) {
                val v = inbound.get("inet6_route_address")
                if (v is String) routeAddresses.put(v)
                else if (v is JSONArray) for (j in 0 until v.length()) routeAddresses.put(v.getString(j))
                inbound.remove("inet6_route_address")
            }
            if (routeAddresses.length() > 0 && !inbound.has("route_address")) {
                inbound.put("route_address", routeAddresses)
            }

            // Migrate inet4_route_exclude_address / inet6_route_exclude_address → route_exclude_address
            val routeExclude = JSONArray()
            if (inbound.has("inet4_route_exclude_address")) {
                val v = inbound.get("inet4_route_exclude_address")
                if (v is String) routeExclude.put(v)
                else if (v is JSONArray) for (j in 0 until v.length()) routeExclude.put(v.getString(j))
                inbound.remove("inet4_route_exclude_address")
            }
            if (inbound.has("inet6_route_exclude_address")) {
                val v = inbound.get("inet6_route_exclude_address")
                if (v is String) routeExclude.put(v)
                else if (v is JSONArray) for (j in 0 until v.length()) routeExclude.put(v.getString(j))
                inbound.remove("inet6_route_exclude_address")
            }
            if (routeExclude.length() > 0 && !inbound.has("route_exclude_address")) {
                inbound.put("route_exclude_address", routeExclude)
            }
        }
    }

    private fun addDirectDomains(json: JSONObject, domains: List<String>) {
        val route = json.getJSONObject("route")
        val ruleSets = route.getJSONArray("rule_set")
        for (i in 0 until ruleSets.length()) {
            val ruleSet = ruleSets.getJSONObject(i)
            if (ruleSet.optString("tag") == "sites-direct") {
                val rules = ruleSet.getJSONArray("rules")
                if (rules.length() > 0) {
                    val firstRule = rules.getJSONObject(0)
                    val domainSuffix = firstRule.getJSONArray("domain_suffix")
                    for (domain in domains) {
                        domainSuffix.put(domain)
                    }
                }
                break
            }
        }
    }

    private fun addProxyDomainsRule(json: JSONObject, domains: List<String>) {
        val route = json.getJSONObject("route")
        val rules = route.getJSONArray("rules")

        val proxyRule = JSONObject().apply {
            put("domain_suffix", JSONArray(domains))
            put("outbound", "proxy-select")
        }

        // Find the index of the rule that references sites-direct
        var insertIndex = rules.length()
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.has("rule_set")) {
                val ruleSets = rule.get("rule_set")
                if (ruleSets is JSONArray) {
                    for (j in 0 until ruleSets.length()) {
                        if (ruleSets.getString(j) == "sites-direct") {
                            insertIndex = i
                            break
                        }
                    }
                }
            }
        }

        // Rebuild rules array with proxy rule inserted
        val newRules = JSONArray()
        for (i in 0 until rules.length()) {
            if (i == insertIndex) {
                newRules.put(proxyRule)
            }
            newRules.put(rules.getJSONObject(i))
        }
        if (insertIndex >= rules.length()) {
            newRules.put(proxyRule)
        }
        route.put("rules", newRules)
    }

    /**
     * Apply include_package mode on TUN inbound — only listed packages route through VPN.
     * All other apps (and the SBoxy app itself) bypass the tunnel automatically.
     * Also strips any leftover exclude_package from the template, since the two are mutually exclusive.
     */
    private fun applyIncludePackage(json: JSONObject, packageNames: List<String>) {
        val inbounds = json.getJSONArray("inbounds")
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") continue

            // Remove exclude_package — incompatible with include_package
            if (inbound.has("exclude_package")) {
                inbound.remove("exclude_package")
                AppLog.i("ConfigGen", "Removed exclude_package (using include_package mode)")
            }

            // Merge with any include_package already set in template
            val include = inbound.optJSONArray("include_package") ?: JSONArray().also {
                inbound.put("include_package", it)
            }
            val existing = mutableSetOf<String>()
            for (j in 0 until include.length()) existing.add(include.getString(j))
            for (pkg in packageNames) {
                if (existing.add(pkg)) include.put(pkg)
            }
            AppLog.i("ConfigGen", "include_package set: ${existing.size} apps tunneled")
            break
        }
    }

    /**
     * Insert a route rule that forces all traffic from given packages to the proxy outbound.
     * Placed at the top of route.rules so it overrides domain-based rules.
     */
    private fun ensureClashApi(json: JSONObject) {
        val experimental = json.optJSONObject("experimental") ?: JSONObject().also {
            json.put("experimental", it)
        }
        val clashApi = experimental.optJSONObject("clash_api") ?: JSONObject().also {
            experimental.put("clash_api", it)
        }
        val port = try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) {
            10000 + (System.nanoTime() % 50000).toInt().let { if (it < 0) -it else it }
        }
        clashApi.put("external_controller", "127.0.0.1:$port")
        // Secret disabled for now — sing-box may hang on auth header
        clashApi.remove("secret")
        AppLog.i("ConfigGen", "Clash API enabled on 127.0.0.1:$port")
    }

    private fun addProxyAppsRule(json: JSONObject, packageNames: List<String>) {
        val route = json.getJSONObject("route")
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }

        val proxyAppsRule = JSONObject().apply {
            put("package_name", JSONArray(packageNames))
            put("outbound", "proxy-select")
        }

        // Insert AFTER sniff/hijack-dns actions but BEFORE other routing rules
        val newRules = JSONArray()
        var inserted = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val action = rule.optString("action")
            if (!inserted && action != "sniff" && action != "hijack-dns") {
                newRules.put(proxyAppsRule)
                inserted = true
            }
            newRules.put(rule)
        }
        if (!inserted) newRules.put(proxyAppsRule)
        route.put("rules", newRules)
        AppLog.i("ConfigGen", "Added proxy apps route rule for ${packageNames.size} apps")
    }
}
