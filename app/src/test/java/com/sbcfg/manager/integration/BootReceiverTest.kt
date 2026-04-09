package com.sbcfg.manager.integration

import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.domain.ConfigGenerator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for BootReceiver logic.
 *
 * Since BootReceiver uses Hilt injection and BroadcastReceiver lifecycle,
 * we test the decision logic in isolation: given autoStart preference and
 * config availability, determine whether VPN should start.
 */
class BootReceiverTest {

    private val appPreferences = mockk<AppPreferences>()
    private val configGenerator = mockk<ConfigGenerator>()

    /**
     * Replicates BootReceiver.onReceive decision logic:
     * returns config JSON if autoStart=true and config is available, null otherwise.
     */
    private suspend fun resolveBootConfig(): String? {
        val autoStart = appPreferences.autoStart.first()
        if (!autoStart) return null
        return try {
            configGenerator.generate()
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun testAutoStartEnabled_WithConfig_ReturnsConfig() = runTest {
        coEvery { appPreferences.autoStart } returns flowOf(true)
        coEvery { configGenerator.generate() } returns """{"inbounds":[]}"""

        val config = resolveBootConfig()
        assertNotNull(config)
    }

    @Test
    fun testAutoStartDisabled_ReturnsNull() = runTest {
        coEvery { appPreferences.autoStart } returns flowOf(false)

        val config = resolveBootConfig()
        assertNull(config)
    }

    @Test
    fun testAutoStartEnabled_NoConfig_ReturnsNull() = runTest {
        coEvery { appPreferences.autoStart } returns flowOf(true)
        coEvery { configGenerator.generate() } throws IllegalStateException("No config")

        val config = resolveBootConfig()
        assertNull(config)
    }
}
