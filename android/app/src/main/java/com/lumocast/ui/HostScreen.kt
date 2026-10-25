package com.lumocast.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumocast.data.LiveSession
import com.lumocast.service.HostService
import com.lumocast.ui.components.GlassCard
import com.lumocast.ui.components.PinDisplay
import com.lumocast.ui.components.PulseDot
import com.lumocast.ui.components.SectionHeader
import com.lumocast.ui.components.StatChip
import com.lumocast.ui.theme.Lumo
import com.lumocast.ui.theme.LumoMint
import com.lumocast.ui.theme.LumoRed
import com.lumocast.ui.theme.LumoViolet
import com.lumocast.util.Qr

@Composable
fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by LiveSession.host.collectAsState()

    Column(Modifier.fillMaxSize()) {

        // 内容区：可滚动。之前这里没有 scroll，内容一长「停止共享」被挤出屏幕 —— 这就是找不到结束按钮的原因。
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
            Text("共享中", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (state.running) {
                PulseDot(LumoMint, 9.dp)
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(LumoRed.copy(alpha = 0.16f))
                        .clickable { HostService.stop(context) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Stop, null, tint = LumoRed, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "结束",
                        style = MaterialTheme.typography.labelSmall,
                        color = LumoRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (!state.running) {
            Spacer(Modifier.height(40.dp))
            Text(
                state.error ?: "共享已停止",
                style = MaterialTheme.typography.titleMedium,
                color = if (state.error != null) LumoRed else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "点返回后重新点「共享本机屏幕」即可再次开始",
                style = MaterialTheme.typography.bodyMedium,
                color = Lumo.Subtle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "用另一台设备在 LumoCast 里选中这台手机，或直接扫描下方二维码",
            style = MaterialTheme.typography.bodyMedium,
            color = Lumo.Subtle
        )

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------------- 二维码
        val qrPayload = remember(state.ip, state.port, state.pin, state.pinRequired) {
            Qr.payload(state.ip ?: "0.0.0.0", state.port, if (state.pinRequired) state.pin else null, "LumoCast")
        }
        val qrBitmap = remember(qrPayload) { Qr.bitmap(qrPayload, 640) }

        GlassCard {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qrBitmap != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White)
                                .padding(12.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "连接二维码",
                                modifier = Modifier.size(190.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "扫码即连",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.ip ?: "—"}:${state.port}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Lumo.Subtle
                    )
                }
            }
        }

        // ---------------------------------------------------------------- PIN
        if (state.pinRequired) {
            Spacer(Modifier.height(14.dp))
            GlassCard {
                Text("连接 PIN", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "对方输入这 4 位数字才能观看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lumo.Subtle
                )
                Spacer(Modifier.height(14.dp))
                PinDisplay(state.pin)
            }
        }

        // ---------------------------------------------------------------- 状态
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("分辨率", "${state.width}×${state.height}", Modifier.weight(1f))
            StatChip("帧率", "${state.fps}fps", Modifier.weight(1f))
            StatChip("码率", "${state.bitrateKbps / 1000.0f}M", Modifier.weight(1f))
            StatChip("观看", "${state.viewers.size}", Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("正在观看", trailing = if (state.viewers.isEmpty()) "等待连接" else null)
        Spacer(Modifier.height(8.dp))
        if (state.viewers.isEmpty()) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(LumoViolet)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "等待设备连接…\n保持这个页面或锁屏都不会中断",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lumo.Subtle
                    )
                }
            }
        } else {
            state.viewers.forEach { viewer ->
                GlassCard(Modifier.padding(bottom = 8.dp), corner = 18) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(LumoMint.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                viewer.name.take(1).uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                color = LumoMint
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(viewer.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                viewer.ip,
                                style = MaterialTheme.typography.labelSmall,
                                color = Lumo.Subtle
                            )
                        }
                        Text(
                            if (viewer.rttMs > 0) "${viewer.rttMs}ms" else "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = Lumo.Subtle
                        )
                    }
                }
            }
        }

            Spacer(Modifier.height(26.dp))
        }

        // 常驻底栏：不滚动、不藏在页面底部，任何滚动位置都能点
        if (state.running) {
            StopBar(onStop = { HostService.stop(context) })
        }
    }
}

/** 结束投屏按钮：实心红、白字，56dp 高，手指容易点到。 */
@Composable
private fun StopBar(onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(LumoRed)
                .clickable(onClick = onStop)
                .padding(vertical = 17.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "结束投屏",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
