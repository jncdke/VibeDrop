package com.vibedrop.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : TauriActivity() {

  companion object {
    private const val TAG = "VibeDrop"
    private const val PREVIEW_ASSET_PREFIX = "/vibedrop-preview/"
    private const val PREVIEW_ASSET_HOST = "https://appassets.androidplatform.net"
    @Volatile
    private var backgroundClipboardStartupGateArmedInProcess = false
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

  private data class HomeVaultResponse(
    val statusCode: Int,
    val body: String,
    val network: String
  )

  private data class CachedVaultObject(
    val file: File,
    val bytes: Long,
    val skipped: Boolean
  )

  private class HistoryDatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, "vibedrop_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS history_entries (
          entry_key TEXT PRIMARY KEY,
          timestamp TEXT NOT NULL,
          text TEXT,
          kind TEXT,
          status TEXT,
          target TEXT,
          target_name TEXT,
          target_alias TEXT,
          target_device_name TEXT,
          target_server_id TEXT,
          raw_json TEXT NOT NULL,
          updated_at INTEGER NOT NULL
        )
        """.trimIndent()
      )
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_entries_timestamp ON history_entries(timestamp DESC)")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_entries_kind ON history_entries(kind)")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_entries_target_server ON history_entries(target_server_id)")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS media_cache (
          cache_key TEXT PRIMARY KEY,
          entry_key TEXT NOT NULL,
          item_index INTEGER NOT NULL,
          kind TEXT,
          file_name TEXT,
          mime_type TEXT,
          object_hash TEXT,
          object_path TEXT,
          local_path TEXT,
          thumbnail_path TEXT,
          thumbnail_data_url TEXT,
          size_bytes INTEGER,
          status TEXT,
          missing_reason TEXT,
          updated_at INTEGER NOT NULL
        )
        """.trimIndent()
      )
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_cache_entry ON media_cache(entry_key, item_index)")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_cache_object_hash ON media_cache(object_hash)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      onCreate(db)
    }

    fun loadHistory(): String {
      val result = JSONArray()
      readableDatabase.rawQuery(
        "SELECT raw_json FROM history_entries ORDER BY timestamp DESC, updated_at DESC",
        null
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val raw = cursor.getString(0) ?: continue
          runCatching { result.put(JSONObject(raw)) }
        }
      }
      return result.toString()
    }

    fun replaceHistory(historyJson: String): JSONObject {
      val entries = JSONArray(historyJson)
      val db = writableDatabase
      val now = System.currentTimeMillis()
      db.beginTransaction()
      try {
        db.delete("media_cache", null, null)
        db.delete("history_entries", null, null)
        for (index in 0 until entries.length()) {
          val entry = entries.optJSONObject(index) ?: continue
          upsertEntry(db, entry, now)
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
      return JSONObject().apply {
        put("ok", true)
        put("count", entries.length())
      }
    }

    fun upsertHistoryEntry(entryJson: String): JSONObject {
      val entry = JSONObject(entryJson)
      val db = writableDatabase
      val key = upsertEntry(db, entry, System.currentTimeMillis())
      return JSONObject().apply {
        put("ok", true)
        put("entryKey", key)
      }
    }

    fun upsertHistoryArray(historyJson: String): JSONObject {
      val entries = JSONArray(historyJson)
      val db = writableDatabase
      val now = System.currentTimeMillis()
      db.beginTransaction()
      var count = 0
      try {
        for (index in 0 until entries.length()) {
          val entry = entries.optJSONObject(index) ?: continue
          upsertEntry(db, entry, now)
          count += 1
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
      return JSONObject().apply {
        put("ok", true)
        put("count", count)
      }
    }

    fun countExistingEntries(entries: JSONArray): Int {
      val existingKeys = mutableSetOf<String>()
      readableDatabase.rawQuery("SELECT entry_key FROM history_entries", null).use { cursor ->
        while (cursor.moveToNext()) {
          existingKeys.add(cursor.getString(0))
        }
      }
      var count = 0
      for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: continue
        if (existingKeys.contains(deriveEntryKey(entry))) {
          count += 1
        }
      }
      return count
    }

    fun clearHistory(): JSONObject {
      val db = writableDatabase
      db.beginTransaction()
      try {
        db.delete("media_cache", null, null)
        db.delete("history_entries", null, null)
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
      return JSONObject().apply { put("ok", true) }
    }

    fun stats(): JSONObject {
      val db = readableDatabase
      val entryCount = scalarLong(db, "SELECT COUNT(*) FROM history_entries")
      val mediaCount = scalarLong(db, "SELECT COUNT(*) FROM media_cache")
      val cachedMediaCount = scalarLong(db, "SELECT COUNT(*) FROM media_cache WHERE local_path IS NOT NULL AND local_path != ''")
      return JSONObject().apply {
        put("ok", true)
        put("entryCount", entryCount)
        put("mediaCount", mediaCount)
        put("cachedMediaCount", cachedMediaCount)
        put("cacheBytes", directorySize(mediaCacheRoot()))
      }
    }

    fun mediaCacheRoot(): File {
      return File(context.filesDir, "vibedrop_history_media").apply { mkdirs() }
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long {
      db.rawQuery(sql, null).use { cursor ->
        return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
      }
    }

    private fun upsertEntry(db: SQLiteDatabase, entry: JSONObject, updatedAt: Long): String {
      val key = deriveEntryKey(entry)
      val values = ContentValues().apply {
        put("entry_key", key)
        put("timestamp", entry.optString("timestamp").ifBlank { entry.optString("timestamp_iso") })
        put("text", entry.optString("text"))
        put("kind", entry.optString("kind", "text"))
        put("status", entry.optString("status", "success"))
        put("target", entry.optString("target"))
        put("target_name", entry.optString("targetName"))
        put("target_alias", entry.optString("targetAlias"))
        put("target_device_name", entry.optString("targetDeviceName").ifBlank { entry.optString("targetHost") })
        put("target_server_id", entry.optString("targetServerId").ifBlank { entry.optString("serverId") })
        put("raw_json", entry.toString())
        put("updated_at", updatedAt)
      }
      db.insertWithOnConflict("history_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
      replaceMediaRows(db, key, entry, updatedAt)
      return key
    }

    private fun replaceMediaRows(db: SQLiteDatabase, entryKey: String, entry: JSONObject, updatedAt: Long) {
      db.delete("media_cache", "entry_key = ?", arrayOf(entryKey))
      val items = collectMediaItems(entry)
      for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val objectHash = item.optString("objectHash")
        val localPath = item.optString("savedPath").ifBlank { item.optString("filePath") }
        val cacheKey = "$entryKey:$index:${objectHash.ifBlank { sha256("${item.optString("fileName")}|$localPath|${item.optString("thumbnailDataUrl")}") }}"
        val values = ContentValues().apply {
          put("cache_key", cacheKey)
          put("entry_key", entryKey)
          put("item_index", index)
          put("kind", item.optString("kind", entry.optString("kind", "media")))
          put("file_name", item.optString("fileName").ifBlank { entry.optString("fileName") })
          put("mime_type", item.optString("mimeType").ifBlank { entry.optString("mimeType") })
          put("object_hash", objectHash)
          put("object_path", item.optString("objectPath"))
          put("local_path", localPath)
          put("thumbnail_path", item.optString("thumbnailPath"))
          put("thumbnail_data_url", item.optString("thumbnailDataUrl"))
          put("size_bytes", item.optLong("sizeBytes", 0L))
          put("status", item.optString("status", entry.optString("status")))
          put("missing_reason", item.optString("missingReason"))
          put("updated_at", updatedAt)
        }
        db.insertWithOnConflict("media_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
      }
    }

    private fun collectMediaItems(entry: JSONObject): JSONArray {
      entry.optJSONArray("items")?.let { return it }
      entry.optJSONArray("media")?.let { return it }
      val kind = entry.optString("kind")
      if (kind !in setOf("image", "video", "media", "file")) {
        return JSONArray()
      }
      return JSONArray().put(JSONObject().apply {
        put("kind", kind)
        put("fileName", entry.optString("fileName"))
        put("mimeType", entry.optString("mimeType"))
        put("savedPath", entry.optString("savedPath"))
        put("filePath", entry.optString("filePath"))
        put("thumbnailDataUrl", entry.optString("thumbnailDataUrl"))
      })
    }

    private fun deriveEntryKey(entry: JSONObject): String {
      val vaultId = entry.optString("vaultEntryId").ifBlank {
        val id = entry.optString("id")
        if (id.length >= 32 && id.all { it.isLetterOrDigit() }) id else ""
      }
      if (vaultId.isNotBlank()) {
        return "vault:$vaultId"
      }
      val id = entry.optString("id")
      if (id.isNotBlank()) {
        return "local:$id"
      }
      val source = "${entry.optString("timestamp")}|${entry.optString("text")}|${entry.optString("target")}|${entry.optString("targetServerId")}"
      return "derived:${sha256(source)}"
    }

    private fun sha256(value: String): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }

    private fun directorySize(file: File): Long {
      if (!file.exists()) {
        return 0L
      }
      if (file.isFile) {
        return file.length()
      }
      return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }
  }

  private var appWebView: WebView? = null
  private val historyStore: HistoryDatabaseHelper by lazy { HistoryDatabaseHelper(applicationContext) }
  private var pendingExportFilename: String? = null
  private var pendingExportData: String? = null
  private var pendingSharedContents: MutableList<PendingSharedContent> = mutableListOf()
  private val previewMediaAssets = ConcurrentHashMap<String, PreviewMediaAsset>()
  private var previewAssetLoaderInstalled = false
  private var tauriInitFallbackApplied = false

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

    maybeArmBackgroundClipboardStartupGate()

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
    webView.addJavascriptInterface(HistoryBridge(historyStore), "NativeHistory")
    webView.addJavascriptInterface(HomeVaultBridge(historyStore), "NativeHomeVault")
    Log.d(TAG, "JS Bridge NativeClipboard 已注入")
    Log.d(TAG, "Tauri init scripts: ${(webView as? RustWebView)?.initScripts?.size ?: 0}")
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
        ensureTauriBridgeIfMissing(view)
      }
    }

    previewAssetLoaderInstalled = true
    Log.d(TAG, "媒体预览资源拦截已安装: ${previousClient?.javaClass?.name ?: "none"}")
  }

  private fun logWebViewBridgeState(view: WebView) {
    val script = """
      (() => JSON.stringify({
        href: window.location?.href || '',
        hasTauri: !!window.__TAURI__,
        tauriKeys: window.__TAURI__ ? Object.keys(window.__TAURI__).slice(0, 12) : [],
        hasTauriCore: !!window.__TAURI__?.core,
        hasTauriInvoke: typeof window.__TAURI__?.core?.invoke === 'function',
        hasInternals: !!window.__TAURI_INTERNALS__,
        internalKeys: window.__TAURI_INTERNALS__ ? Object.keys(window.__TAURI_INTERNALS__).slice(0, 12) : [],
        hasInternalInvoke: typeof window.__TAURI_INTERNALS__?.invoke === 'function',
        hasWindowIpc: typeof window.ipc?.postMessage === 'function',
        hasNativeClipboard: !!window.NativeClipboard
      }))();
    """.trimIndent()

    view.evaluateJavascript(script) { result ->
      Log.d(TAG, "WebView bridge state: $result")
    }
  }

  private fun ensureTauriBridgeIfMissing(view: WebView) {
    val stateScript = """
      (() => JSON.stringify({
        hasTauriInvoke: typeof window.__TAURI__?.core?.invoke === 'function',
        hasInternalInvoke: typeof window.__TAURI_INTERNALS__?.invoke === 'function'
      }))();
    """.trimIndent()

    view.evaluateJavascript(stateScript) { result ->
      val hasInvoke = result?.contains("\"hasTauriInvoke\":true") == true
        || result?.contains("\"hasInternalInvoke\":true") == true
      if (!hasInvoke && !tauriInitFallbackApplied) {
        val rustWebView = view as? RustWebView
        if (rustWebView != null && rustWebView.initScripts.isNotEmpty()) {
          tauriInitFallbackApplied = true
          Log.w(TAG, "Tauri bridge missing after page load, replaying ${rustWebView.initScripts.size} init scripts")
          rustWebView.initScripts.forEach { initScript ->
            view.evaluateJavascript(initScript, null)
          }
          view.postDelayed({ logWebViewBridgeState(view) }, 150)
          return@evaluateJavascript
        }
      }

      logWebViewBridgeState(view)
    }
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

    @JavascriptInterface
    fun readText(): String {
      return try {
        val clipboardManager = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        val clip = clipboardManager?.primaryClip ?: return ""
        if (clip.itemCount <= 0) {
          return ""
        }
        clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
      } catch (e: Exception) {
        Log.e(TAG, "JS 调用 NativeClipboard.readText 失败", e)
        ""
      }
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
      return pendingSharedContents.firstOrNull()?.toJson()?.toString() ?: ""
    }

    @JavascriptInterface
    fun getPendingSharedContents(): String {
      val items = JSONArray()
      pendingSharedContents.forEach { items.put(it.toJson()) }
      return items.toString()
    }

    @JavascriptInterface
    fun readPendingSharedContentBase64(): String {
      val sharedContent = pendingSharedContents.firstOrNull() ?: return ""
      return try {
        Base64.encodeToString(File(sharedContent.cachePath).readBytes(), Base64.NO_WRAP)
      } catch (e: Exception) {
        Log.e(TAG, "读取共享内容失败", e)
        ""
      }
    }

    @JavascriptInterface
    fun readPendingSharedContentBase64At(itemIndex: Int): String {
      val sharedContent = pendingSharedContents.getOrNull(itemIndex) ?: return ""
      return try {
        Base64.encodeToString(File(sharedContent.cachePath).readBytes(), Base64.NO_WRAP)
      } catch (e: Exception) {
        Log.e(TAG, "读取共享内容失败", e)
        ""
      }
    }

    @JavascriptInterface
    fun readPendingSharedContentChunkBase64(offsetBytes: Long, lengthBytes: Int): String {
      return readPendingSharedContentChunkBase64At(0, offsetBytes, lengthBytes)
    }

    @JavascriptInterface
    fun readPendingSharedContentChunkBase64At(itemIndex: Int, offsetBytes: Long, lengthBytes: Int): String {
      val sharedContent = pendingSharedContents.getOrNull(itemIndex) ?: return ""
      if (offsetBytes < 0 || lengthBytes <= 0) {
        return ""
      }

      return try {
        RandomAccessFile(sharedContent.cachePath, "r").use { file ->
          val safeOffset = offsetBytes.coerceAtMost(file.length())
          val readableBytes = (file.length() - safeOffset).coerceAtLeast(0L)
          val targetLength = minOf(lengthBytes.toLong(), readableBytes).toInt()
          if (targetLength <= 0) {
            return ""
          }

          val buffer = ByteArray(targetLength)
          file.seek(safeOffset)
          val read = file.read(buffer)
          if (read <= 0) {
            ""
          } else {
            Base64.encodeToString(
              if (read == buffer.size) buffer else buffer.copyOf(read),
              Base64.NO_WRAP
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "分块读取共享内容失败", e)
        ""
      }
    }

    @JavascriptInterface
    fun clearPendingSharedContent() {
      clearPendingSharedContentsCache()
    }

    @JavascriptInterface
    fun retainPendingSharedContentIndexes(indexesJson: String) {
      val keepIndexes = mutableSetOf<Int>()
      runCatching {
        val array = JSONArray(indexesJson)
        for (index in 0 until array.length()) {
          keepIndexes.add(array.optInt(index, -1))
        }
      }.onFailure {
        Log.w(TAG, "解析保留共享索引失败", it)
      }

      val nextContents = mutableListOf<PendingSharedContent>()
      pendingSharedContents.forEachIndexed { index, content ->
        if (keepIndexes.contains(index)) {
          nextContents.add(content)
        } else {
          runCatching { File(content.cachePath).delete() }
            .onFailure { Log.w(TAG, "清理共享缓存失败", it) }
        }
      }

      pendingSharedContents.clear()
      pendingSharedContents.addAll(nextContents)
    }
  }

  private inner class HistoryBridge(private val store: HistoryDatabaseHelper) {
    @JavascriptInterface
    fun loadHistory(): String {
      return try {
        store.loadHistory()
      } catch (e: Exception) {
        Log.e(TAG, "读取 SQLite 历史失败", e)
        "[]"
      }
    }

    // 异步版：同步 loadHistory 会让 WebView 的 JS 线程原地等待整库读取，
    // 冷启动时表现为首页卡住无法输入。这里放到后台线程读，完成后回调 JS。
    @JavascriptInterface
    fun loadHistoryAsync(requestId: String) {
      Thread {
        val payload = try {
          store.loadHistory()
        } catch (e: Exception) {
          Log.e(TAG, "异步读取 SQLite 历史失败", e)
          "[]"
        }
        val script = "window.__vibedropNativeHistoryLoaded && window.__vibedropNativeHistoryLoaded(" +
          JSONObject.quote(requestId) + "," + JSONObject.quote(payload) + ")"
        runOnUiThread {
          appWebView?.evaluateJavascript(script, null)
        }
      }.start()
    }

    @JavascriptInterface
    fun replaceHistory(historyJson: String): String {
      return try {
        store.replaceHistory(historyJson).toString()
      } catch (e: Exception) {
        Log.e(TAG, "替换 SQLite 历史失败", e)
        JSONObject().apply {
          put("ok", false)
          put("error", e.message ?: "保存失败")
        }.toString()
      }
    }

    @JavascriptInterface
    fun upsertHistoryEntry(entryJson: String): String {
      return try {
        store.upsertHistoryEntry(entryJson).toString()
      } catch (e: Exception) {
        Log.e(TAG, "写入 SQLite 历史失败", e)
        JSONObject().apply {
          put("ok", false)
          put("error", e.message ?: "写入失败")
        }.toString()
      }
    }

    @JavascriptInterface
    fun deleteHistory(): String {
      return try {
        store.clearHistory().toString()
      } catch (e: Exception) {
        Log.e(TAG, "清空 SQLite 历史失败", e)
        JSONObject().apply {
          put("ok", false)
          put("error", e.message ?: "清空失败")
        }.toString()
      }
    }

    @JavascriptInterface
    fun stats(): String {
      return try {
        store.stats().toString()
      } catch (e: Exception) {
        Log.e(TAG, "读取 SQLite 历史统计失败", e)
        JSONObject().apply {
          put("ok", false)
          put("error", e.message ?: "读取统计失败")
        }.toString()
      }
    }
  }

  private inner class HomeVaultBridge(private val store: HistoryDatabaseHelper) {
    @JavascriptInterface
    fun syncHistory(endpoint: String, data: String) {
      Thread {
        val result = JSONObject()
        try {
          val targetUrl = buildHomeVaultSyncUrl(endpoint)
          val bytes = data.toByteArray(Charsets.UTF_8)
          val url = URL(targetUrl)
          val wifiNetwork = if (isLocalHomeVaultTarget(url)) findWifiNetwork() else null
          val response = executeHomeVaultRequest(
            wifiNetwork,
            createConnection = { network ->
              (openHomeVaultConnection(url, network) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-VibeDrop-Client", "android")
                setFixedLengthStreamingMode(bytes.size)
              }
            },
            exchange = { connection ->
              connection.outputStream.use { output ->
                output.write(bytes)
              }
              val statusCode = connection.responseCode
              statusCode to readHttpResponseBody(connection, statusCode)
            }
          )

          result.put("ok", response.statusCode in 200..299)
          result.put("status", response.statusCode)
          result.put("body", response.body)
          result.put("url", targetUrl)
          result.put("network", response.network)
          if (response.statusCode !in 200..299) {
            result.put("error", extractErrorMessage(response.body) ?: "HTTP ${response.statusCode}")
          }
        } catch (e: Exception) {
          Log.e(TAG, "同步 Home Vault 失败", e)
          result.put("ok", false)
          result.put("error", e.message ?: "同步失败")
        } finally {
          emitBridgeEvent("native-home-vault-sync-result", result)
        }
      }.start()
    }

    @JavascriptInterface
    fun restoreHistory(endpoint: String, deviceId: String, mode: String) {
      Thread {
        val result = JSONObject()
        try {
          val targetUrl = buildHomeVaultRestoreUrl(endpoint, deviceId, mode)
          val url = URL(targetUrl)
          val wifiNetwork = if (isLocalHomeVaultTarget(url)) findWifiNetwork() else null
          emitRestoreProgress(
            phase = "requesting",
            message = "正在连接 Home Vault...",
            percent = 0
          )
          val response = executeHomeVaultRequest(
            wifiNetwork,
            createConnection = { network ->
              (openHomeVaultConnection(url, network) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 120_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-VibeDrop-Client", "android")
              }
            },
            exchange = { connection ->
              val statusCode = connection.responseCode
              statusCode to readHttpResponseBody(connection, statusCode)
            }
          )

          result.put("ok", response.statusCode in 200..299)
          result.put("status", response.statusCode)
          result.put("url", targetUrl)
          result.put("network", response.network)
          if (response.statusCode !in 200..299) {
            result.put("error", extractErrorMessage(response.body) ?: "HTTP ${response.statusCode}")
          } else if (mode == "full-media") {
            emitRestoreProgress(
              phase = "manifest",
              message = "已收到恢复清单，正在解析...",
              percent = 3
            )
            val restored = restoreFullMediaPayload(url, wifiNetwork, response.body)
            restored.keys().forEach { key ->
              result.put(key, restored.get(key))
            }
          } else {
            result.put("body", response.body)
          }
        } catch (e: Exception) {
          Log.e(TAG, "从 Home Vault 恢复失败", e)
          result.put("ok", false)
          result.put("error", e.message ?: "恢复失败")
        } finally {
          emitBridgeEvent("native-home-vault-restore-result", result)
        }
      }.start()
    }

    private fun emitRestoreProgress(
      phase: String,
      message: String,
      percent: Int,
      totalEntries: Int = 0,
      processedEntries: Int = 0,
      totalMedia: Int = 0,
      downloadedMedia: Int = 0,
      skippedMedia: Int = 0,
      failedMedia: Int = 0,
      downloadedBytes: Long = 0L,
      existingEntries: Int = 0,
      newEntries: Int = 0,
      currentFileName: String = "",
      currentFileBytes: Long = 0L,
      currentFileTotalBytes: Long = 0L
    ) {
      emitBridgeEvent(
        "native-home-vault-restore-progress",
        JSONObject().apply {
          put("phase", phase)
          put("message", message)
          put("percent", percent.coerceIn(0, 100))
          put("totalEntries", totalEntries)
          put("processedEntries", processedEntries)
          put("totalMedia", totalMedia)
          put("downloadedMedia", downloadedMedia)
          put("skippedMedia", skippedMedia)
          put("failedMedia", failedMedia)
          put("downloadedBytes", downloadedBytes)
          put("existingEntries", existingEntries)
          put("newEntries", newEntries)
          put("currentFileName", currentFileName)
          put("currentFileBytes", currentFileBytes)
          put("currentFileTotalBytes", currentFileTotalBytes)
        }
      )
    }

    private fun restoreFullMediaPayload(requestUrl: URL, wifiNetwork: Network?, body: String): JSONObject {
      val payload = JSONObject(body)
      val sourceHistory = payload.optJSONArray("history") ?: JSONArray()
      val restoredEntries = JSONArray()
      val totalEntries = sourceHistory.length()
      var totalMediaReferences = 0
      var mediaReferenceCount = 0
      var downloadedMediaCount = 0
      var skippedMediaCount = 0
      var failedMediaCount = 0
      var thumbnailCachedCount = 0
      var downloadedBytes = 0L
      val baseUrl = buildBaseUrl(requestUrl)

      for (entryIndex in 0 until totalEntries) {
        val sourceEntry = sourceHistory.optJSONObject(entryIndex) ?: continue
        totalMediaReferences += sourceEntry.optJSONArray("items")?.length() ?: 0
      }

      emitRestoreProgress(
        phase = "processing",
        message = "正在处理历史与媒体缓存...",
        percent = 5,
        totalEntries = totalEntries,
        totalMedia = totalMediaReferences
      )

      for (entryIndex in 0 until totalEntries) {
        val sourceEntry = sourceHistory.optJSONObject(entryIndex) ?: continue
        val entry = JSONObject(sourceEntry.toString())
        val items = sourceEntry.optJSONArray("items") ?: JSONArray()
        val restoredItems = JSONArray()
        val vaultEntryId = sourceEntry.optString("vaultEntryId").ifBlank { sourceEntry.optString("id") }
        if (vaultEntryId.isNotBlank()) {
          entry.put("vaultEntryId", vaultEntryId)
        }

        for (itemIndex in 0 until items.length()) {
          val sourceItem = items.optJSONObject(itemIndex) ?: continue
          val item = JSONObject(sourceItem.toString())
          mediaReferenceCount += 1

          val downloadUrl = item.optString("downloadUrl")
          val objectPath = item.optString("objectPath")
          val objectHash = item.optString("objectHash")
          if (downloadUrl.isNotBlank() || objectPath.isNotBlank()) {
            try {
              val fileName = item.optString("fileName").ifBlank { objectHash.ifBlank { objectPath } }
              emitRestoreProgress(
                phase = "processing",
                message = "正在恢复媒体：$fileName",
                percent = progressPercent(entryIndex, totalEntries),
                totalEntries = totalEntries,
                processedEntries = entryIndex,
                totalMedia = totalMediaReferences,
                downloadedMedia = downloadedMediaCount,
                skippedMedia = skippedMediaCount,
                failedMedia = failedMediaCount,
                downloadedBytes = downloadedBytes,
                currentFileName = fileName
              )
              val cached = downloadVaultObject(baseUrl, wifiNetwork, item) { currentBytes, totalBytes ->
                emitRestoreProgress(
                  phase = "processing",
                  message = "正在下载媒体：$fileName",
                  percent = progressPercent(entryIndex, totalEntries),
                  totalEntries = totalEntries,
                  processedEntries = entryIndex,
                  totalMedia = totalMediaReferences,
                  downloadedMedia = downloadedMediaCount,
                  skippedMedia = skippedMediaCount,
                  failedMedia = failedMediaCount,
                  downloadedBytes = downloadedBytes + currentBytes,
                  currentFileName = fileName,
                  currentFileBytes = currentBytes,
                  currentFileTotalBytes = totalBytes
                )
              }
              item.put("savedPath", cached.file.absolutePath)
              item.put("filePath", cached.file.absolutePath)
              item.put("sizeBytes", cached.bytes)
              if (cached.skipped) {
                skippedMediaCount += 1
              } else {
                downloadedMediaCount += 1
                downloadedBytes += cached.bytes
              }
            } catch (e: Exception) {
              Log.w(TAG, "恢复 Vault 媒体对象失败: ${objectHash.ifBlank { objectPath }}", e)
              item.put("missingReason", e.message ?: "download-failed")
              failedMediaCount += 1
            }
          }

          val thumbnailDataUrl = item.optString("thumbnailDataUrl")
          if (thumbnailDataUrl.startsWith("data:")) {
            runCatching {
              val thumb = cacheThumbnailDataUrl(thumbnailDataUrl, vaultEntryId.ifBlank { entryIndex.toString() }, itemIndex)
              if (thumb.isNotBlank()) {
                item.put("thumbnailPath", thumb)
                thumbnailCachedCount += 1
              }
            }.onFailure {
              Log.w(TAG, "缓存 Vault 缩略图失败", it)
            }
          }

          restoredItems.put(item)
        }

        if (restoredItems.length() > 0) {
          entry.put("items", restoredItems)
        }
        restoredEntries.put(entry)

        if (entryIndex == totalEntries - 1 || entryIndex % 25 == 0) {
          emitRestoreProgress(
            phase = "processing",
            message = "正在处理历史 ${entryIndex + 1}/$totalEntries...",
            percent = progressPercent(entryIndex + 1, totalEntries),
            totalEntries = totalEntries,
            processedEntries = entryIndex + 1,
            totalMedia = totalMediaReferences,
            downloadedMedia = downloadedMediaCount,
            skippedMedia = skippedMediaCount,
            failedMedia = failedMediaCount,
            downloadedBytes = downloadedBytes
          )
        }
      }

      val existingEntryCount = store.countExistingEntries(restoredEntries)
      val newEntryCount = (restoredEntries.length() - existingEntryCount).coerceAtLeast(0)

      emitRestoreProgress(
        phase = "writing",
        message = "正在写入手机 SQLite...",
        percent = 95,
        totalEntries = totalEntries,
        processedEntries = totalEntries,
        totalMedia = totalMediaReferences,
        downloadedMedia = downloadedMediaCount,
        skippedMedia = skippedMediaCount,
        failedMedia = failedMediaCount,
        downloadedBytes = downloadedBytes,
        existingEntries = existingEntryCount,
        newEntries = newEntryCount
      )
      val storeResult = store.upsertHistoryArray(restoredEntries.toString())
      emitRestoreProgress(
        phase = "done",
        message = "恢复完成",
        percent = 100,
        totalEntries = totalEntries,
        processedEntries = totalEntries,
        totalMedia = totalMediaReferences,
        downloadedMedia = downloadedMediaCount,
        skippedMedia = skippedMediaCount,
        failedMedia = failedMediaCount,
        downloadedBytes = downloadedBytes,
        existingEntries = existingEntryCount,
        newEntries = newEntryCount
      )
      return JSONObject().apply {
        put("ok", true)
        put("mode", "full-media")
        put("historyCount", payload.optInt("historyCount", totalEntries))
        put("returnedCount", totalEntries)
        put("restoredCount", storeResult.optInt("count", restoredEntries.length()))
        put("existingEntryCount", existingEntryCount)
        put("newEntryCount", newEntryCount)
        put("mediaReferenceCount", mediaReferenceCount)
        put("downloadedMediaCount", downloadedMediaCount)
        put("skippedMediaCount", skippedMediaCount)
        put("failedMediaCount", failedMediaCount)
        put("thumbnailCachedCount", thumbnailCachedCount)
        put("downloadedBytes", downloadedBytes)
      }
    }

    private fun progressPercent(processedEntries: Int, totalEntries: Int): Int {
      if (totalEntries <= 0) {
        return 5
      }
      return (5 + (processedEntries.coerceIn(0, totalEntries) * 90 / totalEntries)).coerceIn(5, 95)
    }

    private fun buildBaseUrl(url: URL): URL {
      val port = if (url.port > 0) ":${url.port}" else ""
      return URL("${url.protocol}://${url.host}$port/")
    }

    private fun downloadVaultObject(
      baseUrl: URL,
      wifiNetwork: Network?,
      item: JSONObject,
      onProgress: (Long, Long) -> Unit
    ): CachedVaultObject {
      val downloadUrl = item.optString("downloadUrl").ifBlank {
        val objectPath = item.optString("objectPath")
        if (objectPath.isNotBlank()) "/$objectPath" else ""
      }
      if (downloadUrl.isBlank()) {
        throw IllegalStateException("missing-download-url")
      }

      val objectHash = item.optString("objectHash").ifBlank { sha256(downloadUrl) }
      val ext = resolveVaultObjectExtension(item)
      val bucket = objectHash.take(2).ifBlank { "xx" }
      val dest = File(File(store.mediaCacheRoot(), "objects/$bucket").apply { mkdirs() }, "$objectHash$ext")
      if (dest.exists() && dest.length() > 0) {
        return CachedVaultObject(dest, dest.length(), skipped = true)
      }

      val url = URL(baseUrl, downloadUrl.removePrefix("/"))
      var connection: HttpURLConnection? = null
      try {
        connection = openHomeVaultConnection(url, wifiNetwork) as HttpURLConnection
        val bytes = downloadConnectionToFile(connection, dest, onProgress)
        return CachedVaultObject(dest, bytes, skipped = false)
      } catch (e: SocketException) {
        if (wifiNetwork == null || !isVpnBypassDenied(e)) {
          dest.delete()
          throw e
        }
        Log.w(TAG, "Vault 对象下载 Wi-Fi 绑定失败，降级默认网络重试", e)
        connection?.disconnect()
        connection = openHomeVaultConnection(url, null) as HttpURLConnection
        val bytes = downloadConnectionToFile(connection, dest, onProgress)
        return CachedVaultObject(dest, bytes, skipped = false)
      } catch (e: Exception) {
        dest.delete()
        throw e
      } finally {
        connection?.disconnect()
      }
    }

    private fun downloadConnectionToFile(
      connection: HttpURLConnection,
      dest: File,
      onProgress: (Long, Long) -> Unit
    ): Long {
      connection.requestMethod = "GET"
      connection.connectTimeout = 10_000
      connection.readTimeout = 180_000
      connection.setRequestProperty("Accept", "*/*")
      val statusCode = connection.responseCode
      if (statusCode !in 200..299) {
        throw IllegalStateException("HTTP $statusCode")
      }
      var total = 0L
      var lastEmitBytes = 0L
      var lastEmitMs = 0L
      val expectedBytes = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
      connection.inputStream.use { input ->
        dest.outputStream().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
              break
            }
            output.write(buffer, 0, read)
            total += read
            val now = System.currentTimeMillis()
            if (total - lastEmitBytes >= 512L * 1024L || now - lastEmitMs >= 800L) {
              onProgress(total, expectedBytes)
              lastEmitBytes = total
              lastEmitMs = now
            }
          }
        }
      }
      onProgress(total, expectedBytes)
      return total
    }

    private fun cacheThumbnailDataUrl(dataUrl: String, entryKey: String, itemIndex: Int): String {
      val comma = dataUrl.indexOf(',')
      if (comma < 0) {
        return ""
      }
      val header = dataUrl.substring(0, comma)
      if (!header.contains(";base64", ignoreCase = true)) {
        return ""
      }
      val mimeType = header.removePrefix("data:").substringBefore(';')
      val ext = extensionForMimeType(mimeType)
      val safeEntry = safeSegment(entryKey)
      val dest = File(File(store.mediaCacheRoot(), "thumbs/$safeEntry").apply { mkdirs() }, "$itemIndex$ext")
      if (!dest.exists() || dest.length() == 0L) {
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        dest.writeBytes(bytes)
      }
      return dest.absolutePath
    }

    private fun resolveVaultObjectExtension(item: JSONObject): String {
      val objectPathExt = File(item.optString("objectPath")).extension
      if (objectPathExt.isNotBlank()) {
        return ".${objectPathExt.lowercase()}"
      }
      val fileExt = File(item.optString("fileName")).extension
      if (fileExt.isNotBlank()) {
        return ".${fileExt.lowercase()}"
      }
      return extensionForMimeType(item.optString("mimeType"))
    }

    private fun extensionForMimeType(mimeType: String): String {
      return when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "video/mp4" -> ".mp4"
        "video/quicktime" -> ".mov"
        "application/pdf" -> ".pdf"
        else -> ".bin"
      }
    }

    private fun safeSegment(value: String): String {
      val cleaned = value.replace(Regex("""[^A-Za-z0-9._-]"""), "_").trim('.', '_', '-')
      return cleaned.take(80).ifBlank { "entry" }
    }

    private fun sha256(value: String): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }

    private fun executeHomeVaultRequest(
      wifiNetwork: Network?,
      createConnection: (Network?) -> HttpURLConnection,
      exchange: (HttpURLConnection) -> Pair<Int, String>
    ): HomeVaultResponse {
      var connection: HttpURLConnection? = null
      try {
        connection = createConnection(wifiNetwork)
        val (statusCode, body) = exchange(connection)
        return HomeVaultResponse(
          statusCode = statusCode,
          body = body,
          network = if (wifiNetwork != null) "wifi" else "default"
        )
      } catch (e: SocketException) {
        if (wifiNetwork == null || !isVpnBypassDenied(e)) {
          throw e
        }
        Log.w(TAG, "Home Vault Wi-Fi 网络绑定请求失败，降级使用默认网络重试", e)
        connection?.disconnect()
        connection = createConnection(null)
        val (statusCode, body) = exchange(connection)
        return HomeVaultResponse(
          statusCode = statusCode,
          body = body,
          network = "default"
        )
      } finally {
        connection?.disconnect()
      }
    }

    private fun buildHomeVaultSyncUrl(endpoint: String): String {
      val base = normalizeHomeVaultBaseUrl(endpoint)

      return if (base.endsWith("/api/android-history")) {
        base
      } else {
        "$base/api/android-history"
      }
    }

    private fun buildHomeVaultRestoreUrl(endpoint: String, deviceId: String, mode: String): String {
      val base = normalizeHomeVaultBaseUrl(endpoint)
      val apiBase = if (base.endsWith("/api/android-history")) {
        "$base/latest"
      } else {
        "$base/api/android-history/latest"
      }
      val query = mutableListOf("mode=${encodeQuery(mode.ifBlank { "compact" })}")
      if (deviceId.isNotBlank()) {
        query.add("deviceId=${encodeQuery(deviceId)}")
      }
      return "$apiBase?${query.joinToString("&")}"
    }

    private fun normalizeHomeVaultBaseUrl(endpoint: String): String {
      val trimmed = endpoint.trim().trimEnd('/')
      return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.isNotBlank() -> "http://$trimmed"
        else -> "http://minideMac-mini.local:8788"
      }.trimEnd('/')
    }

    private fun encodeQuery(value: String): String {
      return URLEncoder.encode(value, "UTF-8")
    }

    private fun isLocalHomeVaultTarget(url: URL): Boolean {
      val host = url.host?.lowercase().orEmpty()
      return host.endsWith(".local")
        || host.startsWith("192.168.")
        || host.startsWith("10.")
        || Regex("""^172\.(1[6-9]|2\d|3[0-1])\.""").containsMatchIn(host)
    }

    private fun findWifiNetwork(): Network? {
      return try {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          ?: return null
        connectivityManager.allNetworks.firstOrNull { network ->
          val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
          caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
      } catch (e: SecurityException) {
        Log.w(TAG, "缺少网络状态权限，Home Vault 同步将使用默认网络", e)
        null
      }
    }

    private fun openHomeVaultConnection(url: URL, wifiNetwork: Network?): URLConnection {
      if (wifiNetwork == null) {
        return url.openConnection()
      }

      return try {
        wifiNetwork.openConnection(url)
      } catch (e: SocketException) {
        if (isVpnBypassDenied(e)) {
          Log.w(TAG, "系统拒绝绑定 Wi-Fi 网络，Home Vault 将降级使用默认网络", e)
          url.openConnection()
        } else {
          throw e
        }
      } catch (e: SecurityException) {
        Log.w(TAG, "缺少绑定 Wi-Fi 网络权限，Home Vault 将降级使用默认网络", e)
        url.openConnection()
      }
    }

    private fun isVpnBypassDenied(error: Exception): Boolean {
      val message = error.message.orEmpty()
      return message.contains("EPERM", ignoreCase = true)
        || message.contains("Operation not permitted", ignoreCase = true)
    }

    private fun readHttpResponseBody(connection: HttpURLConnection, statusCode: Int): String {
      val stream = if (statusCode in 200..299) {
        connection.inputStream
      } else {
        connection.errorStream ?: connection.inputStream
      }
      return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun extractErrorMessage(body: String): String? {
      return runCatching {
        val json = JSONObject(body)
        json.optString("error").takeIf { it.isNotBlank() }
      }.getOrNull()
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
        val gateWasActive = BackgroundClipboardStartupGate.remainingMs(context) > 0L
        BackgroundClipboardStartupGate.clear(context)
        BackgroundClipboardConfigStore.save(context, configJson)
        requestBackgroundClipboardRefresh(context)
        if (gateWasActive) {
          Log.d(TAG, "后台剪贴板冷启动门控已解除，开始应用前端同步配置")
        }
        Log.d(TAG, "后台剪贴板配置已同步")
      } catch (e: Exception) {
        Log.e(TAG, "同步后台剪贴板配置失败", e)
      }
    }

    @JavascriptInterface
    fun getStatus(): String {
      return try {
        BackgroundClipboardDiagnosticsStore.toJson(context)
      } catch (e: Exception) {
        Log.e(TAG, "读取后台剪贴板状态失败", e)
        JSONObject().apply {
          put("error", e.message ?: e.javaClass.simpleName)
        }.toString()
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

  private fun maybeArmBackgroundClipboardStartupGate() {
    if (backgroundClipboardStartupGateArmedInProcess) {
      return
    }
    val untilMs = BackgroundClipboardStartupGate.arm(this)
    backgroundClipboardStartupGateArmedInProcess = true
    Log.d(TAG, "后台剪贴板冷启动门控已激活，等待前端同步，untilMs=$untilMs")
  }

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
    if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) {
      return
    }

    val sharedUris = extractSharedUris(intent)
    if (sharedUris.isEmpty()) {
      Log.w(TAG, "收到系统分享，但未找到文件 URI")
      return
    }

    val nextContents = mutableListOf<PendingSharedContent>()
    try {
      sharedUris.forEach { sharedUri ->
        val mimeType = contentResolver.getType(sharedUri) ?: intent.type ?: "application/octet-stream"
        val displayName = resolveSharedDisplayName(sharedUri, mimeType)
        val cachedFile = copySharedContentToCache(sharedUri, displayName)
        nextContents.add(
          PendingSharedContent(
            cachePath = cachedFile.absolutePath,
            displayName = cachedFile.name,
            mimeType = mimeType,
            sizeBytes = cachedFile.length(),
            isImage = mimeType.startsWith("image/")
          )
        )
      }

      clearPendingSharedContentsCache()
      pendingSharedContents.clear()
      pendingSharedContents.addAll(nextContents)

      Log.d(TAG, "已接收系统分享: ${nextContents.size} 项")
      emitPendingShareIfAvailable()
    } catch (e: Exception) {
      nextContents.forEach { content ->
        runCatching { File(content.cachePath).delete() }
          .onFailure { Log.w(TAG, "清理失败共享缓存失败", it) }
      }
      Log.e(TAG, "处理系统分享失败", e)
    }
  }

  private fun extractSharedUris(intent: Intent): List<Uri> {
    val uris = mutableListOf<Uri>()

    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
          ?.filterNotNull()
          ?.let { uris.addAll(it) }
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
          ?.filterNotNull()
          ?.let { uris.addAll(it) }
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
    } else {
      @Suppress("DEPRECATION")
      (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
    }

    intent.clipData?.let { clipData ->
      for (index in 0 until clipData.itemCount) {
        clipData.getItemAt(index)?.uri?.let { uris.add(it) }
      }
    }

    return uris.distinctBy { it.toString() }
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

  private fun clearPendingSharedContentsCache() {
    pendingSharedContents.forEach { content ->
      runCatching { File(content.cachePath).delete() }
        .onFailure { Log.w(TAG, "清理共享缓存失败", it) }
    }
    pendingSharedContents.clear()
  }

  private fun emitPendingShareIfAvailable() {
    if (pendingSharedContents.isEmpty()) {
      return
    }

    val items = JSONArray()
    pendingSharedContents.forEach { items.put(it.toJson()) }
    emitBridgeEvent(
      "native-incoming-share",
      JSONObject().apply {
        put("items", items)
      }
    )
  }

  private fun emitBridgeEvent(eventName: String, payload: JSONObject) {
    val webView = appWebView ?: return
    val js = "window.dispatchEvent(new CustomEvent(${JSONObject.quote(eventName)}, { detail: ${payload} }));"
    webView.post {
      webView.evaluateJavascript(js, null)
    }
  }
}
