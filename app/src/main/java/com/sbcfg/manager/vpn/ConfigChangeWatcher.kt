package com.sbcfg.manager.vpn

import android.content.Context
import com.sbcfg.manager.constant.Status
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes app-rule and custom-domain changes; whenever the user edits routing
 * configuration AND the VPN is currently running, regenerates the sing-box config
 * and restarts the tunnel so the changes take effect immediately.
 *
 * A full restart is required (not just a sing-box reload) because the
 * include_package whitelist is enforced at Android VpnService.Builder level —
 * once `establish()` returns, the per-app filter is frozen until the next
 * `establish()` call.
 */
@Singleton
class ConfigChangeWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configManager: ConfigManager
) {
    companion object {
        private const val TAG = "ConfigChangeWatcher"
        private const val DEBOUNCE_MS = 1500L
    }

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            combine(
                configManager.observeAppRules(),
                configManager.observeDomains()
            ) { rules, domains -> rules.size to domains.size }
                .drop(1) // initial snapshot — not a real change
                .debounce(DEBOUNCE_MS)
                .collect {
                    if (BoxService.status.value != Status.Started) {
                        AppLog.i(TAG, "Rules changed but VPN not running, skipping")
                        return@collect
                    }
                    try {
                        AppLog.i(TAG, "Rules changed, regenerating config and restarting VPN")
                        val newConfig = configManager.generateConfigJson()
                        BoxService.restart(context, newConfig)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Restart on config change failed", e)
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
