package com.vibedrop.mobile.nativeapp.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibedrop.mobile.nativeapp.IncomingSharePayload
import com.vibedrop.mobile.nativeapp.MainActivity
import com.vibedrop.mobile.nativeapp.core.model.ConnectionSnapshot
import com.vibedrop.mobile.nativeapp.core.model.ConnectionStatus
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.core.model.DiscoveredDesktop
import com.vibedrop.mobile.nativeapp.data.AppContainer
import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.data.local.HistoryItemEntity
import com.vibedrop.mobile.nativeapp.data.repository.HistoryEntryWithItems
import com.vibedrop.mobile.nativeapp.network.DesktopConnectionController
import com.vibedrop.mobile.nativeapp.platform.AndroidDeviceIdentity
import com.vibedrop.mobile.nativeapp.platform.AndroidNetworkDiagnosticsSnapshot
import com.vibedrop.mobile.nativeapp.platform.BackgroundRunDiagnosticsSnapshot
import com.vibedrop.mobile.nativeapp.platform.ContentTransferResult
import com.vibedrop.mobile.nativeapp.platform.DiagnosticLogEvent
import com.vibedrop.mobile.nativeapp.platform.DiagnosticLogStore
import com.vibedrop.mobile.nativeapp.platform.MediaOpenMode
import com.vibedrop.mobile.nativeapp.platform.loadAndroidNetworkDiagnostics
import com.vibedrop.mobile.nativeapp.platform.loadBackgroundRunDiagnostics
import com.vibedrop.mobile.nativeapp.platform.openAppNotificationSettings
import com.vibedrop.mobile.nativeapp.platform.openAutoRevokeSettings
import com.vibedrop.mobile.nativeapp.platform.openBatteryOptimizationSettings
import com.vibedrop.mobile.nativeapp.platform.openHistoryMediaItem
import com.vibedrop.mobile.nativeapp.platform.readClipboardText
import com.vibedrop.mobile.nativeapp.platform.sendImageUriToMacClipboard
import com.vibedrop.mobile.nativeapp.platform.sendUriToDesktopInbox
import com.vibedrop.mobile.nativeapp.platform.IncomingFileReceiver
import com.vibedrop.mobile.nativeapp.platform.shareJsonFile
import com.vibedrop.mobile.nativeapp.platform.shareHistoryJson
import com.vibedrop.mobile.nativeapp.platform.startClipboardSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VibeDropApp(container: AppContainer) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var discoveredDesktops by remember { mutableStateOf<List<DiscoveredDesktop>>(emptyList()) }
    var discoveryStatus by remember { mutableStateOf("还未扫描附近 Mac") }
    var discoveryBusy by remember { mutableStateOf(false) }
    var pairingDesktopKey by remember { mutableStateOf<String?>(null) }
    var homeVaultUrl by rememberSaveable { mutableStateOf(container.homeVaultRepository.loadEndpoint()) }
    var homeVaultStatus by remember { mutableStateOf("还未同步") }
    var homeVaultBusy by remember { mutableStateOf(false) }
    var imageOpenMode by rememberSaveable { mutableStateOf(container.mediaOpenPreferences.loadImageMode()) }
    var videoOpenMode by rememberSaveable { mutableStateOf(container.mediaOpenPreferences.loadVideoMode()) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val devices by container.deviceRepository.observeDevices().collectAsState(initial = emptyList())
    val history by container.historyRepository.observeRecentWithItems(limit = 1000).collectAsState(initial = emptyList())
    val hostActivity = context as? MainActivity
    val sharedPayloadState = hostActivity?.sharedPayload?.collectAsState(initial = null)
    val sharedPayload = sharedPayloadState?.value
    val importHistoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取历史文件")
                    container.historyRepository.importArchive(raw)
                }
            }
            result
                .onSuccess { imported ->
                    Toast.makeText(context, "已导入 $imported 条新历史", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    Toast.makeText(context, "导入失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
        }
    }
    val exportHistoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入导出文件")
                }
            }
            result
                .onSuccess {
                    Toast.makeText(context, "历史已导出", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    Toast.makeText(context, "导出失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
        }
    }
    val controllers = remember(devices, appContext) {
        devices.associateBy(
            keySelector = { it.id },
            valueTransform = { device ->
                DesktopConnectionController(
                    device = device,
                    clientId = container.androidIdentity.deviceId,
                    clientName = container.androidIdentity.deviceName,
                    incomingFileReceiver = IncomingFileReceiver(appContext),
                    diagnosticLogStore = container.diagnosticLogStore,
                    onIncomingHistorySession = { rawJson ->
                        scope.launch(Dispatchers.IO) {
                            container.historyRepository.recordIncomingHistorySession(device, rawJson)
                        }
                    },
                    onIncomingFileSaved = { result ->
                        scope.launch(Dispatchers.IO) {
                            container.historyRepository.recordReceivedFile(device, result)
                        }
                    }
                )
            }
        )
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            container.legacyHistoryImporter.importIfNeeded()
        }
        if (result.error != null) {
            Toast.makeText(context, "旧历史迁移失败：${result.error}", Toast.LENGTH_LONG).show()
        } else if (result.imported > 0) {
            Toast.makeText(context, "已迁移旧历史 ${result.imported} 条", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(devices) {
        startClipboardSyncService(context)
    }

    DisposableEffect(controllers) {
        controllers.values.forEach { it.connect() }
        onDispose {
            controllers.values.forEach { it.close() }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF4F8FC)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onSettings = { selectedTab = 2 })
            when (selectedTab) {
                0 -> SendScreen(
                    devices = devices,
                    controllers = controllers,
                    sharedPayload = sharedPayload,
                    onConsumeSharedPayload = { payloadId -> hostActivity?.clearSharedPayload(payloadId) },
                    onOpenSettings = { selectedTab = 2 },
                    onRecordSentText = { device, text, pressEnter ->
                        scope.launch(Dispatchers.IO) {
                            container.historyRepository.recordSentText(device, text, pressEnter)
                        }
                    },
                    onRecordSentContent = { device, result, saveTarget ->
                        scope.launch(Dispatchers.IO) {
                            container.historyRepository.recordSentContent(
                                target = device,
                                fileName = result.fileName,
                                mimeType = result.mimeType,
                                sizeBytes = result.sizeBytes,
                                sourceUri = result.sourceUri,
                                transferId = result.transferId,
                                savedPath = result.savedPath,
                                saveTarget = saveTarget
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                1 -> HistoryScreen(
                    entries = history,
                    imageOpenMode = imageOpenMode,
                    videoOpenMode = videoOpenMode,
                    modifier = Modifier.weight(1f)
                )
                else -> SettingsScreen(
                    identity = container.androidIdentity,
                    devices = devices,
                    controllers = controllers,
                    discoveredDesktops = discoveredDesktops,
                    discoveryStatus = discoveryStatus,
                    discoveryBusy = discoveryBusy,
                    pairingDesktopKey = pairingDesktopKey,
                    homeVaultUrl = homeVaultUrl,
                    homeVaultStatus = homeVaultStatus,
                    homeVaultBusy = homeVaultBusy,
                    imageOpenMode = imageOpenMode,
                    videoOpenMode = videoOpenMode,
                    diagnosticLogStore = container.diagnosticLogStore,
                    onHomeVaultUrlChange = { homeVaultUrl = it },
                    onImageOpenModeChange = { mode ->
                        imageOpenMode = mode
                        container.mediaOpenPreferences.saveImageMode(mode)
                    },
                    onVideoOpenModeChange = { mode ->
                        videoOpenMode = mode
                        container.mediaOpenPreferences.saveVideoMode(mode)
                    },
                    onDiscover = {
                        if (discoveryBusy) return@SettingsScreen
                        scope.launch {
                            discoveryBusy = true
                            discoveryStatus = "正在扫描局域网..."
                            container.diagnosticLogStore.append("discovery", "start", JSONObject().put("savedDevices", devices.size))
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.discoveryRepository.discoverDesktops(devices)
                                }
                            }
                            result
                                .onSuccess { desktops ->
                                    container.diagnosticLogStore.append("discovery", "success", JSONObject().put("count", desktops.size))
                                    discoveredDesktops = desktops
                                    discoveryStatus = if (desktops.isEmpty()) {
                                        "没有发现附近 Mac"
                                    } else {
                                        "发现 ${desktops.size} 台 Mac"
                                    }
                                }
                                .onFailure { error ->
                                    container.diagnosticLogStore.append("discovery", "failure", JSONObject().put("error", error.message ?: "未知错误"))
                                    discoveryStatus = "扫描失败：${error.message ?: "未知错误"}"
                                }
                            discoveryBusy = false
                        }
                    },
                    onPairDesktop = { desktop ->
                        if (pairingDesktopKey != null) return@SettingsScreen
                        scope.launch {
                            pairingDesktopKey = desktop.key
                            container.diagnosticLogStore.append("pairing", "request_start", JSONObject().put("host", desktop.hostname).put("port", desktop.port))
                            val accepted = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.discoveryRepository.requestPairing(
                                        desktop = desktop,
                                        clientId = container.androidIdentity.deviceId,
                                        clientName = container.androidIdentity.deviceName
                                    )
                                }
                            }.getOrElse { error ->
                                container.diagnosticLogStore.append("pairing", "request_failure", JSONObject().put("error", error.message ?: "未知错误"))
                                discoveryStatus = "配对请求失败：${error.message ?: "未知错误"}"
                                pairingDesktopKey = null
                                return@launch
                            }

                            discoveryStatus = "请在 Mac 上确认配对码 ${accepted.code}"
                            var terminalStatus = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.discoveryRepository.pollPairing(desktop, accepted.requestId)
                                }
                            }.getOrNull()

                            val maxPolls = accepted.expiresInSecs.coerceIn(1, 180).toInt()
                            var pollIndex = 0
                            while (terminalStatus?.terminal != true && pollIndex < maxPolls) {
                                delay(1000)
                                terminalStatus = runCatching {
                                    withContext(Dispatchers.IO) {
                                        container.discoveryRepository.pollPairing(desktop, accepted.requestId)
                                    }
                                }.getOrElse { error ->
                                    discoveryStatus = "查询配对状态失败：${error.message ?: "未知错误"}"
                                    null
                                }
                                pollIndex += 1
                            }

                            val status = terminalStatus
                            if (status?.approved == true) {
                                container.diagnosticLogStore.append("pairing", "approved", JSONObject().put("hostname", status.hostname ?: desktop.hostname))
                                withContext(Dispatchers.IO) {
                                    container.deviceRepository.savePairedDesktop(desktop, status)
                                }
                                discoveryStatus = "已配对 ${status.hostname ?: desktop.hostname}"
                                selectedTab = 0
                            } else {
                                container.diagnosticLogStore.append("pairing", "not_approved", JSONObject().put("error", status?.error ?: "timeout"))
                                discoveryStatus = status?.error ?: "配对未完成，请重新发起"
                            }
                            pairingDesktopKey = null
                        }
                    },
                    onSyncHomeVault = {
                        if (homeVaultBusy) return@SettingsScreen
                        scope.launch {
                            homeVaultBusy = true
                            homeVaultStatus = "正在同步..."
                            container.diagnosticLogStore.append("home-vault", "sync_start", JSONObject().put("endpoint", homeVaultUrl))
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.homeVaultRepository.saveEndpoint(homeVaultUrl)
                                    val entries = container.historyRepository.loadAllEntriesWithItems()
                                    container.homeVaultRepository.syncHistory(homeVaultUrl, entries)
                                }
                            }
                            result
                                .onSuccess { sync ->
                                    container.diagnosticLogStore.append("home-vault", "sync_success", JSONObject().put("uploaded", sync.uploaded).put("vaultTotal", sync.vaultTotal))
                                    homeVaultStatus = "已同步 ${sync.uploaded} 条，Vault 当前 ${sync.vaultTotal} 条"
                                }
                                .onFailure { error ->
                                    container.diagnosticLogStore.append("home-vault", "sync_failure", JSONObject().put("error", error.message ?: "未知错误"))
                                    homeVaultStatus = "同步失败：${error.message ?: "未知错误"}"
                                }
                            homeVaultBusy = false
                        }
                    },
                    onImportHistory = {
                        importHistoryLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    onExportHistory = {
                        scope.launch {
                            val json = withContext(Dispatchers.IO) {
                                container.historyRepository.exportArchive(
                                    container.historyRepository.loadAllEntriesWithItems()
                                )
                            }
                            pendingExportJson = json
                            exportHistoryLauncher.launch(historyArchiveFileName())
                        }
                    },
                    onShareHistory = {
                        scope.launch {
                            val result = runCatching {
                                val fileName = historyArchiveFileName()
                                val json = withContext(Dispatchers.IO) {
                                    container.historyRepository.exportArchive(
                                        container.historyRepository.loadAllEntriesWithItems()
                                    )
                                }
                                shareHistoryJson(context, fileName, json)
                            }
                            result.onFailure { error ->
                                Toast.makeText(context, "分享失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onClearHistory = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                container.historyRepository.clearHistory()
                            }
                            Toast.makeText(context, "历史已清空", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDeleteDevice = { device ->
                        scope.launch(Dispatchers.IO) {
                            container.deviceRepository.deleteDesktop(device.id)
                            container.diagnosticLogStore.append("device", "deleted", JSONObject().put("deviceId", device.id).put("name", device.displayName))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "已删除 Mac：${device.displayName}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onSaveDevice = { deviceId, name, host, port, pin ->
                        scope.launch(Dispatchers.IO) {
                            val saved = if (deviceId == null) {
                                container.deviceRepository.saveManualDesktop(name, host, port, pin)
                            } else {
                                container.deviceRepository.updateDesktop(deviceId, name, host, port, pin)
                            }
                            container.diagnosticLogStore.append(
                                "device",
                                if (deviceId == null) "saved" else "updated",
                                JSONObject().put("deviceId", saved.id).put("name", saved.displayName)
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "已保存 Mac：${saved.displayName}", Toast.LENGTH_SHORT).show()
                                selectedTab = 0
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            BottomNav(
                selectedTab = if (selectedTab <= 1) selectedTab else -1,
                onSelect = { selectedTab = it }
            )
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF4E3F72)),
            contentAlignment = Alignment.Center
        ) {
            Text("V", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "VibeDrop",
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF111827)
        )
        OutlinedButton(onClick = onSettings) {
            Text("设置", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SendScreen(
    devices: List<DesktopDevice>,
    controllers: Map<String, DesktopConnectionController>,
    sharedPayload: IncomingSharePayload?,
    onConsumeSharedPayload: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onRecordSentText: (DesktopDevice, String, Boolean) -> Unit,
    onRecordSentContent: (DesktopDevice, ContentTransferResult, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts = remember { mutableStateMapOf<String, String>() }
    var pendingImageDeviceId by remember { mutableStateOf<String?>(null) }
    var pendingFileDeviceId by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val deviceId = pendingImageDeviceId
        pendingImageDeviceId = null
        if (uri == null || deviceId == null) return@rememberLauncherForActivityResult
        val controller = controllers[deviceId] ?: return@rememberLauncherForActivityResult
        val device = devices.firstOrNull { it.id == deviceId } ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    sendImageUriToMacClipboard(context, uri, controller)
                }
            }
            result
                .onSuccess {
                    onRecordSentContent(device, it, "clipboard")
                    Toast.makeText(context, "已发送图片到 Mac 剪贴板：${it.fileName}", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, "图片发送失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val deviceId = pendingFileDeviceId
        pendingFileDeviceId = null
        if (uri == null || deviceId == null) return@rememberLauncherForActivityResult
        val controller = controllers[deviceId] ?: return@rememberLauncherForActivityResult
        val device = devices.firstOrNull { it.id == deviceId } ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    sendUriToDesktopInbox(context, uri, controller)
                }
            }
            result
                .onSuccess {
                    onRecordSentContent(device, it, "inbox")
                    Toast.makeText(context, "已发送到 Mac 收件箱：${it.fileName}", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, "文件发送失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
        }
    }

    LaunchedEffect(sharedPayload?.id, devices) {
        val payload = sharedPayload ?: return@LaunchedEffect
        val text = payload.text ?: return@LaunchedEffect
        val firstDevice = devices.firstOrNull() ?: return@LaunchedEffect
        drafts[firstDevice.id] = text
        Toast.makeText(context, "已把分享文本填入 ${firstDevice.displayName}", Toast.LENGTH_SHORT).show()
        onConsumeSharedPayload(payload.id)
    }

    if (devices.isEmpty()) {
        EmptyDeviceState(
            modifier = modifier,
            onOpenSettings = onOpenSettings
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        sharedPayload?.takeIf { it.uris.isNotEmpty() }?.let { payload ->
            item(key = "shared-payload") {
                SharedPayloadCard(
                    payload = payload,
                    devices = devices,
                    controllers = controllers,
                    onRecordSentContent = onRecordSentContent,
                    onDismiss = { onConsumeSharedPayload(payload.id) }
                )
            }
        }
        items(devices, key = { it.id }) { device ->
            val controller = controllers.getValue(device.id)
            SendDeviceCard(
                device = device,
                connection = controller.connection,
                draft = drafts[device.id].orEmpty(),
                onDraftChange = { drafts[device.id] = it },
                onSend = { pressEnter ->
                    val fromInput = drafts[device.id].orEmpty().trim()
                    val text = fromInput.ifBlank { readClipboardText(context) }
                    if (text.isBlank()) {
                        Toast.makeText(context, "输入框和剪贴板都没有可发送文字", Toast.LENGTH_SHORT).show()
                        return@SendDeviceCard
                    }
                    if (controller.sendText(text, pressEnter)) {
                        drafts[device.id] = ""
                        onRecordSentText(device, text, pressEnter)
                    } else {
                        Toast.makeText(context, "发送失败：连接不可用", Toast.LENGTH_SHORT).show()
                    }
                },
                onEnter = {
                    if (!controller.sendEnter()) {
                        Toast.makeText(context, "回车失败：连接不可用", Toast.LENGTH_SHORT).show()
                    }
                },
                onPickImage = {
                    pendingImageDeviceId = device.id
                    imagePicker.launch("image/*")
                },
                onPickFile = {
                    pendingFileDeviceId = device.id
                    filePicker.launch(arrayOf("*/*"))
                }
            )
        }
    }
}

@Composable
private fun SharedPayloadCard(
    payload: IncomingSharePayload,
    devices: List<DesktopDevice>,
    controllers: Map<String, DesktopConnectionController>,
    onRecordSentContent: (DesktopDevice, ContentTransferResult, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = devices.firstOrNull { controllers[it.id]?.connection?.status == ConnectionStatus.Connected }
        ?: devices.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "系统分享",
                color = Color(0xFF111827),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${payload.uris.size} 个文件待发送到 Mac 收件箱",
                color = Color(0xFF667085),
                fontSize = 14.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val device = target ?: return@Button
                        val controller = controllers[device.id] ?: return@Button
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    payload.uris.forEach { uri ->
                                        val sent = sendUriToDesktopInbox(context, uri, controller)
                                        onRecordSentContent(device, sent, "inbox")
                                    }
                                }
                            }
                            result
                                .onSuccess {
                                    Toast.makeText(context, "分享文件已发送到 ${device.displayName}", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                .onFailure {
                                    Toast.makeText(context, "分享发送失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    enabled = target != null && controllers[target.id]?.connection?.status == ConnectionStatus.Connected,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168DF7))
                ) {
                    Text(
                        text = target?.let { "发送到 ${it.displayName}" } ?: "无可用 Mac",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.55f)
                ) {
                    Text("取消", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmptyDeviceState(
    modifier: Modifier,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有保存的 Mac",
                color = Color(0xFF111827),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "先在设置里填入 Mac 的 host、端口和 PIN。",
                color = Color(0xFF667085),
                fontSize = 15.sp
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        }
    }
}

@Composable
private fun SendDeviceCard(
    device: DesktopDevice,
    connection: ConnectionSnapshot,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (pressEnter: Boolean) -> Unit,
    onEnter: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit
) {
    val connected = connection.status == ConnectionStatus.Connected
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(statusColor(connection.status))
                )
                Text(
                    text = device.displayName,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    color = Color(0xFF111827),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusLabel(connection.status),
                    color = Color(0xFF667085),
                    fontSize = 15.sp
                )
            }

            if (!connection.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = connection.lastError,
                    color = Color(0xFFE5484D),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp),
                placeholder = { Text("输入要发送的文字...") },
                minLines = 4,
                maxLines = 4,
                shape = RoundedCornerShape(22.dp)
            )

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onSend(false) },
                    enabled = connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("发送", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onEnter,
                    enabled = connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("回车", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { onSend(true) },
                enabled = connected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168DF7))
            ) {
                Text("发送并回车", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onPickImage,
                    enabled = connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("传图到剪贴板", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("传到收件箱", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    entries: List<HistoryEntryWithItems>,
    imageOpenMode: MediaOpenMode,
    videoOpenMode: MediaOpenMode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf("all") }
    var selectedStatus by rememberSaveable { mutableStateOf("all") }
    var selectedSender by rememberSaveable { mutableStateOf("all") }
    var selectedReceiver by rememberSaveable { mutableStateOf("all") }
    var selectedTime by rememberSaveable { mutableStateOf(HistoryTimeFilter.All) }
    var selectedHourFilter by rememberSaveable { mutableStateOf(HistoryHourFilter.All) }
    var customStartDate by rememberSaveable { mutableStateOf("") }
    var customEndDate by rememberSaveable { mutableStateOf("") }
    var customStartTime by rememberSaveable { mutableStateOf("") }
    var customEndTime by rememberSaveable { mutableStateOf("") }
    var showCustomFilterDialog by rememberSaveable { mutableStateOf(false) }
    var draftStartDate by remember { mutableStateOf("") }
    var draftEndDate by remember { mutableStateOf("") }
    var draftHourFilter by remember { mutableStateOf(HistoryHourFilter.All) }
    var draftStartTime by remember { mutableStateOf("") }
    var draftEndTime by remember { mutableStateOf("") }
    var dayWindowOffset by rememberSaveable { mutableIntStateOf(0) }
    var selectedHour by remember { mutableStateOf<HeatmapSelection?>(null) }
    val now = remember(entries.size) { System.currentTimeMillis() }
    fun resetHeatmapWindow() {
        dayWindowOffset = 0
        selectedHour = null
    }
    fun openCustomFilterDialog(forceHourFilter: HistoryHourFilter? = null) {
        draftStartDate = customStartDate
        draftEndDate = customEndDate
        draftHourFilter = forceHourFilter ?: selectedHourFilter
        draftStartTime = customStartTime
        draftEndTime = customEndTime
        showCustomFilterDialog = true
    }
    fun applyCustomFilterDialog() {
        val cleanedStartDate = draftStartDate.trim()
        val cleanedEndDate = draftEndDate.trim()
        val cleanedStartTime = draftStartTime.trim()
        val cleanedEndTime = draftEndTime.trim()
        val validationError = validateHistoryCustomFilters(
            cleanedStartDate,
            cleanedEndDate,
            draftHourFilter,
            cleanedStartTime,
            cleanedEndTime
        )
        if (validationError != null) {
            Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
            return
        }
        customStartDate = cleanedStartDate
        customEndDate = cleanedEndDate
        selectedTime = if (cleanedStartDate.isNotBlank() || cleanedEndDate.isNotBlank()) {
            HistoryTimeFilter.Custom
        } else if (selectedTime == HistoryTimeFilter.Custom) {
            HistoryTimeFilter.All
        } else {
            selectedTime
        }
        selectedHourFilter = draftHourFilter
        customStartTime = if (draftHourFilter == HistoryHourFilter.Custom) cleanedStartTime else ""
        customEndTime = if (draftHourFilter == HistoryHourFilter.Custom) cleanedEndTime else ""
        showCustomFilterDialog = false
        resetHeatmapWindow()
    }
    fun clearCustomFilters() {
        customStartDate = ""
        customEndDate = ""
        customStartTime = ""
        customEndTime = ""
        selectedTime = HistoryTimeFilter.All
        selectedHourFilter = HistoryHourFilter.All
        showCustomFilterDialog = false
        resetHeatmapWindow()
    }
    val senderFilters = remember(entries) { buildHistoryEndpointFilters(entries, HistoryEndpoint.Sender) }
    val receiverFilters = remember(entries) { buildHistoryEndpointFilters(entries, HistoryEndpoint.Receiver) }
    val baseFiltered = remember(
        entries,
        query,
        selectedType,
        selectedStatus,
        selectedSender,
        selectedReceiver,
        selectedTime,
        selectedHourFilter,
        customStartDate,
        customEndDate,
        customStartTime,
        customEndTime,
        now
    ) {
        entries
            .asSequence()
            .filter { it.matchesQuery(query) }
            .filter { record ->
                selectedType == "all" ||
                    record.entry.kind == selectedType ||
                    record.items.any { it.kind == selectedType }
            }
            .filter { record ->
                selectedStatus == "all" ||
                    record.entry.status == selectedStatus ||
                    record.items.any { it.status == selectedStatus }
            }
            .filter { record ->
                selectedSender == "all" || record.matchesSenderFilter(selectedSender)
            }
            .filter { record ->
                selectedReceiver == "all" || record.matchesReceiverFilter(selectedReceiver)
            }
            .filter {
                it.entry.matchesTimeFilter(
                    selectedTime,
                    selectedHourFilter,
                    now,
                    customStartDate,
                    customEndDate,
                    customStartTime,
                    customEndTime
                )
            }
            .toList()
    }
    val visibleDays = remember(baseFiltered, now, dayWindowOffset) {
        buildVisibleDays(baseFiltered, now, dayWindowOffset)
    }
    val heatmapCounts = remember(baseFiltered, visibleDays) {
        buildHeatmapCounts(baseFiltered, visibleDays)
    }
    val visibleMax = heatmapCounts.values.maxOrNull() ?: 0
    val filteredEntries = remember(baseFiltered, selectedHour) {
        selectedHour?.let { selection ->
            baseFiltered.filter { record ->
                startOfDay(record.entry.timestampMillis) == selection.dayStartMillis &&
                    hourOfDay(record.entry.timestampMillis) == selection.hour
            }
        } ?: baseFiltered
    }
    val peak = heatmapCounts.maxByOrNull { it.value }

    if (showCustomFilterDialog) {
        HistoryCustomFilterDialog(
            startDate = draftStartDate,
            endDate = draftEndDate,
            hourFilter = draftHourFilter,
            startTime = draftStartTime,
            endTime = draftEndTime,
            onStartDateChange = { draftStartDate = it },
            onEndDateChange = { draftEndDate = it },
            onHourFilterChange = { draftHourFilter = it },
            onStartTimeChange = { draftStartTime = it },
            onEndTimeChange = { draftEndTime = it },
            onDismiss = { showCustomFilterDialog = false },
            onClear = { clearCustomFilters() },
            onApply = { applyCustomFilterDialog() }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "历史",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF111827)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "显示 ${filteredEntries.size} / ${entries.size} 条最近记录",
                fontSize = 15.sp,
                color = Color(0xFF667085)
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            resetHeatmapWindow()
                        },
                        placeholder = { Text("搜索文本、文件名、设备、状态") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        query = ""
                                        resetHeatmapWindow()
                                    }
                                ) {
                                    Text("清空")
                                }
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryFilterButton("全部时间", selectedTime == HistoryTimeFilter.All) {
                            selectedTime = HistoryTimeFilter.All
                            resetHeatmapWindow()
                        }
                        HistoryFilterButton("今天", selectedTime == HistoryTimeFilter.Today) {
                            selectedTime = HistoryTimeFilter.Today
                            resetHeatmapWindow()
                        }
                        HistoryFilterButton("近7天", selectedTime == HistoryTimeFilter.Last7Days) {
                            selectedTime = HistoryTimeFilter.Last7Days
                            resetHeatmapWindow()
                        }
                        HistoryFilterButton("近30天", selectedTime == HistoryTimeFilter.Last30Days) {
                            selectedTime = HistoryTimeFilter.Last30Days
                            resetHeatmapWindow()
                        }
                        HistoryFilterButton(
                            historyCustomDateLabel(customStartDate, customEndDate),
                            selectedTime == HistoryTimeFilter.Custom
                        ) {
                            openCustomFilterDialog()
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryHourFilter.entries.forEach { filter ->
                            HistoryFilterButton(
                                historyHourFilterLabel(filter, customStartTime, customEndTime),
                                selectedHourFilter == filter
                            ) {
                                if (filter == HistoryHourFilter.Custom) {
                                    openCustomFilterDialog(HistoryHourFilter.Custom)
                                } else {
                                    selectedHourFilter = filter
                                    resetHeatmapWindow()
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        historyTypeFilters.forEach { filter ->
                            HistoryFilterButton(filter.label, selectedType == filter.kind) {
                                selectedType = filter.kind
                                resetHeatmapWindow()
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        historyStatusFilters.forEach { filter ->
                            HistoryFilterButton(filter.label, selectedStatus == filter.status) {
                                selectedStatus = filter.status
                                resetHeatmapWindow()
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        senderFilters.forEach { filter ->
                            HistoryFilterButton(filter.label, selectedSender == filter.key) {
                                selectedSender = filter.key
                                resetHeatmapWindow()
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        receiverFilters.forEach { filter ->
                            HistoryFilterButton(filter.label, selectedReceiver == filter.key) {
                                selectedReceiver = filter.key
                                resetHeatmapWindow()
                            }
                        }
                    }
                    historyActiveFilterSummary(
                        selectedTime,
                        selectedHourFilter,
                        customStartDate,
                        customEndDate,
                        customStartTime,
                        customEndTime
                    ).takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            text = summary,
                            color = Color(0xFF667085),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "活跃热力图",
                                color = Color(0xFF111827),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${formatMonthDay(visibleDays.first())} - ${formatMonthDay(visibleDays.last())} · 当前窗口独立对比",
                                color = Color(0xFF667085),
                                fontSize = 14.sp
                            )
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    dayWindowOffset += 5
                                    selectedHour = null
                                }
                            ) {
                                Text("更早")
                            }
                            OutlinedButton(
                                onClick = {
                                    dayWindowOffset = (dayWindowOffset - 5).coerceAtLeast(0)
                                    selectedHour = null
                                },
                                enabled = dayWindowOffset > 0
                            ) {
                                Text("更近")
                            }
                            if (dayWindowOffset > 0 || selectedHour != null) {
                                OutlinedButton(
                                    onClick = {
                                        dayWindowOffset = 0
                                        selectedHour = null
                                    }
                                ) {
                                    Text("回到最近")
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryStatCard("发送", "${baseFiltered.size} 条", Modifier.weight(1f))
                        HistoryStatCard("峰值", peak?.let { "${it.key.second}:00 · ${it.value}" } ?: "无", Modifier.weight(1f))
                        HistoryStatCard("类型", historyTypeLabel(selectedType), Modifier.weight(1f))
                    }
                    HistoryHeatmap(
                        days = visibleDays,
                        counts = heatmapCounts,
                        maxCount = visibleMax,
                        selected = selectedHour,
                        onSelect = { selectedHour = it }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("少", color = Color(0xFF667085), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .weight(1f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFFEFF4F2))
                        ) {
                            Row(Modifier.fillMaxSize()) {
                                repeat(24) { index ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxSize()
                                            .background(heatColor(index + 1, 24))
                                    )
                                }
                            }
                        }
                        Text("多", color = Color(0xFF667085), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Text(
                        text = "暂无历史记录。发送成功后会写入 Room 数据库。",
                        modifier = Modifier.padding(18.dp),
                        color = Color(0xFF667085)
                    )
                }
            }
        }
        items(filteredEntries, key = { it.entry.id }) { record ->
            HistoryCard(
                record = record,
                onOpenItem = { item ->
                    val mode = if (item.kind == "video") videoOpenMode else imageOpenMode
                    runCatching {
                        openHistoryMediaItem(context, item, mode)
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "无法打开：${error.message ?: "没有可用应用"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }
}

@Composable
private fun HistoryFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color(0xFF111827) else Color(0xFFEFF4FA))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        color = if (selected) Color.White else Color(0xFF526174),
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        maxLines = 1
    )
}

@Composable
private fun HistoryCustomFilterDialog(
    startDate: String,
    endDate: String,
    hourFilter: HistoryHourFilter,
    startTime: String,
    endTime: String,
    onStartDateChange: (String) -> Unit,
    onEndDateChange: (String) -> Unit,
    onHourFilterChange: (HistoryHourFilter) -> Unit,
    onStartTimeChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("自定义筛选", fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("日期范围", color = Color(0xFF111827), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startDate,
                        onValueChange = onStartDateChange,
                        label = { Text("开始日期") },
                        placeholder = { Text("2026-05-01") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endDate,
                        onValueChange = onEndDateChange,
                        label = { Text("结束日期") },
                        placeholder = { Text("2026-05-28") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("一天内时段", color = Color(0xFF111827), fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryHourFilter.entries.forEach { filter ->
                        HistoryFilterButton(
                            historyHourFilterLabel(filter, startTime, endTime),
                            hourFilter == filter
                        ) {
                            onHourFilterChange(filter)
                        }
                    }
                }
                if (hourFilter == HistoryHourFilter.Custom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startTime,
                            onValueChange = onStartTimeChange,
                            label = { Text("开始时间") },
                            placeholder = { Text("09:00") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endTime,
                            onValueChange = onEndTimeChange,
                            label = { Text("结束时间") },
                            placeholder = { Text("18:30") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    text = "日期使用 YYYY-MM-DD，时间使用 HH:mm；留空表示不限制这一端。",
                    color = Color(0xFF667085),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) {
                Text("应用")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear) {
                    Text("清除")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
private fun HistoryStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF8FAFC))
            .padding(10.dp)
    ) {
        Text(label, color = Color(0xFF98A2B3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            text = value,
            color = Color(0xFF111827),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HistoryHeatmap(
    days: List<Long>,
    counts: Map<Pair<Long, Int>, Int>,
    maxCount: Int,
    selected: HeatmapSelection?,
    onSelect: (HeatmapSelection) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Spacer(Modifier.width(40.dp))
            days.forEach { dayStart ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = weekdayLabel(dayStart),
                        color = Color(0xFF667085),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatMonthDay(dayStart),
                        color = Color(0xFF111827),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        listOf(0, 6, 12, 18).forEach { blockStart ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%02d:00".format(blockStart),
                    modifier = Modifier.width(40.dp),
                    color = Color(0xFF98A2B3),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                days.forEach { dayStart ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(6) { offset ->
                            val hour = blockStart + offset
                            val selection = HeatmapSelection(dayStart, hour)
                            val count = counts[dayStart to hour] ?: 0
                            val isSelected = selected == selection
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .padding(horizontal = 3.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(heatColor(count, maxCount))
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) Color(0xFF168DF7) else Color.Transparent,
                                        shape = RoundedCornerShape(999.dp)
                                    )
                                    .clickable { if (count > 0) onSelect(selection) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    record: HistoryEntryWithItems,
    onOpenItem: (HistoryItemEntity) -> Unit
) {
    val entry = record.entry
    val items = remember(record.items) { record.items.sortedBy { it.itemIndex } }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatTime(entry.timestampMillis),
                color = Color(0xFF667085),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = entry.text.orEmpty().ifBlank { kindLabel(entry.kind) },
                color = Color(0xFF111827),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.take(12).forEach { item ->
                        HistoryItemPreview(
                            item = item,
                            onOpen = { onOpenItem(item) }
                        )
                    }
                    if (items.size > 12) {
                        HistoryMoreItemsChip(items.size - 12)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryChip(kindLabel(entry.kind))
                HistoryChip(entry.status)
                entry.itemCount?.takeIf { it > 1 }?.let { HistoryChip("${it} 项") }
                entry.senderName?.takeIf { it.isNotBlank() }?.let { HistoryChip(it) }
                entry.receiverName?.takeIf { it.isNotBlank() }?.let { HistoryChip(it) }
            }
        }
    }
}

@Composable
private fun HistoryItemPreview(
    item: HistoryItemEntity,
    onOpen: () -> Unit
) {
    val bitmap = remember(item.thumbnailDataUrl) { decodeThumbnailDataUrl(item.thumbnailDataUrl) }
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF4F7FB))
            .clickable(onClick = onOpen)
            .border(
                width = 1.dp,
                color = statusBorderColor(item.status),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(itemKindColor(item.kind)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = itemKindShortLabel(item),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = item.fileName.orEmpty().ifBlank { kindLabel(item.kind) },
            color = Color(0xFF111827),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = itemStatusLabel(item.status),
            color = if (item.status == "failed") Color(0xFFE5484D) else Color(0xFF667085),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HistoryMoreItemsChip(extraCount: Int) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(104.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFEFF4FA)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+$extraCount",
            color = Color(0xFF526174),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun HistoryChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFEFF4FA))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = Color(0xFF526174),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SettingsScreen(
    identity: AndroidDeviceIdentity,
    devices: List<DesktopDevice>,
    controllers: Map<String, DesktopConnectionController>,
    discoveredDesktops: List<DiscoveredDesktop>,
    discoveryStatus: String,
    discoveryBusy: Boolean,
    pairingDesktopKey: String?,
    homeVaultUrl: String,
    homeVaultStatus: String,
    homeVaultBusy: Boolean,
    imageOpenMode: MediaOpenMode,
    videoOpenMode: MediaOpenMode,
    diagnosticLogStore: DiagnosticLogStore,
    onHomeVaultUrlChange: (String) -> Unit,
    onImageOpenModeChange: (MediaOpenMode) -> Unit,
    onVideoOpenModeChange: (MediaOpenMode) -> Unit,
    onDiscover: () -> Unit,
    onPairDesktop: (DiscoveredDesktop) -> Unit,
    onSyncHomeVault: () -> Unit,
    onImportHistory: () -> Unit,
    onExportHistory: () -> Unit,
    onShareHistory: () -> Unit,
    onClearHistory: () -> Unit,
    onDeleteDevice: (DesktopDevice) -> Unit,
    onSaveDevice: (deviceId: String?, name: String, host: String, port: Int, pin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable { mutableStateOf("MacBook") }
    var host by rememberSaveable { mutableStateOf("overlorddeMacBook-Air-4.local") }
    var portText by rememberSaveable { mutableStateOf("9001") }
    var pin by rememberSaveable { mutableStateOf("1234") }
    var editingDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteArmedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearArmed by rememberSaveable { mutableStateOf(false) }
    var networkDiagnostics by remember { mutableStateOf<AndroidNetworkDiagnosticsSnapshot?>(null) }
    var backgroundDiagnostics by remember { mutableStateOf<BackgroundRunDiagnosticsSnapshot?>(null) }
    var diagnosticEvents by remember { mutableStateOf<List<DiagnosticLogEvent>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        scope.launch {
            backgroundDiagnostics = withContext(Dispatchers.IO) {
                loadBackgroundRunDiagnostics(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        networkDiagnostics = withContext(Dispatchers.IO) {
            loadAndroidNetworkDiagnostics(context)
        }
        backgroundDiagnostics = withContext(Dispatchers.IO) {
            loadBackgroundRunDiagnostics(context)
        }
        diagnosticEvents = withContext(Dispatchers.IO) {
            diagnosticLogStore.recent()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "设置",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF111827)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "可以先扫描局域网配对 Mac；扫描不到时再手动填写 host、端口和 PIN。",
                color = Color(0xFF667085),
                fontSize = 15.sp
            )
        }
        item {
            ConnectionDiagnosticsCard(
                identity = identity,
                devices = devices,
                controllers = controllers,
                diagnostics = networkDiagnostics,
                editingDeviceId = editingDeviceId,
                deleteArmedDeviceId = deleteArmedDeviceId,
                onRefresh = {
                    scope.launch {
                        networkDiagnostics = withContext(Dispatchers.IO) {
                            loadAndroidNetworkDiagnostics(context)
                        }
                    }
                },
                onEditDevice = { device ->
                    editingDeviceId = device.id
                    deleteArmedDeviceId = null
                    name = device.displayName
                    host = device.host
                    portText = device.port.toString()
                    pin = device.pin.orEmpty()
                    Toast.makeText(context, "已载入 ${device.displayName}，到下面“编辑 Mac”保存修改", Toast.LENGTH_SHORT).show()
                },
                onArmDeleteDevice = { device ->
                    deleteArmedDeviceId = device.id
                    Toast.makeText(context, "再次点击确认删除 ${device.displayName}", Toast.LENGTH_SHORT).show()
                },
                onDeleteDevice = { device ->
                    deleteArmedDeviceId = null
                    if (editingDeviceId == device.id) {
                        editingDeviceId = null
                    }
                    onDeleteDevice(device)
                }
            )
        }
        item {
            BackgroundRunCard(
                diagnostics = backgroundDiagnostics,
                onRefresh = {
                    scope.launch {
                        backgroundDiagnostics = withContext(Dispatchers.IO) {
                            loadBackgroundRunDiagnostics(context)
                        }
                    }
                },
                onOpenNotificationSettings = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppNotificationSettings(context)
                    }
                },
                onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
                onOpenAutoRevokeSettings = { openAutoRevokeSettings(context) }
            )
        }
        item {
            DiagnosticLogCard(
                events = diagnosticEvents,
                onRefresh = {
                    scope.launch {
                        diagnosticEvents = withContext(Dispatchers.IO) {
                            diagnosticLogStore.recent()
                        }
                    }
                },
                onShare = {
                    scope.launch {
                        val result = runCatching {
                            val network = networkDiagnostics ?: withContext(Dispatchers.IO) {
                                loadAndroidNetworkDiagnostics(context)
                            }
                            val background = backgroundDiagnostics ?: withContext(Dispatchers.IO) {
                                loadBackgroundRunDiagnostics(context)
                            }
                            val json = withContext(Dispatchers.IO) {
                                diagnosticLogStore.exportSnapshot(
                                    deviceName = identity.deviceName,
                                    deviceId = identity.deviceId,
                                    network = network,
                                    background = background
                                )
                            }
                            shareJsonFile(context, diagnosticArchiveFileName(), json, "分享诊断")
                        }
                        result.onFailure { error ->
                            Toast.makeText(context, "分享诊断失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("附近 Mac", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Text(discoveryStatus, color = Color(0xFF667085), fontSize = 14.sp)
                        }
                        OutlinedButton(
                            onClick = onDiscover,
                            enabled = !discoveryBusy && pairingDesktopKey == null
                        ) {
                            Text(if (discoveryBusy) "扫描中" else "扫描")
                        }
                    }

                    if (discoveredDesktops.isEmpty()) {
                        Text(
                            text = "扫描会使用 UDP 广播和已知设备 HTTP /discover 校验。Mac 端会继续使用现有 Tauri 服务。",
                            color = Color(0xFF98A2B3),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
        items(discoveredDesktops, key = { it.key }) { desktop ->
            DiscoveredDesktopCard(
                desktop = desktop,
                pairing = pairingDesktopKey == desktop.key,
                disabled = discoveryBusy || pairingDesktopKey != null,
                onPair = { onPairDesktop(desktop) }
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("家庭服务器", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = homeVaultStatus,
                        color = if (homeVaultStatus.startsWith("同步失败")) Color(0xFFE5484D) else Color(0xFF667085),
                        fontSize = 14.sp
                    )
                    OutlinedTextField(
                        value = homeVaultUrl,
                        onValueChange = onHomeVaultUrlChange,
                        label = { Text("Home Vault 地址") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onSyncHomeVault,
                        enabled = !homeVaultBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text(if (homeVaultBusy) "同步中" else "同步到 Mac mini", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("媒体打开方式", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = "控制历史页里点击图片或视频时交给哪个系统入口打开。",
                        color = Color(0xFF667085),
                        fontSize = 14.sp
                    )
                    MediaOpenModeSelector(
                        title = "图片",
                        selected = imageOpenMode,
                        onSelect = onImageOpenModeChange
                    )
                    MediaOpenModeSelector(
                        title = "视频",
                        selected = videoOpenMode,
                        onSelect = onVideoOpenModeChange
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("数据管理", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = "导入和导出都走系统文件选择器，清空只删除原生 Room 历史，不会删除旧 Tauri 的 history.json 源文件。",
                        color = Color(0xFF667085),
                        fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onImportHistory,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("导入历史", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onExportHistory,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("导出历史", fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onShareHistory,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("分享历史", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                if (clearArmed) {
                                    clearArmed = false
                                    onClearHistory()
                                } else {
                                    clearArmed = true
                                    Toast.makeText(context, "再次点击确认清空历史", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (clearArmed) "确认清空" else "清空历史",
                                color = Color(0xFFE5484D),
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (editingDeviceId == null) "手动 Mac" else "编辑 Mac",
                            modifier = Modifier.weight(1f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (editingDeviceId != null) {
                            OutlinedButton(
                                onClick = {
                                    editingDeviceId = null
                                    name = "MacBook"
                                    host = "overlorddeMacBook-Air-4.local"
                                    portText = "9001"
                                    pin = "1234"
                                }
                            ) {
                                Text("取消编辑")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("显示名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host 或 IP") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("PIN") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull()
                            if (host.isBlank() || pin.isBlank() || port == null) {
                                Toast.makeText(context, "Host、端口和 PIN 都要填写", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onSaveDevice(editingDeviceId, name, host, port, pin)
                            editingDeviceId = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text(if (editingDeviceId == null) "保存 Mac" else "保存修改", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogCard(
    events: List<DiagnosticLogEvent>,
    onRefresh: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("诊断日志", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = "记录连接、重连、文件传输和后台剪贴板事件，不记录正文和剪贴板内容。",
                        color = Color(0xFF667085),
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }
            if (events.isEmpty()) {
                Text("暂无诊断事件。连接或同步发生后会显示在这里。", color = Color(0xFF98A2B3), fontSize = 13.sp)
            } else {
                events.take(8).forEach { event ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(event.label, color = Color(0xFF111827), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = event.detail,
                            color = Color(0xFF667085),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Button(
                onClick = onShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168DF7))
            ) {
                Text("分享诊断包", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun BackgroundRunCard(
    diagnostics: BackgroundRunDiagnosticsSnapshot?,
    onRefresh: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutoRevokeSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("后台稳定性", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = diagnostics?.let {
                            if (it.issueCount == 0) "后台剪贴板和重连所需系统开关看起来正常。" else "发现 ${it.issueCount} 个可能影响后台连接的系统开关。"
                        } ?: "正在读取通知、电池和闲置应用状态...",
                        color = if ((diagnostics?.issueCount ?: 0) == 0) Color(0xFF667085) else Color(0xFFE5484D),
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }

            if (diagnostics == null) {
                Text("正在读取系统状态...", color = Color(0xFF98A2B3), fontSize = 13.sp)
            } else {
                BackgroundRunIssueRow(
                    title = "通知权限",
                    detail = if (diagnostics.notificationsEnabled) {
                        "已开启，前台服务通知可以正常显示。"
                    } else {
                        "未开启，系统可能限制前台服务提示，影响后台同步稳定性。"
                    },
                    ok = diagnostics.notificationsEnabled,
                    actionText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "请求权限" else "通知设置",
                    onAction = onOpenNotificationSettings
                )
                BackgroundRunIssueRow(
                    title = "电池优化",
                    detail = if (!diagnostics.batteryCheckAvailable) {
                        "当前系统没有返回电池优化状态，可从系统设置里手动确认。"
                    } else if (diagnostics.ignoresBatteryOptimizations) {
                        "已允许忽略电池优化，后台连接更不容易被系统杀掉。"
                    } else {
                        "仍受电池优化限制，建议把 VibeDrop 设为不限制。"
                    },
                    ok = diagnostics.batteryCheckAvailable && diagnostics.ignoresBatteryOptimizations,
                    actionText = "电池设置",
                    onAction = onOpenBatterySettings
                )
                diagnostics.autoRevokeWhitelisted?.let { whitelisted ->
                    BackgroundRunIssueRow(
                        title = "闲置应用管理",
                        detail = if (whitelisted) {
                            "系统不会因为长期闲置自动撤销权限。"
                        } else {
                            "系统可能在闲置后撤销权限或休眠应用，建议在应用详情里关闭相关开关。"
                        },
                        ok = whitelisted,
                        actionText = "应用设置",
                        onAction = onOpenAutoRevokeSettings
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundRunIssueRow(
    title: String,
    detail: String,
    ok: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF8FAFC))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF34C759) else Color(0xFFFFC45C))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, color = Color(0xFF111827), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(detail, color = Color(0xFF667085), fontSize = 12.sp)
        }
        OutlinedButton(onClick = onAction) {
            Text(actionText, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConnectionDiagnosticsCard(
    identity: AndroidDeviceIdentity,
    devices: List<DesktopDevice>,
    controllers: Map<String, DesktopConnectionController>,
    diagnostics: AndroidNetworkDiagnosticsSnapshot?,
    editingDeviceId: String?,
    deleteArmedDeviceId: String?,
    onRefresh: () -> Unit,
    onEditDevice: (DesktopDevice) -> Unit,
    onArmDeleteDevice: (DesktopDevice) -> Unit,
    onDeleteDevice: (DesktopDevice) -> Unit
) {
    val connectedCount = controllers.values.count { it.connection.status == ConnectionStatus.Connected }
    val reconnectingCount = controllers.values.count { it.connection.status == ConnectionStatus.Connecting }
    val recentErrors = devices
        .mapNotNull { device ->
            val connection = controllers[device.id]?.connection ?: return@mapNotNull null
            connection.lastError
                ?.takeIf { it.isNotBlank() }
                ?.let { "${device.displayName}: ${statusLabel(connection.status)} · $it" }
        }
        .distinct()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("连接诊断", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = "只显示网络、设备和连接状态，不读取正文内容。",
                        color = Color(0xFF667085),
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }

            DiagnosticRow("本机", identity.deviceName)
            DiagnosticRow("设备 ID", shortIdentity(identity.deviceId))
            DiagnosticRow("已保存 Mac", "${devices.size} 台")
            DiagnosticRow("当前连接", "${connectedCount} 已连接 · ${reconnectingCount} 重连中")

            val activeNetwork = diagnostics?.activeTransports?.joinToString(" + ").orEmpty()
            DiagnosticRow("当前网络", activeNetwork.ifBlank { "未识别到活动网络" })
            diagnostics?.let { snapshot ->
                DiagnosticRow(
                    "网络能力",
                    buildString {
                        append(if (snapshot.hasInternetCapability) "有 INTERNET 能力" else "缺少 INTERNET 能力")
                        append(" · ")
                        append(if (snapshot.isValidated) "系统已验证联网" else "系统未验证联网")
                    }
                )
                if (snapshot.activeTransports.contains("VPN")) {
                    Text(
                        text = "检测到 VPN：如果同步或局域网发现失败，优先确认 VPN 是否接管了本地网络路由。",
                        color = Color(0xFFE5484D),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (snapshot.localAddresses.isNotEmpty()) {
                    Text("本机局域网地址", color = Color(0xFF98A2B3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        snapshot.localAddresses.take(8).forEach { address ->
                            DiagnosticChip("${address.interfaceName} ${address.hostAddress}")
                        }
                    }
                }
            } ?: Text("正在读取系统网络状态...", color = Color(0xFF98A2B3), fontSize = 13.sp)

            if (devices.isNotEmpty()) {
                Text("已保存连接", color = Color(0xFF98A2B3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                devices.forEach { device ->
                    val connection = controllers[device.id]?.connection ?: device.connection
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(statusColor(connection.status))
                            )
                            Text(
                                text = device.displayName,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f),
                                color = Color(0xFF111827),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(statusLabel(connection.status), color = Color(0xFF667085), fontSize = 12.sp)
                        }
                        Text(
                            text = "${device.host}:${device.port}",
                            color = Color(0xFF667085),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        connection.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                            Text(
                                text = error,
                                color = Color(0xFFE5484D),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onEditDevice(device) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    if (editingDeviceId == device.id) "编辑中" else "编辑",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    if (deleteArmedDeviceId == device.id) {
                                        onDeleteDevice(device)
                                    } else {
                                        onArmDeleteDevice(device)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    if (deleteArmedDeviceId == device.id) "确认删除" else "删除",
                                    color = Color(0xFFE5484D),
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }

            if (recentErrors.isNotEmpty()) {
                Text("最近错误", color = Color(0xFF98A2B3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                recentErrors.take(4).forEach { error ->
                    Text(error, color = Color(0xFFE5484D), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            modifier = Modifier.width(86.dp),
            color = Color(0xFF98A2B3),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = Color(0xFF111827),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DiagnosticChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFEFF4FA))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color(0xFF526174),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MediaOpenModeSelector(
    title: String,
    selected: MediaOpenMode,
    onSelect: (MediaOpenMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(0xFF98A2B3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MediaOpenMode.entries.forEach { mode ->
                HistoryFilterButton(mode.label, selected == mode) {
                    onSelect(mode)
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDesktopCard(
    desktop: DiscoveredDesktop,
    pairing: Boolean,
    disabled: Boolean,
    onPair: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = desktop.hostname,
                    color = Color(0xFF111827),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${desktop.ip}:${desktop.port} · ${desktop.source}",
                    color = Color(0xFF667085),
                    fontSize = 13.sp
                )
            }
            Button(
                onClick = onPair,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168DF7))
            ) {
                Text(if (pairing) "配对中" else "配对")
            }
        }
    }
}

@Composable
private fun BottomNav(
    selectedTab: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = { onSelect(0) },
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (selectedTab == 0) Color(0xFFF8FAFC) else Color.Transparent)
        ) {
            Text("发送", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        TextButton(
            onClick = { onSelect(1) },
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (selectedTab == 1) Color(0xFFF8FAFC) else Color.Transparent)
        ) {
            Text("历史", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

private fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Connected -> Color(0xFF34C759)
    ConnectionStatus.Connecting -> Color(0xFFFFC45C)
    ConnectionStatus.Disconnected -> Color(0xFFD0D5DD)
    ConnectionStatus.Error -> Color(0xFFFF5A5F)
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.Connecting -> "连接中..."
    ConnectionStatus.Disconnected -> "未连接"
    ConnectionStatus.Error -> "连接失败"
}

private fun shortIdentity(value: String): String {
    return if (value.length <= 28) value else "${value.take(18)}...${value.takeLast(6)}"
}

private fun kindLabel(kind: String): String = when (kind) {
    "image" -> "图片"
    "video" -> "视频"
    "file" -> "文件"
    "media" -> "媒体"
    "text" -> "文本"
    else -> kind
}

private fun formatTime(timestampMillis: Long): String {
    return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.CHINA).format(Date(timestampMillis))
}

private fun historyArchiveFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "vibedrop_history_$stamp.json"
}

private fun diagnosticArchiveFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "vibedrop_diagnostics_$stamp.json"
}

private enum class HistoryTimeFilter {
    All,
    Today,
    Last7Days,
    Last30Days,
    Custom
}

private enum class HistoryHourFilter {
    All,
    Morning,
    Afternoon,
    Evening,
    Night,
    Custom
}

private data class HistoryTypeFilter(val kind: String, val label: String)

private data class HistoryStatusFilter(val status: String, val label: String)

private enum class HistoryEndpoint {
    Sender,
    Receiver
}

private data class HistoryEndpointFilter(val key: String, val label: String)

private data class HeatmapSelection(
    val dayStartMillis: Long,
    val hour: Int
)

private val historyTypeFilters = listOf(
    HistoryTypeFilter("all", "全部类型"),
    HistoryTypeFilter("text", "文本"),
    HistoryTypeFilter("media", "媒体"),
    HistoryTypeFilter("image", "图片"),
    HistoryTypeFilter("video", "视频"),
    HistoryTypeFilter("file", "文件")
)

private val historyStatusFilters = listOf(
    HistoryStatusFilter("all", "全部状态"),
    HistoryStatusFilter("success", "成功"),
    HistoryStatusFilter("pending", "进行中"),
    HistoryStatusFilter("partial", "部分完成"),
    HistoryStatusFilter("failed", "失败")
)

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

private fun buildHistoryEndpointFilters(
    entries: List<HistoryEntryWithItems>,
    endpoint: HistoryEndpoint
): List<HistoryEndpointFilter> {
    val counts = linkedMapOf<String, Pair<String, Int>>()
    entries.forEach { record ->
        val (id, name) = when (endpoint) {
            HistoryEndpoint.Sender -> record.entry.senderDeviceId to record.entry.senderName
            HistoryEndpoint.Receiver -> record.entry.receiverDeviceId to record.entry.receiverName
        }
        val label = name?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: return@forEach
        val key = historyParticipantKey(id, label)
        val current = counts[key]
        counts[key] = label to ((current?.second ?: 0) + 1)
    }
    val allLabel = when (endpoint) {
        HistoryEndpoint.Sender -> "全部发送端"
        HistoryEndpoint.Receiver -> "全部接收端"
    }
    return listOf(HistoryEndpointFilter("all", "$allLabel (${entries.size})")) +
        counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }
                    .thenBy { it.value.first }
            )
            .take(12)
            .map { (key, value) -> HistoryEndpointFilter(key, "${value.first} (${value.second})") }
}

private fun HistoryEntryWithItems.matchesSenderFilter(key: String): Boolean {
    val senderLabel = entry.senderName?.takeIf { it.isNotBlank() } ?: entry.senderDeviceId.orEmpty()
    return historyParticipantKey(entry.senderDeviceId, senderLabel) == key
}

private fun HistoryEntryWithItems.matchesReceiverFilter(key: String): Boolean {
    val receiverLabel = entry.receiverName?.takeIf { it.isNotBlank() } ?: entry.receiverDeviceId.orEmpty()
    return historyParticipantKey(entry.receiverDeviceId, receiverLabel) == key
}

private fun historyParticipantKey(id: String?, fallback: String): String {
    return (id?.takeIf { it.isNotBlank() } ?: fallback).trim().lowercase(Locale.US)
}

private fun HistoryEntryWithItems.matchesQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return true
    val rootEntry = this.entry
    val itemText = items.joinToString(" ") { item ->
        listOfNotNull(
            item.fileName,
            item.mimeType,
            item.status,
            item.localPath,
            item.savedPath,
            item.thumbnailPath,
            kindLabel(item.kind)
        ).joinToString(" ")
    }
    val haystack = listOfNotNull(
        rootEntry.text,
        rootEntry.senderName,
        rootEntry.receiverName,
        kindLabel(rootEntry.kind),
        rootEntry.status,
        rootEntry.saveTarget,
        rootEntry.direction,
        itemText
    ).joinToString(" ")
    return haystack.contains(trimmed, ignoreCase = true)
}

private fun HistoryEntryEntity.matchesTimeFilter(
    filter: HistoryTimeFilter,
    hourFilter: HistoryHourFilter,
    now: Long,
    customStartDate: String,
    customEndDate: String,
    customStartTime: String,
    customEndTime: String
): Boolean {
    val todayStart = startOfDay(now)
    val matchesDate = when (filter) {
        HistoryTimeFilter.All -> true
        HistoryTimeFilter.Today -> timestampMillis >= todayStart
        HistoryTimeFilter.Last7Days -> timestampMillis >= todayStart - 6L * DAY_MILLIS
        HistoryTimeFilter.Last30Days -> timestampMillis >= todayStart - 29L * DAY_MILLIS
        HistoryTimeFilter.Custom -> matchesCustomDateRange(timestampMillis, customStartDate, customEndDate)
    }
    return matchesDate && matchesHourRange(timestampMillis, hourFilter, customStartTime, customEndTime)
}

private fun matchesCustomDateRange(timestampMillis: Long, startDate: String, endDate: String): Boolean {
    val dayKey = dateKey(timestampMillis)
    if (startDate.isNotBlank() && dayKey < startDate) return false
    if (endDate.isNotBlank() && dayKey > endDate) return false
    return true
}

private fun matchesHourRange(
    timestampMillis: Long,
    filter: HistoryHourFilter,
    customStartTime: String,
    customEndTime: String
): Boolean {
    val minute = minuteOfDay(timestampMillis)
    val range = when (filter) {
        HistoryHourFilter.All -> return true
        HistoryHourFilter.Morning -> 6 * 60 to 11 * 60 + 59
        HistoryHourFilter.Afternoon -> 12 * 60 to 17 * 60 + 59
        HistoryHourFilter.Evening -> 18 * 60 to 23 * 60 + 59
        HistoryHourFilter.Night -> 0 to 5 * 60 + 59
        HistoryHourFilter.Custom -> {
            val start = parseClockMinutes(customStartTime) ?: 0
            val end = parseClockMinutes(customEndTime) ?: 23 * 60 + 59
            start to end
        }
    }
    return minute in range.first..range.second
}

private fun validateHistoryCustomFilters(
    startDate: String,
    endDate: String,
    hourFilter: HistoryHourFilter,
    startTime: String,
    endTime: String
): String? {
    if (startDate.isNotBlank() && !isValidDateKey(startDate)) return "开始日期格式应为 YYYY-MM-DD"
    if (endDate.isNotBlank() && !isValidDateKey(endDate)) return "结束日期格式应为 YYYY-MM-DD"
    if (startDate.isNotBlank() && endDate.isNotBlank() && startDate > endDate) return "开始日期不能晚于结束日期"
    if (hourFilter != HistoryHourFilter.Custom) return null
    val startMinutes = if (startTime.isBlank()) null else parseClockMinutes(startTime)
    val endMinutes = if (endTime.isBlank()) null else parseClockMinutes(endTime)
    if (startTime.isNotBlank() && startMinutes == null) return "开始时间格式应为 HH:mm"
    if (endTime.isNotBlank() && endMinutes == null) return "结束时间格式应为 HH:mm"
    if (startMinutes != null && endMinutes != null && startMinutes > endMinutes) return "开始时间不能晚于结束时间"
    return null
}

private fun isValidDateKey(value: String): Boolean {
    val parts = value.split("-")
    if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return false
    val year = parts[0].toIntOrNull() ?: return false
    val month = parts[1].toIntOrNull() ?: return false
    val day = parts[2].toIntOrNull() ?: return false
    val calendar = Calendar.getInstance(Locale.CHINA)
    calendar.isLenient = false
    return runCatching {
        calendar.set(year, month - 1, day, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.time
        true
    }.getOrDefault(false)
}

private fun parseClockMinutes(value: String): Int? {
    val parts = value.split(":")
    if (parts.size != 2 || parts[0].length != 2 || parts[1].length != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun buildVisibleDays(
    entries: List<HistoryEntryWithItems>,
    now: Long,
    dayWindowOffset: Int
): List<Long> {
    val anchor = entries.maxOfOrNull { it.entry.timestampMillis } ?: now
    val end = startOfDay(anchor) - dayWindowOffset.toLong().coerceAtLeast(0L) * DAY_MILLIS
    return (4 downTo 0).map { end - it * DAY_MILLIS }
}

private fun buildHeatmapCounts(
    entries: List<HistoryEntryWithItems>,
    days: List<Long>
): Map<Pair<Long, Int>, Int> {
    val visibleDays = days.toSet()
    return entries
        .map { startOfDay(it.entry.timestampMillis) to hourOfDay(it.entry.timestampMillis) }
        .filter { it.first in visibleDays }
        .groupingBy { it }
        .eachCount()
}

private fun startOfDay(timestampMillis: Long): Long {
    val calendar = Calendar.getInstance(Locale.CHINA)
    calendar.timeInMillis = timestampMillis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun hourOfDay(timestampMillis: Long): Int {
    val calendar = Calendar.getInstance(Locale.CHINA)
    calendar.timeInMillis = timestampMillis
    return calendar.get(Calendar.HOUR_OF_DAY)
}

private fun minuteOfDay(timestampMillis: Long): Int {
    val calendar = Calendar.getInstance(Locale.CHINA)
    calendar.timeInMillis = timestampMillis
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

private fun dateKey(timestampMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestampMillis))
}

private fun heatColor(count: Int, maxCount: Int): Color {
    if (count <= 0 || maxCount <= 0) return Color(0xFFF1F4F7)
    val t = kotlin.math.sqrt((count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f))
    return if (t < 0.58f) {
        lerpColor(Color.White, Color(0xFF5DDD8A), t / 0.58f)
    } else {
        lerpColor(Color(0xFF5DDD8A), Color(0xFF08130D), (t - 0.58f) / 0.42f)
    }
}

private fun lerpColor(from: Color, to: Color, t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * clamped,
        green = from.green + (to.green - from.green) * clamped,
        blue = from.blue + (to.blue - from.blue) * clamped,
        alpha = 1f
    )
}

private fun formatMonthDay(timestampMillis: Long): String {
    return SimpleDateFormat("M/d", Locale.CHINA).format(Date(timestampMillis))
}

private fun weekdayLabel(timestampMillis: Long): String {
    return SimpleDateFormat("E", Locale.CHINA).format(Date(timestampMillis))
}

private fun historyTypeLabel(kind: String): String {
    return historyTypeFilters.firstOrNull { it.kind == kind }?.label ?: kindLabel(kind)
}

private fun historyCustomDateLabel(startDate: String, endDate: String): String {
    if (startDate.isBlank() && endDate.isBlank()) return "自定义日期"
    return "${startDate.ifBlank { "开始" }} - ${endDate.ifBlank { "结束" }}"
}

private fun historyHourFilterLabel(
    filter: HistoryHourFilter,
    customStartTime: String,
    customEndTime: String
): String = when (filter) {
    HistoryHourFilter.All -> "全天"
    HistoryHourFilter.Morning -> "上午"
    HistoryHourFilter.Afternoon -> "下午"
    HistoryHourFilter.Evening -> "晚上"
    HistoryHourFilter.Night -> "凌晨"
    HistoryHourFilter.Custom -> if (customStartTime.isBlank() && customEndTime.isBlank()) {
        "自定义时段"
    } else {
        "${customStartTime.ifBlank { "00:00" }} - ${customEndTime.ifBlank { "23:59" }}"
    }
}

private fun historyActiveFilterSummary(
    timeFilter: HistoryTimeFilter,
    hourFilter: HistoryHourFilter,
    customStartDate: String,
    customEndDate: String,
    customStartTime: String,
    customEndTime: String
): String {
    val labels = mutableListOf<String>()
    if (timeFilter == HistoryTimeFilter.Custom) {
        labels += "日期：${historyCustomDateLabel(customStartDate, customEndDate)}"
    }
    if (hourFilter != HistoryHourFilter.All) {
        labels += "时段：${historyHourFilterLabel(hourFilter, customStartTime, customEndTime)}"
    }
    return labels.joinToString(" · ")
}

private fun decodeThumbnailDataUrl(value: String?): ImageBitmap? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return null
    val base64 = raw.substringAfter("base64,", raw)
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private fun itemKindShortLabel(item: HistoryItemEntity): String {
    val extension = item.fileName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() && it.length <= 5 }
        ?.uppercase(Locale.US)
    return extension ?: when (item.kind) {
        "image" -> "IMG"
        "video" -> "VID"
        "media" -> "MEDIA"
        "file" -> "FILE"
        else -> kindLabel(item.kind)
    }
}

private fun itemKindColor(kind: String): Color = when (kind) {
    "image" -> Color(0xFF168DF7)
    "video" -> Color(0xFF8B5CF6)
    "media" -> Color(0xFF0F766E)
    "file" -> Color(0xFF475467)
    else -> Color(0xFF667085)
}

private fun statusBorderColor(status: String?): Color = when (status) {
    "success" -> Color(0xFFD7F4DF)
    "failed" -> Color(0xFFFFC9C9)
    "partial" -> Color(0xFFFFE2A8)
    else -> Color(0xFFE4EAF2)
}

private fun itemStatusLabel(status: String?): String = when (status) {
    "success" -> "已保存"
    "failed" -> "失败"
    "partial" -> "部分完成"
    "pending" -> "接收中"
    else -> status.orEmpty().ifBlank { "未知状态" }
}
