package com.vibedrop.mobile.nativeapp.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.vibedrop.mobile.nativeapp.data.local.HistoryItemEntity
import java.io.File

fun openHistoryMediaItem(
    context: Context,
    item: HistoryItemEntity,
    mode: MediaOpenMode
) {
    val uri = resolveHistoryItemUri(context, item)
        ?: throw IllegalStateException("这条历史没有可打开的本地媒体路径")
    val mimeType = item.mimeType ?: when (item.kind) {
        "image" -> "image/*"
        "video" -> "video/*"
        else -> "*/*"
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (mode == MediaOpenMode.Browser) {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }
    val launchIntent = if (mode == MediaOpenMode.AskEveryTime) {
        Intent.createChooser(intent, "打开 ${item.fileName ?: "媒体"}")
    } else {
        intent
    }
    try {
        context.startActivity(launchIntent)
    } catch (error: ActivityNotFoundException) {
        if (mode != MediaOpenMode.SystemDefault) {
            context.startActivity(intent)
        } else {
            throw error
        }
    }
}

private fun resolveHistoryItemUri(context: Context, item: HistoryItemEntity): Uri? {
    val rawPath = listOf(item.savedPath, item.localPath, item.thumbnailPath)
        .firstOrNull { !it.isNullOrBlank() }
        ?: return null
    return when {
        rawPath.startsWith("content://") || rawPath.startsWith("file://") -> Uri.parse(rawPath)
        rawPath.startsWith("http://") || rawPath.startsWith("https://") -> Uri.parse(rawPath)
        else -> {
            val file = File(rawPath)
            if (!file.exists()) return null
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
    }
}
