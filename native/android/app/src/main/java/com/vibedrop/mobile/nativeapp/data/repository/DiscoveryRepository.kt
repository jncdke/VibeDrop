package com.vibedrop.mobile.nativeapp.data.repository

import android.content.Context
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.core.model.DiscoveredDesktop
import com.vibedrop.mobile.nativeapp.core.model.PairRequestAccepted
import com.vibedrop.mobile.nativeapp.core.model.PairStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
            val candidates = discoveryIpv4Candidates()
            val results = linkedMapOf<String, DiscoveredDesktop>()
            discoverViaUdp(candidates).forEach { results[it.key] = it }
            knownDevices.forEach { device ->
                discoverViaHttp(device.host, device.port, "known")?.let { results[it.key] = it }
                device.ip?.let { ip ->
                    discoverViaHttp(ip, device.port, "known-ip")?.let { results[it.key] = it }
                }
            }
            if (results.values.none { it.source == "udp" }) {
                discoverViaHttpTargets(httpSweepTargets(candidates)).forEach { results[it.key] = it }
            }
            results.values.sortedWith(
                compareByDescending<DiscoveredDesktop> { it.source == "udp" }
                    .thenByDescending { it.source == "known" || it.source == "known-ip" }
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

    private fun discoverViaUdp(candidates: List<DiscoveryIpv4Candidate>): List<DiscoveredDesktop> {
        val payload = JSONObject()
            .put("kind", "discover_probe")
            .put("protocol_version", 1)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val targets = udpTargets(candidates)
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

    private suspend fun discoverViaHttpTargets(targets: List<InetAddress>): List<DiscoveredDesktop> {
        if (targets.isEmpty()) return emptyList()
        val semaphore = Semaphore(32)
        return coroutineScope {
            targets.map { target ->
                async {
                    semaphore.withPermit {
                        discoverViaHttp(target.hostAddress.orEmpty(), DESKTOP_DISCOVERY_PORT, "http-sweep")
                    }
                }
            }.awaitAll().filterNotNull()
        }
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

    private fun udpTargets(candidates: List<DiscoveryIpv4Candidate>): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        val sourceCandidates = candidates
            .filter { it.allowDirectedBroadcast }
            .ifEmpty { candidates }
        sourceCandidates.forEach { candidate ->
            val octets = candidate.octets
            runCatching {
                targets.add(
                    InetAddress.getByAddress(
                        byteArrayOf(octets[0], octets[1], octets[2], 255.toByte())
                    )
                )
            }
        }
        return targets.toList()
    }

    private fun httpSweepTargets(candidates: List<DiscoveryIpv4Candidate>): List<InetAddress> {
        val localAddresses = candidates.map { it.hostAddress }.toSet()
        val sourceCandidates = candidates
            .filter { it.allowHttpSweep }
            .ifEmpty { candidates }
            .distinctBy { it.subnetKey }
            .take(HTTP_SUBNET_LIMIT)
        val targets = mutableListOf<InetAddress>()
        val seen = mutableSetOf<String>()
        for (candidate in sourceCandidates) {
            val octets = candidate.octets
            for (host in 1..254) {
                val address = "${octets[0].toInt() and 0xff}.${octets[1].toInt() and 0xff}.${octets[2].toInt() and 0xff}.$host"
                if (address in localAddresses || !seen.add(address)) continue
                runCatching { targets.add(InetAddress.getByName(address)) }
            }
        }
        return targets
    }

    private fun discoveryIpv4Candidates(): List<DiscoveryIpv4Candidate> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList().mapNotNull { address ->
                        val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                        if (!isViableDiscoveryAddress(ipv4)) return@mapNotNull null
                        DiscoveryIpv4Candidate(
                            interfaceName = networkInterface.name,
                            address = ipv4
                        )
                    }
                }
                .distinctBy { it.hostAddress }
                .sortedWith(
                    compareBy<DiscoveryIpv4Candidate> { it.priority }
                        .thenBy { it.interfaceName }
                        .thenBy { it.hostAddress }
                )
        }.getOrDefault(emptyList())
    }

    private fun isViableDiscoveryAddress(address: Inet4Address): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) {
            return false
        }
        val octets = address.address
        val first = octets[0].toInt() and 0xff
        val second = octets[1].toInt() and 0xff
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private data class DiscoveryIpv4Candidate(
        val interfaceName: String,
        val address: Inet4Address
    ) {
        val hostAddress: String = address.hostAddress.orEmpty()
        val octets: ByteArray = address.address
        val subnetKey: String = "${octets[0].toInt() and 0xff}.${octets[1].toInt() and 0xff}.${octets[2].toInt() and 0xff}"
        private val normalizedName = interfaceName.lowercase()
        private val primaryLan = normalizedName.startsWith("wlan") ||
            normalizedName.startsWith("ap") ||
            normalizedName.startsWith("swlan") ||
            normalizedName.startsWith("softap")
        private val secondaryLan = normalizedName.startsWith("eth") ||
            normalizedName.startsWith("en") ||
            normalizedName.startsWith("bridge") ||
            normalizedName.startsWith("br") ||
            normalizedName.startsWith("rndis") ||
            normalizedName.startsWith("usb")
        private val pointToPointOrVirtual = normalizedName.startsWith("tun") ||
            normalizedName.startsWith("ppp") ||
            normalizedName.startsWith("rmnet") ||
            normalizedName.startsWith("ccmni") ||
            normalizedName.startsWith("clat") ||
            normalizedName.startsWith("dummy") ||
            normalizedName.startsWith("ifb") ||
            normalizedName.startsWith("tap") ||
            normalizedName.startsWith("utun")
        val allowHttpSweep: Boolean = primaryLan || secondaryLan
        val allowDirectedBroadcast: Boolean = allowHttpSweep && !pointToPointOrVirtual
        val priority: Int = when {
            primaryLan -> 0
            secondaryLan -> 1
            pointToPointOrVirtual -> 3
            else -> 2
        }
    }

    private companion object {
        const val DESKTOP_DISCOVERY_PORT = 9001
        const val HTTP_SUBNET_LIMIT = 2
    }
}
