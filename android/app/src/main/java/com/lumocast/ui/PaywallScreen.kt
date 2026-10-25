package com.lumocast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumocast.ui.components.GlassCard
import com.lumocast.ui.components.GradientButton
import com.lumocast.ui.components.Pill
import com.lumocast.ui.theme.Lumo
import com.lumocast.ui.theme.LumoBlue
import com.lumocast.ui.theme.LumoMint
import com.lumocast.ui.theme.LumoViolet

/**
 * 付费页。刻意不做「限时倒计时」这类暗黑手法：
 * 一句话说清楚免费够用、Pro 解决什么，价格摆出来，然后让用户自己决定。
 */
@Composable
fun PaywallScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
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
            Spacer(Modifier.weight(1f))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(LumoViolet, LumoBlue)))
                .padding(22.dp)
        ) {
            Column {
                Pill("LUMOCAST PRO", color = Color.White)
                Spacer(Modifier.height(14.dp))
                Text(
                    "把「能用」变成「好用」",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "免费版已经能完整投屏。Pro 是为长时间、专业场景准备的：\n1080p60、触摸回控、录制、无水印、5 台设备同时观看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        PriceCard(
            title = "Pro 年付",
            price = "¥48",
            unit = "/ 年",
            highlight = true,
            bullets = listOf("全部 Pro 能力", "3 台设备激活", "随时取消")
        ) {
            // 实际接入：打开 Stripe Checkout
            onOpenSettings()
        }

        Spacer(Modifier.height(12.dp))

        PriceCard(
            title = "Pro 买断",
            price = "¥128",
            unit = "一次性",
            highlight = false,
            bullets = listOf("永久使用，不续费", "3 台设备激活", "含一年更新")
        ) {
            onOpenSettings()
        }

        Spacer(Modifier.height(18.dp))

        GlassCard {
            Text("免费版包括什么", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            listOf(
                "1080p / 30fps 局域网投屏",
                "单次最长 60 分钟",
                "1 台设备同时观看",
                "PIN 与二维码连接",
                "无账号、无广告、无数据采集"
            ).forEach { line -> FeatureRow(line, LumoMint) }

            Spacer(Modifier.height(14.dp))
            Text("Pro 额外提供", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            listOf(
                "1080p / 60fps，无时长限制",
                "触摸回控：在接收端直接操作手机",
                "录制 MP4 / 一键截图",
                "5 台设备同时观看，无角标水印",
                "商用授权（门店、教学、展台）"
            ).forEach { line -> FeatureRow(line, LumoViolet) }
        }

        Spacer(Modifier.height(18.dp))

        GradientButton("我已经购买，去激活", icon = Icons.Filled.Check, colors = Lumo.GradientMint) {
            onOpenSettings()
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "购买后你会收到一个离线 license（LC1.…），粘贴到设置页即可激活，\n不需要联网校验、不需要注册账号。14 天无理由退款。",
            style = MaterialTheme.typography.labelSmall,
            color = Lumo.Subtle
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PriceCard(
    title: String,
    price: String,
    unit: String,
    highlight: Boolean,
    bullets: List<String>,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (highlight) LumoViolet.copy(alpha = 0.14f) else Lumo.CardBg)
            .border(
                1.dp,
                if (highlight) LumoViolet else Lumo.CardBorder,
                RoundedCornerShape(22.dp)
            )
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (highlight) Pill("最划算", color = LumoMint)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    price,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (highlight) LumoViolet else MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(6.dp))
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = Lumo.Subtle)
            }
            Spacer(Modifier.height(10.dp))
            bullets.forEach { b ->
                Row(
                    Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Check, null,
                        tint = LumoMint, modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(b, style = MaterialTheme.typography.bodyMedium, color = Lumo.Subtle)
                }
            }
            Spacer(Modifier.height(14.dp))
            GradientButton(
                text = "选择这一档",
                colors = if (highlight) Lumo.Gradient else listOf(Color(0xFF2C3142), Color(0xFF3A4157)),
                onClick = onClick
            )
        }
    }
}

@Composable
private fun FeatureRow(text: String, color: Color) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(color, RoundedCornerShape(50))
        )
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
