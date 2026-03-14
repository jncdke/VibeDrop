package com.vibedrop.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

class MainActivity : TauriActivity() {

  companion object {
    private const val TAG = "VibeDrop"
  }

  private data class PendingSharedContent(
    val cachePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean
  ) {
    fun toJson(): JSONObject = JSONObject().apply {
      put("cachePath", cachePath)
      put("displayName", displayName)
      put("mimeType", mimeType)
      put("sizeBytes", sizeBytes)
      put("isImage", isImage)
    }
  }

  private var appWebView: WebView? = null
  private var pendingExportFilename: String? = null
  private var pendingExportData: String? = null
  private var pendingSharedContent: PendingSharedContent? = null

  private val exportDocumentLauncher = registerForActivityResult(
    ActivityResultContracts.CreateDocument("application/json")
  ) { uri ->
    val filename = pendingExportFilename ?: "vibedrop_history.json"
    val data = pendingExportData
    pendingExportFilename = null
    pendingExportData = null

    if (uri == null) {
      emitBridgeEvent(
        "native-export-result",
        JSONObject().apply {
          put("ok", false)
          put("cancelled", true)
          put("message", "已取消导出")
        }
      )
      return@registerForActivityResult
    }

    if (data == null) {
      emitBridgeEvent(
        "native-export-result",
        JSONObject().apply {
          put("ok", false)
          put("error", "没有可导出的数据")
        }
      )
      return@registerForActivityResult
    }

    try {
      contentResolver.openOutputStream(uri)?.use { output ->
        output.write(data.toByteArray(Charsets.UTF_8))
      } ?: throw IllegalStateException("无法打开导出目标")

      emitBridgeEvent(
        "native-export-result",
        JSONObject().apply {
          put("ok", true)
          put("message", "已导出到你选择的位置")
          put("filename", filename)
          put("uri", uri.toString())
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "导出历史失败", e)
      emitBridgeEvent(
        "native-export-result",
        JSONObject().apply {
          put("ok", false)
          put("error", e.message ?: "导出失败")
        }
      )
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    handleIncomingShare(intent)

    // Android 13+ 请求通知权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
          != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
          arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
      }
    }

    // 启动前台保活服务
    val serviceIntent = Intent(this, KeepAliveService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }

    // 延迟检查权限（只在启动时检查一次）
    window.decorView.postDelayed({ checkPermissionsOnce() }, 2000)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingShare(intent)
  }

