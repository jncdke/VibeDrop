package com.vibedrop.mobile.nativeapp.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale
import java.util.UUID

data class AndroidDeviceIdentity(
    val deviceId: String,
    val baseDeviceId: String,
    val deviceName: String
) {
    fun clipboardDeviceId(targetId: String): String {
        return "$baseDeviceId:clipboard:${targetId.hashCode()}"
    }

    fun clipboardDeviceName(): String {
        return "$deviceName 剪贴板"
    }
}

fun loadAndroidDeviceIdentity(context: Context): AndroidDeviceIdentity {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences("android_device_identity", Context.MODE_PRIVATE)
    val secureId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
    val fallbackId = prefs.getString("fallback_id", null) ?: UUID.randomUUID().toString().also { generated ->
        prefs.edit().putString("fallback_id", generated).apply()
    }
    val seed = sanitizeIdentityPart(secureId ?: fallbackId)
    val baseDeviceId = "native-android:$seed"
    return AndroidDeviceIdentity(
        deviceId = baseDeviceId,
        baseDeviceId = baseDeviceId,
        deviceName = androidDeviceDisplayName()
    )
}

private fun androidDeviceDisplayName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    if (manufacturer.isBlank() && model.isBlank()) return "Android"
    if (manufacturer.isBlank()) return model
    if (model.isBlank()) return titleCaseWords(manufacturer)
    return if (model.startsWith(manufacturer, ignoreCase = true)) {
        model
    } else {
        "${titleCaseWords(manufacturer)} $model"
    }
}

private fun titleCaseWords(value: String): String {
    return value
        .lowercase(Locale.US)
        .split(" ")
        .joinToString(" ") { part ->
            if (part.isBlank()) {
                part
            } else {
                part.substring(0, 1).uppercase(Locale.US) + part.substring(1)
            }
        }
}

private fun sanitizeIdentityPart(value: String): String {
    return value.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
    }.joinToString("").ifBlank { UUID.randomUUID().toString() }
}
