package com.vibedrop.mobile.nativeapp.platform

import android.content.Context

enum class MediaOpenMode(val rawValue: String, val label: String) {
    SystemDefault("system", "系统默认"),
    Gallery("gallery", "相册"),
    Browser("browser", "浏览器"),
    AskEveryTime("ask", "每次询问");

    companion object {
        fun fromRaw(value: String?): MediaOpenMode {
            return entries.firstOrNull { it.rawValue == value } ?: SystemDefault
        }
    }
}

class MediaOpenPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("media_open_preferences", Context.MODE_PRIVATE)

    fun loadImageMode(): MediaOpenMode {
        return MediaOpenMode.fromRaw(prefs.getString(KEY_IMAGE_MODE, null))
    }

    fun loadVideoMode(): MediaOpenMode {
        return MediaOpenMode.fromRaw(prefs.getString(KEY_VIDEO_MODE, null))
    }

    fun saveImageMode(mode: MediaOpenMode) {
        prefs.edit().putString(KEY_IMAGE_MODE, mode.rawValue).apply()
    }

    fun saveVideoMode(mode: MediaOpenMode) {
        prefs.edit().putString(KEY_VIDEO_MODE, mode.rawValue).apply()
    }

    companion object {
        private const val KEY_IMAGE_MODE = "image_mode"
        private const val KEY_VIDEO_MODE = "video_mode"
    }
}
