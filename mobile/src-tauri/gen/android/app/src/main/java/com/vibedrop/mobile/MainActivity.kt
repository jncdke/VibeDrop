package com.vibedrop.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : TauriActivity() {

  companion object {
    private const val TAG = "VibeDrop"
    private const val PREVIEW_ASSET_PREFIX = "/vibedrop-preview/"
    private const val PREVIEW_ASSET_HOST = "https://appassets.androidplatform.net"
  }

  private data class PreviewMediaAsset(
    val sourcePath: String,
    val mimeType: String,
    val displayName: String
  )

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
  private val previewMediaAssets = ConcurrentHashMap<String, PreviewMediaAsset>()
  private var previewAssetLoaderInstalled = false

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
    webView.settings.allowContentAccess = true
    webView.settings.allowFileAccess = true
    webView.addJavascriptInterface(ClipboardBridge(this), "NativeClipboard")
    webView.addJavascriptInterface(ShareBridge(), "NativeShare")
    webView.addJavascriptInterface(DeviceBridge(), "NativeDevice")
    webView.addJavascriptInterface(BackgroundClipboardBridge(this), "NativeBackgroundClipboard")
    webView.addJavascriptInterface(MediaLibraryBridge(this), "NativeMediaLibrary")
    Log.d(TAG, "JS Bridge NativeClipboard 已注入")
    webView.post { installPreviewAssetLoaderIfNeeded(webView) }
    emitPendingShareIfAvailable()
  }

  private fun installPreviewAssetLoaderIfNeeded(webView: WebView) {
    if (previewAssetLoaderInstalled) {
      return
    }

    val previousClient = webView.webViewClient
    val assetLoader = WebViewAssetLoader.Builder()
      .addPathHandler(PREVIEW_ASSET_PREFIX, PreviewMediaPathHandler())
      .build()

    webView.webViewClient = object : WebViewClientCompat() {
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        assetLoader.shouldInterceptRequest(request.url)?.let { return it }
        return previousClient?.shouldInterceptRequest(view, request)
          ?: super.shouldInterceptRequest(view, request)
      }

      @Deprecated("Deprecated in Java")
      override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        assetLoader.shouldInterceptRequest(Uri.parse(url))?.let { return it }
        return previousClient?.shouldInterceptRequest(view, url)
          ?: super.shouldInterceptRequest(view, url)
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return previousClient?.shouldOverrideUrlLoading(view, request)
          ?: super.shouldOverrideUrlLoading(view, request)
      }

      @Deprecated("Deprecated in Java")
      override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return previousClient?.shouldOverrideUrlLoading(view, url)
          ?: super.shouldOverrideUrlLoading(view, url)
      }

      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        previousClient?.onPageStarted(view, url, favicon)
        super.onPageStarted(view, url, favicon)
      }

      override fun onPageFinished(view: WebView, url: String?) {
        previousClient?.onPageFinished(view, url)
        super.onPageFinished(view, url)
      }
    }

    previewAssetLoaderInstalled = true
    Log.d(TAG, "媒体预览资源拦截已安装: ${previousClient?.javaClass?.name ?: "none"}")
  }

  private inner class PreviewMediaPathHandler : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
      val token = path.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
      val asset = previewMediaAssets[token] ?: return null
      return try {
        val inputStream = if (asset.sourcePath.startsWith("content://")) {
          contentResolver.openInputStream(Uri.parse(asset.sourcePath))
        } else {
          val file = File(asset.sourcePath)
          if (!file.exists()) {
            null
          } else {
            FileInputStream(file)
          }
        } ?: return null

        WebResourceResponse(asset.mimeType, null, inputStream).apply {
          responseHeaders = mapOf(
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "读取预览媒体失败: ${asset.displayName}", e)
        null
      }
    }
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

  inner class MediaLibraryBridge(private val context: Context) {
    @JavascriptInterface
    fun getPreviewUri(path: String, mimeType: String?): String {
      if (path.isBlank()) {
        return ""
      }

      return try {
        if (!path.startsWith("content://")) {
          val file = File(path)
          if (!file.exists()) {
            return ""
          }
        }

        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
          ?: resolveMimeTypeForPath(path)
          ?: "*/*"
        val displayName = deriveDisplayName(path)
        val token = UUID.randomUUID().toString()
        previewMediaAssets[token] = PreviewMediaAsset(
          sourcePath = path,
          mimeType = resolvedMimeType,
          displayName = displayName
        )
        "$PREVIEW_ASSET_HOST$PREVIEW_ASSET_PREFIX$token/${sanitizePreviewName(displayName)}"
      } catch (e: Exception) {
        Log.e(TAG, "生成预览 URI 失败", e)
        ""
      }
    }

    @JavascriptInterface
    fun scanPath(path: String, mimeType: String?) {
      if (path.isBlank()) {
        return
      }

      MediaScannerConnection.scanFile(
        context,
        arrayOf(path),
        arrayOf(mimeType?.takeIf { it.isNotBlank() }),
        null
      )
    }

    @JavascriptInterface
    fun openPath(path: String, mimeType: String?): String {
      if (path.isBlank()) {
        return "原文件路径为空"
      }

      val latch = CountDownLatch(1)
      var errorMessage = ""
      runOnUiThread {
        try {
          val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
          } else {
            val file = File(path)
            if (!file.exists()) {
              throw IllegalStateException("原文件不存在")
            }
            FileProvider.getUriForFile(
              this@MainActivity,
              "$packageName.fileprovider",
              file
            )
          }

          val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
            ?: contentResolver.getType(uri)
            ?: "*/*"

          val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }

          startActivity(Intent.createChooser(intent, "打开媒体"))
        } catch (e: ActivityNotFoundException) {
          Log.e(TAG, "没有可用的应用打开媒体", e)
          errorMessage = "没有可用的应用可以打开这个媒体文件"
        } catch (e: Exception) {
          Log.e(TAG, "打开媒体文件失败", e)
          errorMessage = e.message ?: "打开失败"
        } finally {
          latch.countDown()
        }
      }

      if (!latch.await(5, TimeUnit.SECONDS)) {
        return "打开媒体超时"
      }
      return errorMessage
    }

    @JavascriptInterface
    fun listOpeners(path: String?, mimeType: String?): String {
      return try {
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
        val intent = buildMediaViewIntent(path, resolvedMimeType, null)
        val apps = JSONArray()
        val seenPackages = linkedSetOf<String>()
        queryIntentActivitiesCompat(intent)
          .sortedBy { resolveAppLabel(it).lowercase() }
          .forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@forEach
            if (!seenPackages.add(packageName)) {
              return@forEach
            }
            apps.put(JSONObject().apply {
              put("packageName", packageName)
              put("label", resolveAppLabel(resolveInfo))
            })
          }
        apps.toString()
      } catch (e: Exception) {
        Log.e(TAG, "读取可用打开应用失败", e)
        "[]"
      }
    }

    @JavascriptInterface
    fun openPathWithPackage(path: String, mimeType: String?, packageName: String?): String {
      if (path.isBlank()) {
        return JSONObject().apply {
          put("ok", false)
          put("code", "missing_path")
          put("message", "原文件路径为空")
        }.toString()
      }

      val normalizedPackage = packageName?.trim().orEmpty()
      if (normalizedPackage.isBlank()) {
        return JSONObject().apply {
          put("ok", false)
          put("code", "missing_package")
          put("message", "未指定打开应用")
        }.toString()
      }

      val latch = CountDownLatch(1)
      var result = JSONObject().apply { put("ok", true) }

      runOnUiThread {
        try {
          val intent = buildMediaViewIntent(path, mimeType, normalizedPackage)
          startActivity(intent)
        } catch (e: ActivityNotFoundException) {
          Log.e(TAG, "指定应用无法打开媒体", e)
          result = JSONObject().apply {
            put("ok", false)
            put("code", "package_unavailable")
            put("message", "这个应用当前无法打开该媒体，可能已卸载或不再支持")
          }
        } catch (e: Exception) {
          Log.e(TAG, "指定应用打开媒体失败", e)
          result = JSONObject().apply {
            put("ok", false)
            put("code", "open_failed")
            put("message", e.message ?: "打开失败")
          }
        } finally {
          latch.countDown()
        }
      }

      if (!latch.await(5, TimeUnit.SECONDS)) {
        return JSONObject().apply {
          put("ok", false)
          put("code", "timeout")
          put("message", "打开媒体超时")
        }.toString()
      }

      return result.toString()
    }
  }

  private fun buildMediaViewIntent(path: String?, mimeType: String?, packageName: String?): Intent {
    val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
    return Intent(Intent.ACTION_VIEW).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

      if (!path.isNullOrBlank()) {
        val uri = buildMediaOpenUri(path)
        setDataAndType(uri, resolvedMimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, deriveDisplayName(path), uri)
      } else {
        type = resolvedMimeType
      }

      if (!packageName.isNullOrBlank()) {
        setPackage(packageName)
      }
    }
  }

  private fun buildMediaOpenUri(path: String): Uri {
    return if (path.startsWith("content://")) {
      Uri.parse(path)
    } else {
      val file = File(path)
      if (!file.exists()) {
        throw IllegalStateException("原文件不存在")
      }
      FileProvider.getUriForFile(
        this@MainActivity,
        "$packageName.fileprovider",
        file
      )
    }
  }

  @Suppress("DEPRECATION")
  private fun queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.queryIntentActivities(
        intent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
      )
    } else {
      packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
  }

  private fun resolveAppLabel(resolveInfo: ResolveInfo): String {
    return resolveInfo.loadLabel(packageManager)?.toString()?.trim().takeIf { !it.isNullOrBlank() }
      ?: resolveInfo.activityInfo?.packageName
      ?: "未知应用"
  }

  private fun resolveMimeTypeForPath(path: String): String? {
    return try {
      if (path.startsWith("content://")) {
        contentResolver.getType(Uri.parse(path))
      } else {
        URLConnection.guessContentTypeFromName(path)
      }
    } catch (e: Exception) {
      Log.w(TAG, "推断媒体 MIME 失败: $path", e)
      null
    }
  }

  private fun deriveDisplayName(path: String): String {
    if (path.startsWith("content://")) {
      return runCatching {
        contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
              cursor.getString(index)
            } else {
              null
            }
          } else {
            null
          }
        }
      }.getOrNull()?.takeIf { it.isNotBlank() } ?: "media"
    }

    return File(path).name.takeIf { it.isNotBlank() } ?: "media"
  }

  private fun sanitizePreviewName(name: String): String {
    return name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
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

  inner class DeviceBridge {
    @JavascriptInterface
    fun getDeviceInfo(): String {
      val marketName = readSystemProperty("ro.vendor.oplus.market.name")
        ?: readSystemProperty("ro.product.marketname")
        ?: readSystemProperty("ro.vendor.oplus.market.enname")
      val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
      val model = Build.MODEL?.trim().orEmpty()
      val friendlyName = when {
        marketName?.isNotBlank() == true -> marketName
        manufacturer.isNotBlank() && model.isNotBlank() -> "$manufacturer $model"
        model.isNotBlank() -> model
        manufacturer.isNotBlank() -> manufacturer
        else -> "Android 手机"
      }

      return JSONObject().apply {
        put("friendlyName", friendlyName)
        put("manufacturer", manufacturer)
        put("model", model)
        put("marketName", marketName ?: "")
        put("brand", Build.BRAND?.trim().orEmpty())
        put("device", Build.DEVICE?.trim().orEmpty())
      }.toString()
    }
  }

  inner class BackgroundClipboardBridge(private val context: Context) {
    @JavascriptInterface
    fun syncConfig(configJson: String) {
      try {
        BackgroundClipboardConfigStore.save(context, configJson)
        requestBackgroundClipboardRefresh(context)
        Log.d(TAG, "后台剪贴板配置已同步")
      } catch (e: Exception) {
        Log.e(TAG, "同步后台剪贴板配置失败", e)
      }
    }
  }

  private fun readSystemProperty(key: String): String? {
    return try {
      val clazz = Class.forName("android.os.SystemProperties")
      val method = clazz.getMethod("get", String::class.java)
      val value = method.invoke(null, key) as? String
      value?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      Log.w(TAG, "读取系统属性失败: $key", e)
      null
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

  private fun requestBackgroundClipboardRefresh(context: Context) {
    val serviceIntent = Intent(context, KeepAliveService::class.java).apply {
      action = KeepAliveService.ACTION_REFRESH_BACKGROUND_CLIPBOARD
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(serviceIntent)
    } else {
      context.startService(serviceIntent)
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
