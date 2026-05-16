package com.sbcfg.manager.vpn

import com.sbcfg.manager.util.AppLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "ClashApiClient"

data class TrafficSnapshot(
    val uploadTotal: Long,
    val downloadTotal: Long,
    val activeConnections: Int,
    val memory: Long,
    val byOutbound: Map<String, OutboundTraffic>,
)

data class OutboundTraffic(
    val name: String,
    val upload: Long,
    val download: Long,
    val connectionCount: Int,
)

data class ConnectionsSnapshot(
    val uploadTotal: Long,
    val downloadTotal: Long,
    val connections: List<ConnectionInfo>,
)

data class ConnectionInfo(
    val host: String,
    val destinationPort: Int,
    val network: String,
    val chain: String,
    val rule: String,
    val upload: Long,
    val download: Long,
    val start: String,
    val process: String,
)

class ClashApiClient(
    private val baseUrl: String = "http://127.0.0.1:9090",
    private val secret: String = "",
) {
    private fun getRawBody(path: String): String {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Accept", "application/json")
        if (secret.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $secret")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw IllegalStateException("HTTP $code for $path: $errBody")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Переключить активный outbound в селекторе [group] на [optionName].
     * Стандартный Clash API: PUT /proxies/{group} body {"name": "..."}.
     * sing-box возвращает 204 No Content при успехе.
     */
    fun selectOutbound(group: String, optionName: String) {
        val url = URL("$baseUrl/proxies/$group")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (secret.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $secret")
        }
        try {
            val body = JSONObject().put("name", optionName).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw IllegalStateException("HTTP $code for PUT /proxies/$group: $errBody")
            }
            AppLog.i(TAG, "Selector $group → $optionName (HTTP $code)")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Синхронно прогоняет HTTP-проверку через указанный outbound (Clash-API delay-test).
     * Endpoint: `GET /proxies/{name}/delay?url=...&timeout=...`. sing-box физически
     * устанавливает соединение к outbound'у и делает GET по [url]. Это используется
     * как «прогрев» outbound'а перед переключением на него selector'а — гарантирует,
     * что h2/QUIC-сессия уже поднята до того как туда пойдёт реальный трафик.
     *
     * @param timeoutMs таймаут проверки на стороне sing-box (мс). HTTP read-timeout
     *   локального соединения с Clash API выставляется на [timeoutMs] + 2000, чтобы
     *   гарантированно дождаться ответа sing-box'а.
     * @return delay в мс при успехе.
     * @throws IllegalStateException при HTTP-ошибке или если outbound не ответил.
     */
    fun proxyDelay(
        name: String,
        url: String = DEFAULT_DELAY_URL,
        timeoutMs: Int = DEFAULT_DELAY_TIMEOUT_MS,
    ): Long {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val path = "/proxies/$encodedName/delay?url=$encodedUrl&timeout=$timeoutMs"
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = timeoutMs + 2_000
        conn.setRequestProperty("Accept", "application/json")
        if (secret.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $secret")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $path: $body")
            }
            val delay = JSONObject(body).optLong("delay", -1L)
            if (delay < 0) {
                throw IllegalStateException("delay test for $name returned no delay: $body")
            }
            return delay
        } finally {
            conn.disconnect()
        }
    }

    fun fetchConnections(): ConnectionsSnapshot {
        val body = getRawBody("/connections")
        val root = JSONObject(body)
        val uploadTotal = root.optLong("uploadTotal", 0L)
        val downloadTotal = root.optLong("downloadTotal", 0L)
        val connsArray = root.optJSONArray("connections")
        val connections = mutableListOf<ConnectionInfo>()
        if (connsArray != null) {
            for (i in 0 until connsArray.length()) {
                val obj = connsArray.getJSONObject(i)
                val meta = obj.optJSONObject("metadata") ?: JSONObject()
                val chainsArray = obj.optJSONArray("chains")
                val chain = if (chainsArray != null && chainsArray.length() > 0) {
                    chainsArray.getString(0)
                } else {
                    ""
                }
                val portStr = meta.optString("destinationPort", "0")
                val port = portStr.toIntOrNull() ?: 0
                connections.add(
                    ConnectionInfo(
                        host = meta.optString("host", ""),
                        destinationPort = port,
                        network = meta.optString("network", ""),
                        chain = chain,
                        rule = obj.optString("rule", ""),
                        upload = obj.optLong("upload", 0L),
                        download = obj.optLong("download", 0L),
                        start = obj.optString("start", ""),
                        process = meta.optString("processPath", ""),
                    )
                )
            }
        }
        return ConnectionsSnapshot(
            uploadTotal = uploadTotal,
            downloadTotal = downloadTotal,
            connections = connections,
        )
    }

    fun fetchMemory(): Long {
        val body = getRawBody("/memory")
        val root = JSONObject(body)
        return root.optLong("inuse", 0L)
    }

    fun fetchVersion(): String = getRawBody("/version")

    fun fetchTrafficSnapshot(): TrafficSnapshot {
        val snapshot = fetchConnections()
        val memory = 0L // /memory is a streaming endpoint — can't read with simple HTTP GET

        // Aggregate per-outbound traffic from the first chain element of each connection
        val outboundMap = mutableMapOf<String, MutableOutboundAccumulator>()
        for (conn in snapshot.connections) {
            val outboundName = conn.chain.ifEmpty { "unknown" }
            val acc = outboundMap.getOrPut(outboundName) { MutableOutboundAccumulator(outboundName) }
            acc.upload += conn.upload
            acc.download += conn.download
            acc.connectionCount++
        }

        return TrafficSnapshot(
            uploadTotal = snapshot.uploadTotal,
            downloadTotal = snapshot.downloadTotal,
            activeConnections = snapshot.connections.size,
            memory = memory,
            byOutbound = outboundMap.mapValues { (_, acc) ->
                OutboundTraffic(
                    name = acc.name,
                    upload = acc.upload,
                    download = acc.download,
                    connectionCount = acc.connectionCount,
                )
            },
        )
    }

    private data class MutableOutboundAccumulator(
        val name: String,
        var upload: Long = 0L,
        var download: Long = 0L,
        var connectionCount: Int = 0,
    )

    companion object {
        const val DEFAULT_DELAY_URL = "https://www.google.com/generate_204"
        const val DEFAULT_DELAY_TIMEOUT_MS = 3_000
    }
}
