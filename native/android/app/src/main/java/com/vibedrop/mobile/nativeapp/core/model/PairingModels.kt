package com.vibedrop.mobile.nativeapp.core.model

data class PairRequestAccepted(
    val requestId: String,
    val code: String,
    val hostname: String,
    val expiresInSecs: Long
)

data class PairStatus(
    val status: String,
    val requestId: String,
    val serverId: String?,
    val hostname: String?,
    val ip: String?,
    val port: Int?,
    val pin: String?,
    val error: String?
) {
    val approved: Boolean
        get() = status == "approved" && !pin.isNullOrBlank()

    val terminal: Boolean
        get() = status == "approved" || status == "rejected" || status == "expired"
}
