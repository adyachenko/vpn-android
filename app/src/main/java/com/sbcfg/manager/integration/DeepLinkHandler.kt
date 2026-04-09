package com.sbcfg.manager.integration

import android.net.Uri

data class DeepLinkResult(
    val configUrl: String,
    val name: String? = null,
    val autoConnect: Boolean = false
)

object DeepLinkHandler {

    fun parse(uri: Uri?): DeepLinkResult? {
        uri ?: return null

        return when (uri.scheme) {
            "sbcfg" -> parseSbcfgScheme(uri)
            "https", "http" -> parseHttpScheme(uri)
            else -> null
        }
    }

    private fun parseSbcfgScheme(uri: Uri): DeepLinkResult? {
        // sbcfg://host/api/config/TOKEN -> https://host/api/config/TOKEN
        val host = uri.host ?: return null
        val path = uri.path ?: return null

        if (!path.contains("/api/config/")) return null

        val configUrl = "https://$host$path"
        return DeepLinkResult(
            configUrl = configUrl,
            name = uri.getQueryParameter("name"),
            autoConnect = uri.getQueryParameter("auto_connect") == "true"
        )
    }

    private fun parseHttpScheme(uri: Uri): DeepLinkResult? {
        val url = uri.toString()
        if (!url.contains("/api/config/")) return null

        return DeepLinkResult(
            configUrl = url,
            name = uri.getQueryParameter("name"),
            autoConnect = uri.getQueryParameter("auto_connect") == "true"
        )
    }
}
