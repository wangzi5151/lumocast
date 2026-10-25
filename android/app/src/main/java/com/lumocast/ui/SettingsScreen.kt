package com.lumocast.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumocast.data.Prefs
import com.lumocast.ui.components.GlassCard
import com.lumocast.ui.components.GradientButton
import com.lumocast.ui.components.Pill
import com.lumocast.ui.theme.Lumo
import com.lumocast.ui.theme.LumoBlue
import com.lumocast.ui.theme.LumoMint
import com.lumocast.ui.theme.LumoRed
import com.lumocast.ui.theme.LumoViolet

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val uriHandler = LocalUriHandler.current

    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var pinEnabled by remember { mutableStateOf(prefs.pinEnabled) }
    var maxFps by remember { mutableStateOf(prefs.maxFps) }
    var maxShort by remember { mutableStateOf(prefs.maxShortSide) }
    var diagnosticHint by remember { mutableStateOf("连不上时复制这个") }
    val hostState by com.lumocast.data.LiveSession.host.collectAsState()

    fun buildDiagnostic(): String = buildString {
        append("LumoCast 1.1.1  Android ").append(android.os.Build.VERSION.SDK_INT)
        append("  (").append(android.os.Build.MANUFACTURER).append(' ')
        append(android.os.Build.MODEL).append(")\n")
        append("共享: running=").append(hostState.running)
        append("  ").append(hostState.width).append('x').append(hostState.height)
        append("  ").append(hostState.fps).append("fps  ")
        append(hostState.bitrateKbps / 1000).append("Mbps\n")
        append("地址: ").append(hostState.ip ?: "-").append(':').append(hostState.port).append("\n")
        append("观看者: ").append(hostState.viewers.size).append("  ")
        hostState.viewers.forEach { append(it.name).append('/').append(it.ip).append("  ") }
        append("\n错误: ").append(hostState.error ?: "-").append("\n----\n")
        append(com.lumocast.data.LiveSession.lastViewerDiagnostic ?: "（本机没有观看端失败记录）")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("设置", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(10.dp))

        // ---------------------------------------------------------- 主机设置
        GlassCard {
            Text("共享设置", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = deviceName,
                onValueChange = {
                    deviceName = it.take(24)
                    prefs.deviceName = it
                },
                label = { Text("本机名称（对方列表里看到的名字）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            SettingRow("连接需要 PIN", "关闭后同一 Wi-Fi 内任何人可直接观看") {
                Switch(
                    checked = pinEnabled,
                    onCheckedChange = {
                        pinEnabled = it
                        prefs.pinEnabled = it
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingRow("当前 PIN", prefs.pin) {
                Text("重新生成", style = MaterialTheme.typography.labelLarge, color = LumoViolet,
                    modifier = Modifier.clickable {
                        prefs.pin = (1000..9999).random().toString()
                    })
            }

            Spacer(Modifier.height(16.dp))
            Text("最高帧率", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("30 fps", maxFps == 30) {
                    maxFps = 30
                    prefs.maxFps = 30
                }
                SegmentedOption("60 fps", maxFps == 60) {
                    maxFps = 60
                    prefs.maxFps = 60
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("画面质量上限", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("自动", maxShort == 0) {
                    maxShort = 0
                    prefs.maxShortSide = 0
                }
                SegmentedOption("1080p", maxShort == 1080) {
                    maxShort = 1080
                    prefs.maxShortSide = 1080
                }
                SegmentedOption("720p", maxShort == 720) {
                    maxShort = 720
                    prefs.maxShortSide = 720
                }
                SegmentedOption("1440p", maxShort == 1440) {
                    maxShort = 1440
                    prefs.maxShortSide = 1440
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "所有功能全部开放：最高 1440p / 60fps。画质越高越清晰，但发热和耗电也越明显，日常 1080p 足够。",
                style = MaterialTheme.typography.labelSmall,
                color = Lumo.Subtle
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---------------------------------------------------------- 关于
        GlassCard {
            LinkRow("隐私政策") { uriHandler.openUri("https://github.com/wangzi5151/lumocast/blob/main/docs/PRIVACY.md") }
            LinkRow("源码与许可证") { uriHandler.openUri("https://github.com/wangzi5151/lumocast") }
            LinkRow("版本", "1.1.1 (3)") {}
            LinkRow("复制诊断信息", diagnosticHint) {
                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clip.setPrimaryClip(
                    android.content.ClipData.newPlainText("LumoCast 诊断", buildDiagnostic())
                )
                diagnosticHint = "已复制"
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "LumoCast 完全免费，无广告、无内购、无账号。\n不收集屏幕内容，视频只在你的局域网内传输。",
            style = MaterialTheme.typography.labelSmall,
            color = Lumo.Subtle
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Lumo.Subtle)
        }
        trailing()
    }
}

@Composable
private fun SegmentedOption(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) LumoViolet.copy(alpha = 0.20f) else Lumo.CardBg)
            .border(
                1.dp,
                if (selected) LumoViolet else Lumo.CardBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Lumo.Subtle
                selected -> LumoViolet
                else -> Color.Unspecified
            }
        )
    }
}

@Composable
private fun LinkRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelSmall, color = Lumo.Subtle)
        }
    }
}
