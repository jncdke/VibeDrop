package com.vibedrop.mobile.nativeapp.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun shareHistoryJson(
    context: Context,
    fileName: String,
    json: String
) {
    shareJsonFile(context, fileName, json, "分享历史")
}

fun shareJsonFile(
    context: Context,
    fileName: String,
    json: String,
    chooserTitle: String
) {
    val dir = File(context.cacheDir, "history-share")
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, sanitizeFileName(fileName))
    file.writeText(json)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

private fun sanitizeFileName(value: String): String {
    return value.map { ch ->
        if (ch == '/' || ch == '\\' || ch == ':' || ch.isISOControl()) '_' else ch
    }.joinToString("").ifBlank { "vibedrop_history.json" }
}
