package com.vibedrop.mobile.nativeapp.data.repository

import android.content.Context
import android.net.wifi.WifiManager
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.core.model.DiscoveredDesktop
import com.vibedrop.mobile.nativeapp.core.model.PairRequestAccepted
import com.vibedrop.mobile.nativeapp.core.model.PairStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class DiscoveryRepository(
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .writeTimeout(1200, TimeUnit.MILLISECONDS)
        .build()

    suspend fun discoverDesktops(knownDevices: List<DesktopDevice>): List<DiscoveredDesktop> {
        return withContext(Dispatchers.IO) {
            val results = linkedMapOf<String, DiscoveredDesktop>()
            discoverViaUdp().forEach { results[it.key] = it }
            knownDevices.forEach { device ->
                discoverViaHttp(device.host, device.port, "known")?.let { results[it.key] = it }
                device.ip?.let { ip ->
                    discoverViaHttp(ip, device.port, "known-ip")?.let { results[it.key] = it }
                }
            }
            results.values.sortedWith(
                compareByDescending<DiscoveredDesktop> { it.source == "udp" }
                    .thenBy { it.hostname.lowercase() }
                    .thenBy { it.ip }
            )
        }
    }

    suspend fun requestPairing(
        desktop: DiscoveredDesktop,
        clientId: String,
        clientName: String
    ): PairRequestAccepted = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("client_name", clientName)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://${desktop.ip}:${desktop.port}/pair/request")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        val payload = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val error = runCatching { JSONObject(payload).optString("error") }.getOrNull()
            throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "桌面端拒绝了配对请求")
        }
        val json = JSONObject(payload)
        PairRequestAccepted(
            requestId = json.getString("request_id"),
            code = json.getString("code"),
            hostname = json.optString("hostname", desktop.hostname),
            expiresInSecs = json.optLong("expires_in_secs", 120)
        )
    }

    suspend fun pollPairing(
        desktop: DiscoveredDesktop,
        requestId: String
    ): PairStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://${desktop.ip}:${desktop.port}/pair/status/$requestId")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string().orEmpty())
        PairStatus(
            status = json.optString("status", "expired"),
            requestId = json.optString("request_id", requestId),
            serverId = json.optString("server_id").takeIf { it.isNotBlank() },
            hostname = json.optString("hostname").takeIf { it.isNotBlank() },
            ip = json.optString("ip").takeIf { it.isNotBlank() },
            port = json.takeIf { it.has("port") && !it.isNull("port") }?.optInt("port"),
            pin = json.optString("pin").takeIf { it.isNotBlank() },
            error = json.optString("error").takeIf { it.isNotBlank() }
        )
    }

    private fun discoverViaUdp(): List<DiscoveredDesktop> {
        val payload = JSONObject()
            .put("kind", "discover_probe")
            .put("protocol_version", 1)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val targets = udpTargets()
        if (targets.isEmpty()) return emptyList()

        val results = linkedMapOf<String, DiscoveredDesktop>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 900
            socket.bind(InetSocketAddress(0))
            repeat(2) {
                targets.forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, 9001))
                    }
                }
            }

            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching {
                    socket.receive(packet)
                    packet
                }.getOrNull() ?: break
                parseDiscoverResponse(
                    String(received.data, 0, received.length, Charsets.UTF_8),
                    fallbackIp = received.address.hostAddress.orEmpty(),
                    source = "udp"
                )?.let { results[it.key] = it }
            }
        }
        return results.values.toList()
    }

    private fun discoverViaHttp(host: String, port: Int, source: String): DiscoveredDesktop? {
        val request = Request.Builder()
            .url("http://$host:$port/discover")
            .get()
            .build()
        return runCatching {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            parseDiscoverResponse(
                response.body?.string().orEmpty(),
                fallbackIp = host,
                source = source
            )
        }.getOrNull()
    }

    private fun parseDiscoverResponse(
        text: String,
        fallbackIp: String,
        source: String
    ): DiscoveredDesktop? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("kind") != "desktop") return null
        val serverId = json.optString("server_id")
        val hostname = json.optString("hostname", fallbackIp)
        val ip = json.optString("ip", fallbackIp).ifBlank { fallbackIp }
        val port = json.optInt("port", 9001)
        return DiscoveredDesktop(
            serverId = serverId,
            hostname = hostname,
            ip = ip,
            port = port,
            protocolVersion = json.optInt("protocol_version", 1),
            source = source
        )
    }

    private fun udpTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo ?: return@runCatching
            val broadcast = (dhcpInfo.ipAddress and dhcpInfo.netmask) or dhcpInfo.netmask.inv()
            val bytes = byteArrayOf(
                (broadcast and 0xff).toByte(),
                (broadcast shr 8 and 0xff).toByte(),
                (broadcast shr 16 and 0xff).toByte(),
                (broadcast shr 24 and 0xff).toByte()
            )
            targets.add(InetAddress.getByAddress(bytes))
        }
        return targets.toList()
    }
}
