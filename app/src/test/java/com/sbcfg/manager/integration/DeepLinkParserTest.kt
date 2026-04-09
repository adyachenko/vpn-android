package com.sbcfg.manager.integration

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkParserTest {

    @Test
    fun testParse_SbcfgScheme_ExtractsUrl() {
        val uri = Uri.parse("sbcfg://proxy.example.com/api/config/TOKEN")
        val result = DeepLinkHandler.parse(uri)

        assertNotNull(result)
        assertEquals("https://proxy.example.com/api/config/TOKEN", result!!.configUrl)
    }

    @Test
    fun testParse_HttpsScheme_ExtractsUrl() {
        val uri = Uri.parse("https://proxy.example.com/api/config/TOKEN")
        val result = DeepLinkHandler.parse(uri)

        assertNotNull(result)
        assertEquals("https://proxy.example.com/api/config/TOKEN", result!!.configUrl)
    }

    @Test
    fun testParse_InvalidUri_ReturnsNull() {
        val uri = Uri.parse("content://something/random")
        val result = DeepLinkHandler.parse(uri)

        assertNull(result)
    }

    @Test
    fun testParse_WithParams_ExtractsNameAndAutoConnect() {
        val uri = Uri.parse("sbcfg://proxy.example.com/api/config/TOKEN?name=MyVPN&auto_connect=true")
        val result = DeepLinkHandler.parse(uri)

        assertNotNull(result)
        assertEquals("https://proxy.example.com/api/config/TOKEN", result!!.configUrl)
        assertEquals("MyVPN", result.name)
        assertTrue(result.autoConnect)
    }
}
