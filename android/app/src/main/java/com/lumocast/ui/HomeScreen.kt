package com.lumocast.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lumocast.core.net.DeviceScanner
import com.lumocast.core.net.DiscoveredDevice
import com.lumocast.data.LiveSession
import com.lumocast.data.Prefs
import com.lumocast.service.HostService
import com.lumocast.ui.components.EmptyState
import com.lumocast.ui.components.GlassCard
import com.lumocast.ui.components.GradientButton
import com.lumocast.ui.components.PinDialog
import com.lumocast.ui.components.Pill
import com.lumocast.ui.components.PulseDot
import com.lumocast.ui.components.SectionHeader
import com.lumocast.ui.theme.Lumo
import com.lumocast.ui.theme.LumoBlue
import com.lumocast.ui.theme.LumoMint
import com.lumocast.ui.theme.LumoBlueDeep
import com.lumocast.ui.theme.LumoViolet
import com.lumocast.ui.theme.LumoVioletDeep

@Composable
fun HomeScreen(
    onOpenHost: () -> Unit,
    onOpenViewer: (ip: String, port: Int, pin: String?, name: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val hostState by LiveSession.host.collectAsState()

    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var pendingDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }

    val scanner = remember { DeviceScanner { list -> devices = list } }
    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }

    // 首次进入时一次性申请：通知（前台服务）+ 录音（AudioPlaybackCapture 采系统音频）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.RECORD_AUDIO
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            HostService.start(context, result.resultCode, data)
            onOpenHost()
        }
    }

    fun requestCapture() {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(LumoVioletDeep, LumoBlueDeep))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Cast, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "LumoCast",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, "设置", tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        item {
            Text(
                if (hostState.running) "这台手机正在共享屏幕" else "同一个 Wi-Fi，投到任何屏幕",
                style = MaterialTheme.typography.bodyMedium,
                color = Lumo.Subtle
            )
        }

        // ---------------------------------------------------------- 主机卡片
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(LumoVioletDeep.copy(alpha = 0.98f), LumoBlueDeep.copy(alpha = 0.98f))
                        )
                    )
                    .clickable {
                        if (hostState.running) onOpenHost() else requestCapture()
                    }
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Smartphone, null,
                                tint = Color.White, modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (hostState.running) "正在共享屏幕" else "共享本机屏幕",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (hostState.running)
                                    "${hostState.viewers.size} 台设备在观看 · ${hostState.width}×${hostState.height}"
                                else "把这台手机的屏幕投到局域网内的另一台设备",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                        }
                        if (hostState.running) PulseDot(Color.White)
                    }
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .clickable {
                                if (hostState.running) onOpenHost() else requestCapture()
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (hostState.running) Icons.Filled.Cast else Icons.Filled.PlayArrow,
                                null, tint = LumoVioletDeep, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (hostState.running) "查看连接信息" else "开始共享",
                                style = MaterialTheme.typography.labelLarge,
                                color = LumoVioletDeep,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- 附近设备
        item {
            Spacer(Modifier.height(2.dp))
            SectionHeader(
                "附近的屏幕",
                trailing = when {
                    devices.isEmpty() -> "搜索中…"
                    devices.size == 1 -> "1 台可用"
                    else -> "${devices.size} 台可用"
                }
            )
        }

        if (devices.isEmpty()) {
            item {
                GlassCard {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = "正在搜索同一 Wi-Fi 下的设备",
                            subtitle = "让对方打开 LumoCast 并点「共享本机屏幕」，或直接扫描其二维码。\n搜不到？检查是否连的同一个 Wi-Fi、路由器是否开了「AP 隔离」。"
                        )
                    }
                }
            }
        } else {
            items(devices, key = { it.key }) { device ->
                DeviceRow(device) {
                    if (device.pinRequired) {
                        pendingDevice = device
                        pinError = null
                    } else {
                        onOpenViewer(device.ip, device.port, null, device.name)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(26.dp)) }
    }

    pendingDevice?.let { device ->
        PinDialog(
            hostName = device.name,
            error = pinError,
            onDismiss = { pendingDevice = null },
            onConfirm = { pin ->
                pendingDevice = null
                prefs.lastHost = "${device.ip}:${device.port}"
                onOpenViewer(device.ip, device.port, pin, device.name)
            }
        )
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Lumo.CardBg)
            .border(1.dp, Lumo.CardBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(LumoViolet.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Smartphone, null, tint = LumoViolet, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Wifi, null,
                    tint = Lumo.Subtle, modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${device.ip} · ${device.width}×${device.height} · ${device.fps}fps",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lumo.Subtle
                )
            }
        }
        if (device.pinRequired) {
            Icon(Icons.Filled.Lock, "需要 PIN", tint = Lumo.Subtle, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Pill(if (device.free) "FREE" else "PRO", color = if (device.free) Lumo.Subtle else LumoMint)
    }
}
