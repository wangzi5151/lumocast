package com.lumocast.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 配色原则：
 * 1. 深色底「抬起」到 #22262F，不再用接近纯黑（#0B0D17）压白字 —— 纯黑底上小字会发灰、发虚
 * 2. 强调色全部降饱和：原来的 #00C6FF/#00D8C0 是霓虹色，长看刺眼，也容易让文字边缘发彩
 * 3. 次级文字 #C6CCDC（对比度 ≈ 9:1），三级文字 #9BA3B8（≈ 5.5:1），都过 WCAG AA
 */

// ---- 强调色（柔和版）
val LumoViolet = Color(0xFF8E96F5)   // 主色：淡靛紫
val LumoIndigo = Color(0xFF6672DF)   // 按下 / 深一档
val LumoBlue   = Color(0xFF6FB2E0)   // 辅助蓝
val LumoMint   = Color(0xFF6BCFB0)   // 成功 / 在线
val LumoAmber  = Color(0xFFE8BC7B)   // 一般 / 提醒
val LumoPink   = Color(0xFFE79AB4)
val LumoRed    = Color(0xFFE58081)   // 错误（降饱和，不刺眼）

// 上面几个是「深色背景上当前景色」用的浅色；下面两个是**背景色**，配白字用，
// 否则浅紫底 + 白字只有 2.7:1，等于看不清。
val LumoVioletDeep = Color(0xFF5B63D6)   // 白字 5.0:1
val LumoBlueDeep   = Color(0xFF3776AE)   // 白字 4.8:1
val LumoMintDeep   = Color(0xFF1B7A67)   // 白字 ≈4.6:1

// ---- 深色表面（整体抬高，卡片靠明度区分而不是靠纯黑压深）
val Ink950 = Color(0xFF1C2029)
val Ink900 = Color(0xFF22262F)
val Ink800 = Color(0xFF2A2F3A)
val Ink700 = Color(0xFF343A47)
val Ink600 = Color(0xFF414857)

// ---- 文字
val TextPrimary   = Color(0xFFF4F6FB)
val TextSecondary = Color(0xFFC6CCDC)
val TextTertiary  = Color(0xFF9BA3B8)

// ---- 浅色主题
val LightBg      = Color(0xFFF4F6FB)
val LightSurface = Color(0xFFFFFFFF)
val LightText    = Color(0xFF1B1F2A)
