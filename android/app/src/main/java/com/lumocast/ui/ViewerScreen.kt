package com.lumocast.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumocast.ui.components.GradientButton
import com.lumocast.ui.components.QualityDot
import com.lumocast.ui.theme.Lumo
import com.lumocast.ui.theme.LumoMint
import com.lumocast.ui.theme.LumoRed
import com.lumocast.ui.theme.LumoViolet

/** 观看页：全屏视频 + 极简 HUD，点一下屏幕才出现控制条（不打扰观看）。 */
@Composable
fun ViewerScreen(
    ip: String,
    port: Int,
    pin: String?,
    name: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var touchEnabled by remember { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }

    val controller = remember(ip, port, pin, attempt) {
        ViewerController(ip, port, pin, "LumoCast Android")
    }
    val state = controller.state

    DisposableEffect(controller) {
        controller.start()
        onDispose {
            controller.stop()
        }
    }

    val ratio = if (state.height > 0) state.width.toFloat() / state.height.toFloat() else 9f / 16f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ------------------------------------------------------------ 视频
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .pointerInput(touchEnabled) {
                        if (!touchEnabled) return@pointerInput
                        detectDragGestures(
                            onDragStart = { o ->
                                controller.sendTouch("down", o.x / size.width, o.y / size.height)
                            },
                            onDrag = { change, _ ->
                                controller.sendTouch(
                                    "move",
                                    change.position.x / size.width,
                                    change.position.y / size.height
                                )
                            },
                            onDragEnd = { controller.sendTouch("up", 0.5f, 0.5f) },
                            onDragCancel = { controller.sendTouch("cancel", 0.5f, 0.5f) }
                        )
                    }
                    .pointerInput(touchEnabled) {
                        detectTapGestures(
                            onTap = { o ->
                                if (touchEnabled) {
                                    controller.sendTouch("down", o.x / size.width, o.y / size.height)
                                    controller.sendTouch("up", o.x / size.width, o.y / size.height)
                                } else {
                                    hudVisible = !hudVisible
                                }
                            }
                        )
                    },
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                controller.attachSurface(holder.surface)
                            }

                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                controller.detachSurface()
                            }
                        })
                    }
                }
            )

            // 连接中 / 失败
            if (state.phase == ViewerUiState.Phase.CONNECTING) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LumoViolet, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (state.reconnecting > 0) "连接中断，正在重连（第 ${state.reconnecting} 次）…"
                        else "正在连接 $name…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                    if (state.reconnecting > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "正在自动重试，不用手动点",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                }
            }

            if (state.phase == ViewerUiState.Phase.ERROR) {
                Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        state.error ?: "连接已断开",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$ip:$port",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(22.dp))
                    GradientButton(
                        text = "重新连接",
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.width(200.dp)
                    ) { attempt++ }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .width(200.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable { onBack() }
                            .padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "结束投屏",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ HUD
        if (hudVisible) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.hostName.ifBlank { name },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QualityDot(state.rttMs)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.phase == ViewerUiState.Phase.LIVE)
                                buildString {
                                    append("${state.fps.toInt()} fps · ${"%.1f".format(state.mbps)} Mbps")
                                    if (state.latencyMs > 0) append(" · ${state.latencyMs}ms 延迟")
                                    append(if (state.audio) " · 有声" else " · 静音")
                                }
                            else "连接中",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                }
                if (state.canTouch) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (touchEnabled) LumoViolet else Color.White.copy(alpha = 0.14f)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.TouchApp, "回控",
                                tint = Color.White, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (touchEnabled) "回控中" else "回控",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.Switch(
                        checked = touchEnabled,
                        onCheckedChange = { touchEnabled = it },
                        modifier = Modifier.size(width = 42.dp, height = 26.dp)
                    )
                }

                // 结束投屏：一直显示在控制条最右边，不用去找返回箭头
                Row(
                    Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(LumoRed.copy(alpha = 0.92f))
                        .clickable { onBack() }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Stop, null,
                        tint = Color.White, modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "结束",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

        }
    }
}
