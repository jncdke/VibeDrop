package com.vibedrop.mobile.nativeapp.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

data class AndroidNetworkDiagnosticsSnapshot(
    val activeTransports: List<String>,
    val hasInternetCapability: Boolean,
    val isValidated: Boolean,
    val localAddresses: List<NetworkAddressSnapshot>
)

data class NetworkAddressSnapshot(
    val interfaceName: String,
    val hostAddress: String,
    val likelyLan: Boolean
)

fun loadAndroidNetworkDiagnostics(context: Context): AndroidNetworkDiagnosticsSnapshot {
    val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val capabilities = connectivityManager
        ?.activeNetwork
        ?.let { connectivityManager.getNetworkCapabilities(it) }
    return AndroidNetworkDiagnosticsSnapshot(
        activeTransports = capabilities?.transportLabels().orEmpty(),
        hasInternetCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        localAddresses = localNetworkAddresses()
    )
}

private fun NetworkCapabilities.transportLabels(): List<String> {
    val labels = mutableListOf<String>()
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) labels += "Wi-Fi"
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) labels += "蜂窝"
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) labels += "以太网"
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) labels += "VPN"
    if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) labels += "蓝牙"
    return labels
}

private fun localNetworkAddresses(): List<NetworkAddressSnapshot> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { address ->
                        val hostAddress = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                        NetworkAddressSnapshot(
                            interfaceName = networkInterface.name,
                            hostAddress = hostAddress,
                            likelyLan = isLikelyLanAddress(hostAddress)
                        )
                    }
            }
            .sortedWith(
                compareByDescending<NetworkAddressSnapshot> { it.likelyLan }
                    .thenBy { it.interfaceName }
                    .thenBy { it.hostAddress }
            )
    }.getOrDefault(emptyList())
}

private fun isLikelyLanAddress(value: String): Boolean {
    return value.startsWith("192.168.") ||
        value.startsWith("10.") ||
        Regex("""^172\.(1[6-9]|2\d|3[0-1])\.""").containsMatchIn(value)
}
