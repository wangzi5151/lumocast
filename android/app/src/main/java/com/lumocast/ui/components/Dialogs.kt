package com.lumocast.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumocast.ui.theme.Lumo
import com.lumocast.ui.theme.LumoMint
import com.lumocast.ui.theme.LumoRed
import com.lumocast.ui.theme.LumoViolet

/** 4 位 PIN 输入弹窗：自动聚焦、满 4 位自动提交、错误抖动提示。 */
@Composable
fun PinDialog(
    hostName: String,
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入连接 PIN") },
        text = {
            Column {
                Text(
                    "在「$hostName」的屏幕上可以看到 4 位数字",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lumo.Subtle
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v ->
                        pin = v.filter { it.isDigit() }.take(4)
                        if (pin.length == 4) onConfirm(pin)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        letterSpacing = 12.sp,
                        textAlign = TextAlign.Center
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (pin.length == 4) onConfirm(pin)
                    })
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = LumoRed, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pin.length == 4) onConfirm(pin) }) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 大号等宽 PIN 显示（主机端展示给观看者看）。 */
@Composable
fun PinDisplay(pin: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pin.forEach { d ->
            Box(
                Modifier
                    .size(48.dp)
                    .background(Lumo.CardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, Lumo.CardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    d.toString(),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

/** 顶部小标签：Free / Pro。 */
@Composable
fun PlanBadge(isPro: Boolean, modifier: Modifier = Modifier) {
    val color = if (isPro) LumoMint else Lumo.Subtle
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isPro) androidx.compose.material.icons.Icons.Filled.Star
            else androidx.compose.material.icons.Icons.Filled.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (isPro) "PRO" else "FREE",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 网络状态点（绿=好，黄=一般，红=差）。 */
@Composable
fun QualityDot(rttMs: Int, modifier: Modifier = Modifier) {
    val color = when {
        rttMs <= 0 -> Lumo.Subtle
        rttMs < 100 -> LumoMint
        rttMs < 250 -> Color(0xFFFFC062)
        else -> LumoRed
    }
    Box(
        modifier
            .size(8.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape)
    )
}

/** Pro 引导条：不打扰、可关闭，点击进付费页。 */
@Composable
fun ProBanner(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LumoViolet.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .border(1.dp, LumoViolet.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .then(Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(androidx.compose.material.icons.Icons.Filled.Star, null, tint = LumoViolet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text("升级", style = MaterialTheme.typography.labelLarge) }
    }
}
