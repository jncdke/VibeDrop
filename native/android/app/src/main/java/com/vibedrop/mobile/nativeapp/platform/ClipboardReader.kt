package com.vibedrop.mobile.nativeapp.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun readClipboardText(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip: ClipData = manager?.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""
    return clip.getItemAt(0)
        .coerceToText(context)
        ?.toString()
        ?.trim()
        .orEmpty()
}
