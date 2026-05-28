package com.vibedrop.mobile.nativeapp.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class BackgroundRunDiagnosticsSnapshot(
    val notificationsEnabled: Boolean,
    val ignoresBatteryOptimizations: Boolean,
    val autoRevokeWhitelisted: Boolean?,
    val batteryCheckAvailable: Boolean
) {
    val issueCount: Int
        get() = listOf(
            !notificationsEnabled,
            batteryCheckAvailable && !ignoresBatteryOptimizations,
            autoRevokeWhitelisted == false
        ).count { it }
}

fun loadBackgroundRunDiagnostics(context: Context): BackgroundRunDiagnosticsSnapshot {
    val appContext = context.applicationContext
    val batteryIgnored = runCatching {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }.getOrNull()
    val autoRevokeWhitelisted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { appContext.packageManager.isAutoRevokeWhitelisted }.getOrNull()
    } else {
        null
    }
    return BackgroundRunDiagnosticsSnapshot(
        notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        ignoresBatteryOptimizations = batteryIgnored == true,
        autoRevokeWhitelisted = autoRevokeWhitelisted,
        batteryCheckAvailable = batteryIgnored != null
    )
}

fun openAppNotificationSettings(context: Context) {
    val appContext = context.applicationContext
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        }
    } else {
        appDetailsIntent(appContext)
    }
    startSettingsActivity(context, intent)
}

fun openBatteryOptimizationSettings(context: Context) {
    val appContext = context.applicationContext
    val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${appContext.packageName}")
    }
    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching {
        startSettingsActivity(context, directIntent)
    }.recoverCatching {
        startSettingsActivity(context, fallbackIntent)
    }.recoverCatching {
        startSettingsActivity(context, appDetailsIntent(appContext))
    }
}

fun openAutoRevokeSettings(context: Context) {
    startSettingsActivity(context, appDetailsIntent(context.applicationContext))
}

private fun appDetailsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
}

private fun startSettingsActivity(context: Context, intent: Intent) {
    context.startActivity(
        intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
