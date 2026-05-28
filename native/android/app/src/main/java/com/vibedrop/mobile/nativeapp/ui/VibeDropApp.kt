package com.vibedrop.mobile.nativeapp.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibedrop.mobile.nativeapp.core.model.ConnectionSnapshot
import com.vibedrop.mobile.nativeapp.core.model.ConnectionStatus
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.core.model.DiscoveredDesktop
import com.vibedrop.mobile.nativeapp.data.AppContainer
import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.network.DesktopConnectionController
import com.vibedrop.mobile.nativeapp.platform.readClipboardText
import com.vibedrop.mobile.nativeapp.platform.startClipboardSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VibeDropApp(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var discoveredDesktops by remember { mutableStateOf<List<DiscoveredDesktop>>(emptyList()) }
    var discoveryStatus by remember { mutableStateOf("还未扫描附近 Mac") }
    var discoveryBusy by remember { mutableStateOf(false) }
    var pairingDesktopKey by remember { mutableStateOf<String?>(null) }
    val devices by container.deviceRepository.observeDevices().collectAsState(initial = emptyList())
    val history by container.historyRepository.observeRecent().collectAsState(initial = emptyList())
    val controllers = remember(devices) {
        devices.associateBy(
            keySelector = { it.id },
            valueTransform = {
                DesktopConnectionController(
                    device = it,
                    clientId = "native_android_preview",
                    clientName = "VibeDrop Native Preview"
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
                    onOpenSettings = { selectedTab = 2 },
                    onRecordSentText = { device, text, pressEnter ->
                        scope.launch(Dispatchers.IO) {
                            container.historyRepository.recordSentText(device, text, pressEnter)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                1 -> HistoryScreen(
                    entries = history,
                    modifier = Modifier.weight(1f)
                )
                else -> SettingsScreen(
                    discoveredDesktops = discoveredDesktops,
                    discoveryStatus = discoveryStatus,
                    discoveryBusy = discoveryBusy,
                    pairingDesktopKey = pairingDesktopKey,
                    onDiscover = {
                        if (discoveryBusy) return@SettingsScreen
                        scope.launch {
                            discoveryBusy = true
                            discoveryStatus = "正在扫描局域网..."
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.discoveryRepository.discoverDesktops(devices)
                                }
                            }
                            result
                                .onSuccess { desktops ->
                                    discoveredDesktops = desktops
                                    discoveryStatus = if (desktops.isEmpty()) {
                                        "没有发现附近 Mac"
                                    } else {
                                        "发现 ${desktops.size} 台 Mac"
                                    }
                                }
                                .onFailure { error ->
                                    discoveryStatus = "扫描失败：${error.message ?: "未知错误"}"
                                }
                            discoveryBusy = false
                        }
                    },
                    onPairDesktop = { desktop ->
                        if (pairingDesktopKey != null) return@SettingsScreen
                        scope.launch {
                            pairingDesktopKey = desktop.key
                            val accepted = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.discoveryRepository.requestPairing(
                                        desktop = desktop,
                                        clientId = "native_android_preview",
                                        clientName = "VibeDrop Native Preview"
                                    )
                                }
                            }.getOrElse { error ->
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
                                withContext(Dispatchers.IO) {
                                    container.deviceRepository.savePairedDesktop(desktop, status)
                                }
                                discoveryStatus = "已配对 ${status.hostname ?: desktop.hostname}"
                                selectedTab = 0
                            } else {
                                discoveryStatus = status?.error ?: "配对未完成，请重新发起"
                            }
                            pairingDesktopKey = null
                        }
                    },
                    onSaveDevice = { name, host, port, pin ->
                        scope.launch(Dispatchers.IO) {
                            container.deviceRepository.saveManualDesktop(name, host, port, pin)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "已保存 Mac：${name.ifBlank { host }}", Toast.LENGTH_SHORT).show()
                                selectedTab = 0
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            BottomNav(
                selectedTab = selectedTab.coerceAtMost(1),
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
    onOpenSettings: () -> Unit,
    onRecordSentText: (DesktopDevice, String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drafts = remember { mutableStateMapOf<String, String>() }

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
                }
            )
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
    onEnter: () -> Unit
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
                    onClick = { },
                    enabled = connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("传图到剪贴板", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { },
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
    entries: List<HistoryEntryEntity>,
    modifier: Modifier = Modifier
) {
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
                text = "当前原生库 ${entries.size} 条最近记录",
                fontSize = 15.sp,
                color = Color(0xFF667085)
            )
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
        items(entries, key = { it.id }) { entry ->
            HistoryCard(entry)
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntryEntity) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryChip(kindLabel(entry.kind))
                HistoryChip(entry.status)
                entry.receiverName?.takeIf { it.isNotBlank() }?.let { HistoryChip(it) }
            }
        }
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
    discoveredDesktops: List<DiscoveredDesktop>,
    discoveryStatus: String,
    discoveryBusy: Boolean,
    pairingDesktopKey: String?,
    onDiscover: () -> Unit,
    onPairDesktop: (DiscoveredDesktop) -> Unit,
    onSaveDevice: (name: String, host: String, port: Int, pin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable { mutableStateOf("MacBook") }
    var host by rememberSaveable { mutableStateOf("overlorddeMacBook-Air-4.local") }
    var portText by rememberSaveable { mutableStateOf("9001") }
    var pin by rememberSaveable { mutableStateOf("1234") }
    val context = LocalContext.current

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
                    Text("手动 Mac", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
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
                            onSaveDevice(name, host, port, pin)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text("保存 Mac", fontWeight = FontWeight.ExtraBold)
                    }
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

private fun kindLabel(kind: String): String = when (kind) {
    "image" -> "图片"
    "video" -> "视频"
    "file" -> "文件"
    "media" -> "媒体"
    else -> "text"
}

private fun formatTime(timestampMillis: Long): String {
    return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.CHINA).format(Date(timestampMillis))
}
