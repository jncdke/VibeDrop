package com.vibedrop.mobile.nativeapp.core.model

data class DesktopDevice(
    val id: String,
    val stableId: String,
    val displayName: String,
    val host: String,
    val ip: String?,
    val port: Int,
    val pin: String?,
    val connection: ConnectionSnapshot
)
