package com.vibedrop.mobile.nativeapp.core.model

enum class ConnectionStatus {
    Connected,
    Connecting,
    Disconnected,
    Error
}

data class ConnectionSnapshot(
    val status: ConnectionStatus,
    val lastError: String? = null,
    val reconnectAttempt: Int = 0
) {
    val canSend: Boolean
        get() = status == ConnectionStatus.Connected
}
