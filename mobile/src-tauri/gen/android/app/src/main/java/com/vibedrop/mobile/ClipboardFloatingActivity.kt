package com.vibedrop.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 透明悬浮 Activity，用于绕过 Android 10+ 后台剪贴板写入限制。
 * 参考 KDE Connect 的 ClipboardFloatingActivity 实现。
 *
 * 原理：启动这个 Activity 会让 APP 短暂"变成前台"，
 * 此时写入剪贴板就不会被系统拦截。写完立即 finish()，用户无感知。
 */
class ClipboardFloatingActivity : AppCompatActivity() {

  companion object {
    private const val TAG = "VibeDrop.Clipboard"
    private const val EXTRA_TEXT = "clipboard_text"

    // 静态待写入文本，供 JS Bridge 调用
    @Volatile
    var pendingText: String? = null

    fun getIntent(context: Context, text: String): Intent {
      return Intent(context, ClipboardFloatingActivity::class.java).apply {
        putExtra(EXTRA_TEXT, text)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
      }
    }

    /**
     * 从任意位置触发透明 Activity 写入剪贴板
     */
    fun launchWrite(context: Context, text: String) {
      pendingText = text
      context.startActivity(getIntent(context, text))
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 窗口完全透明 + 不遮挡触摸
    window.apply {
      val wlp = attributes
      wlp.dimAmount = 0f
      wlp.width = 1
      wlp.height = 1
      attributes = wlp
      addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
      addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      writeClipboardAndFinish()
    }
  }

  override fun onResume() {
    super.onResume()
    // 有些设备 onWindowFocusChanged 不触发，用 onResume 兜底
    window.decorView.postDelayed({ writeClipboardAndFinish() }, 200)
  }

  private var hasWritten = false

  private fun writeClipboardAndFinish() {
    if (hasWritten) return
    hasWritten = true

    val text = intent.getStringExtra(EXTRA_TEXT)
    if (text != null) {
      try {
        val cm = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("VibeDrop", text))
        Log.d(TAG, "透明 Activity 写入剪贴板成功，长度: ${text.length}")
      } catch (e: Exception) {
        Log.e(TAG, "透明 Activity 写入剪贴板失败", e)
      }
    }
    finish()
    overridePendingTransition(0, 0) // 无动画
  }
}
