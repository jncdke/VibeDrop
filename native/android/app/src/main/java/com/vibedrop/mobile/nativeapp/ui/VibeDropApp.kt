package com.vibedrop.mobile.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.vibedrop.mobile.nativeapp.core.model.ConnectionSnapshot
import com.vibedrop.mobile.nativeapp.core.model.ConnectionStatus
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.network.DesktopConnectionController
import com.vibedrop.mobile.nativeapp.platform.readClipboardText

@Composable
fun VibeDropApp() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val devices = remember { previewDevices() }
    val controllers = remember {
        devices.associateWith {
            DesktopConnectionController(
                device = it,
                clientId = "native_android_preview",
                clientName = "VibeDrop Native Preview"
            )
        }
    }

    DisposableEffect(Unit) {
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
            Header()
            when (selectedTab) {
                0 -> SendScreen(
                    controllers = controllers,
                    modifier = Modifier.weight(1f)
                )
                else -> HistoryPlaceholder(modifier = Modifier.weight(1f))
            }
            BottomNav(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    }
}

@Composable
private fun Header() {
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
        OutlinedButton(onClick = { }) {
            Text("设置", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SendScreen(
    controllers: Map<DesktopDevice, DesktopConnectionController>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drafts = remember { mutableStateMapOf<String, String>() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(controllers.keys.toList(), key = { it.id }) { device ->
            val controller = controllers.getValue(device)
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
                    fontSize = 20.sp
                )
                Text(
                    text = statusLabel(connection.status),
                    color = Color(0xFF667085),
                    fontSize = 15.sp
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
private fun HistoryPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "历史页原生实现将在 Room 迁移后接入",
            color = Color(0xFF667085),
            fontSize = 17.sp
        )
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

private fun previewDevices(): List<DesktopDevice> = listOf(
    DesktopDevice(
        id = "desktop_demo_macbook",
        stableId = "desktop_demo_macbook",
        displayName = "overlorddeMacBook-Air-4.local",
        host = "overlorddeMacBook-Air-4.local",
        ip = "192.168.3.10",
        port = 9001,
        pin = "1234",
        connection = ConnectionSnapshot(ConnectionStatus.Connected)
    ),
    DesktopDevice(
        id = "desktop_demo_macmini",
        stableId = "desktop_demo_macmini",
        displayName = "minideMac-mini.local",
        host = "minideMac-mini.local",
        ip = "192.168.3.2",
        port = 9001,
        pin = "1234",
        connection = ConnectionSnapshot(ConnectionStatus.Connecting)
    )
)