  /**
   * WebView 创建后注入 JS Bridge，让前端可以调用原生剪贴板写入
   * JS 端调用：window.NativeClipboard.writeText("文字")
   */
  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    appWebView = webView
    webView.addJavascriptInterface(ClipboardBridge(this), "NativeClipboard")
    webView.addJavascriptInterface(ShareBridge(), "NativeShare")
    Log.d(TAG, "JS Bridge NativeClipboard 已注入")
    emitPendingShareIfAvailable()
  }

  /**
   * JS Bridge：供前端调用的原生剪贴板接口
   */
  inner class ClipboardBridge(private val context: Context) {
    @JavascriptInterface
    fun writeText(text: String) {
      Log.d(TAG, "JS 调用 NativeClipboard.writeText, 长度: ${text.length}")
      ClipboardFloatingActivity.launchWrite(context, text)
    }
  }

  inner class ShareBridge {
    @JavascriptInterface
    fun exportHistory(filename: String, data: String) {
      runOnUiThread {
        val safeFilename = sanitizeFilename(filename)
        pendingExportFilename = safeFilename
        pendingExportData = data
        exportDocumentLauncher.launch(safeFilename)
      }
    }

    @JavascriptInterface
    fun shareHistory(filename: String, data: String) {
      runOnUiThread {
        try {
          val safeFilename = sanitizeFilename(filename)
          val shareDir = File(cacheDir, "shared").apply { mkdirs() }
          val shareFile = File(shareDir, safeFilename)
          shareFile.writeText(data, Charsets.UTF_8)

          val uri = FileProvider.getUriForFile(
            this@MainActivity,
            "$packageName.fileprovider",
            shareFile
          )

          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, safeFilename)
            putExtra(Intent.EXTRA_TITLE, safeFilename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, safeFilename, uri)
          }

          startActivity(Intent.createChooser(shareIntent, "分享历史记录"))

          emitBridgeEvent(
            "native-share-result",
            JSONObject().apply {
              put("ok", true)
              put("message", "已打开分享面板")
              put("filename", safeFilename)
            }
          )
        } catch (e: ActivityNotFoundException) {
          Log.e(TAG, "没有可用的分享应用", e)
          emitBridgeEvent(
            "native-share-result",
            JSONObject().apply {
              put("ok", false)
              put("error", "没有可用的分享应用")
            }
          )
        } catch (e: Exception) {
          Log.e(TAG, "分享历史失败", e)
          emitBridgeEvent(
            "native-share-result",
            JSONObject().apply {
              put("ok", false)
              put("error", e.message ?: "分享失败")
            }
          )
        }
      }
    }

    @JavascriptInterface
    fun getPendingSharedContent(): String {
      return pendingSharedContent?.toJson()?.toString() ?: ""
    }

    @JavascriptInterface
    fun readPendingSharedContentBase64(): String {
      val sharedContent = pendingSharedContent ?: return ""
      return try {
        Base64.encodeToString(File(sharedContent.cachePath).readBytes(), Base64.NO_WRAP)
      } catch (e: Exception) {
        Log.e(TAG, "读取共享内容失败", e)
        ""
      }
    }

    @JavascriptInterface
    fun clearPendingSharedContent() {
      pendingSharedContent?.let { content ->
        runCatching { File(content.cachePath).delete() }
          .onFailure { Log.w(TAG, "清理共享缓存失败", it) }
      }
      pendingSharedContent = null
    }
  }

  private var hasCheckedPermissions = false

  private fun checkPermissionsOnce() {
    if (hasCheckedPermissions) return
    hasCheckedPermissions = true

    val issues = mutableListOf<String>()

    // 1. 检查通知
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      issues.add("- 通知权限未开启（需要通知来保持后台运行）")
      Log.d(TAG, "通知未开启")
    }

    // 2. 检查电池优化
    try {
      val pm = getSystemService(POWER_SERVICE) as PowerManager
      if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        issues.add("- 电池优化未关闭（需要设为【不限制】）")
        Log.d(TAG, "电池优化未关闭")
      }
    } catch (e: Exception) {
      Log.w(TAG, "检查电池优化失败", e)
    }

    // 3. 检查管理闲置应用（Android 12+）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        if (!packageManager.isAutoRevokeWhitelisted) {
          issues.add("- 【管理闲置应用】未关闭（可能导致 APP 被自动休眠）")
          Log.d(TAG, "管理闲置应用未关闭")
        }
      } catch (e: Exception) {
        Log.w(TAG, "检查闲置应用失败", e)
      }
    }

    if (issues.isEmpty()) {
      Log.d(TAG, "所有权限检查通过")
      return
    }

    // 统一一个弹窗列出所有问题
    val message = "为确保 VibeDrop 后台稳定运行，请在设置中调整以下项目：\n\n" +
      issues.joinToString("\n") +
      "\n\n点击【去设置】将打开 VibeDrop 的系统设置页面。"

    AlertDialog.Builder(this)
      .setTitle("权限设置提醒")
      .setMessage(message)
      .setPositiveButton("去设置") { dialog, _ ->
        dialog.dismiss()
        openAppSettings()
      }
      .setNegativeButton("已设置过，不再提示") { dialog, _ ->
        dialog.dismiss()
      }
      .setCancelable(true)
      .show()
  }

  private fun openAppSettings() {
    try {
      // 最通用的方式：打开 APP 详情页，所有手机都支持
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
      }
      startActivity(intent)
      Log.d(TAG, "已跳转到 APP 设置页")
    } catch (e: Exception) {
      Log.e(TAG, "跳转设置失败", e)
      // 极端兜底：打开通用设置
      try {
        startActivity(Intent(Settings.ACTION_SETTINGS))
      } catch (_: Exception) {}
    }
  }

  private fun sanitizeFilename(filename: String): String {
    val cleaned = filename.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    return if (cleaned.isNotEmpty()) cleaned else "vibedrop_history.json"
  }

  private fun handleIncomingShare(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND) {
      return
    }

    val sharedUri = extractSharedUri(intent) ?: run {
      Log.w(TAG, "收到系统分享，但未找到文件 URI")
      return
    }

    try {
      val mimeType = intent.type ?: contentResolver.getType(sharedUri) ?: "application/octet-stream"
      val displayName = resolveSharedDisplayName(sharedUri, mimeType)
      val cachedFile = copySharedContentToCache(sharedUri, displayName)

      pendingSharedContent?.let { previous ->
        runCatching { File(previous.cachePath).delete() }
          .onFailure { Log.w(TAG, "清理旧的共享缓存失败", it) }
      }

      pendingSharedContent = PendingSharedContent(
        cachePath = cachedFile.absolutePath,
        displayName = cachedFile.name,
        mimeType = mimeType,
        sizeBytes = cachedFile.length(),
        isImage = mimeType.startsWith("image/")
      )

      Log.d(TAG, "已接收系统分享: ${cachedFile.name} ($mimeType)")
      emitPendingShareIfAvailable()
    } catch (e: Exception) {
      Log.e(TAG, "处理系统分享失败", e)
    }
  }

  private fun extractSharedUri(intent: Intent): Uri? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { return it }
    } else {
      @Suppress("DEPRECATION")
      (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { return it }
    }

    return intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
  }

  private fun resolveSharedDisplayName(uri: Uri, mimeType: String): String {
    val queriedName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor: Cursor ->
        if (cursor.moveToFirst()) {
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0) cursor.getString(index) else null
        } else {
          null
        }
      }

    val fallbackName = if (mimeType.startsWith("image/")) "shared-image" else "shared-file"
    return sanitizeFilename(queriedName ?: uri.lastPathSegment ?: fallbackName)
  }

  private fun copySharedContentToCache(uri: Uri, displayName: String): File {
    val sharedDir = File(cacheDir, "incoming-shared").apply { mkdirs() }
    val targetFile = uniqueCacheFile(sharedDir, displayName)

    contentResolver.openInputStream(uri)?.use { input ->
      targetFile.outputStream().use { output ->
        input.copyTo(output)
      }
    } ?: throw IllegalStateException("无法读取共享文件")

    return targetFile
  }

  private fun uniqueCacheFile(dir: File, preferredName: String): File {
    val sanitized = sanitizeFilename(preferredName)
    val preferred = File(dir, sanitized)
    if (!preferred.exists()) {
      return preferred
    }

    val stem = preferred.nameWithoutExtension.ifBlank { "shared-file" }
    val extension = preferred.extension
    var index = 1
    while (true) {
      val candidateName = if (extension.isBlank()) {
        "$stem ($index)"
      } else {
        "$stem ($index).$extension"
      }
      val candidate = File(dir, candidateName)
      if (!candidate.exists()) {
        return candidate
      }
      index += 1
    }
  }

  private fun emitPendingShareIfAvailable() {
    pendingSharedContent?.let { emitBridgeEvent("native-incoming-share", it.toJson()) }
  }

  private fun emitBridgeEvent(eventName: String, payload: JSONObject) {
    val webView = appWebView ?: return
    val js = "window.dispatchEvent(new CustomEvent(${JSONObject.quote(eventName)}, { detail: ${payload} }));"
    webView.post {
      webView.evaluateJavascript(js, null)
    }
  }
}
