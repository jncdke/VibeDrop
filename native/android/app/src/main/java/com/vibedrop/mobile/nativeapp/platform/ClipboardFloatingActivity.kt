package com.vibedrop.mobile.nativeapp.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

class ClipboardFloatingActivity : Activity() {
    private var hasWritten = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val params = attributes
            params.dimAmount = 0f
            params.width = 1
            params.height = 1
            attributes = params
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({ writeClipboardAndFinish() }, 150)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            writeClipboardAndFinish()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeClipboardAndFinish() {
        if (hasWritten) return
        hasWritten = true
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) {
            runCatching {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("VibeDrop", text))
            }.onFailure { error ->
                Log.e(TAG, "透明 Activity 写剪贴板失败", error)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "VibeDrop.Clipboard"
        private const val EXTRA_TEXT = "clipboard_text"

        fun launchWrite(context: Context, text: String) {
            val intent = Intent(context, ClipboardFloatingActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
        }
    }
}
