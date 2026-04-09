package com.sbcfg.manager.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.domain.ConfigGenerator
import com.sbcfg.manager.vpn.BoxService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var configGenerator: ConfigGenerator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoStart = appPreferences.autoStart.first()
                if (!autoStart) return@launch

                val configJson = try {
                    configGenerator.generate()
                } catch (_: Exception) {
                    return@launch  // No config available
                }

                withContext(Dispatchers.Main) {
                    BoxService.start(context, configJson)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
