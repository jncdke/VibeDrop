package com.vibedrop.mobile.nativeapp.core.model

data class DiscoveredDesktop(
    val serverId: String,
    val hostname: String,
    val ip: String,
    val port: Int,
    val protocolVersion: Int,
    val source: String
) {
    val key: String
        get() = serverId.ifBlank { "$ip:$port" }
}
