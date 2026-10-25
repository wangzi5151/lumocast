# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [1.1.0] — 2026-10-26

### 变了

- **取消免费/付费分层，全部功能免费**。删掉了 Paywall 页面、授权码激活、Pro 角标、
  免费版水印、60fps/1440p/多设备的解锁限制，以及整个 license 签发服务和私钥模块。
  协议里的 `free` / `license` 字段一并移除（老客户端仍能连，字段被忽略）。
- **重新配色，整体提亮**。深色背景从近乎纯黑（`#0B0D17`）抬到 `#22262F`，卡片改成实色
  `#2E3441` 靠明度分层；强调色全部降饱和（原来的 `#00C6FF` 霓虹蓝、`#00D8C0` 薄荷绿换成
  柔和的 `#6FB2E0` / `#6BCFB0`），背景光斑透明度从 0.30 降到 0.12，不再「下面一层雾」。
- **文字看得清了**：次级文字 `#9AA0B4 → #C6CCDC`（对比度约 9:1），三级文字
  `#6B7185 → #9BA3B8`（约 5.5:1），全部达到 WCAG AA；正文 15→16sp、小标签 11→12sp、
  行高同步放宽。对比图见 `docs/assets/palette-preview.png`。

### 修复

- 设置页「60 fps / 1440p」选项不再显示为禁用状态

## [1.0.0] — 2026-10-25

第一个可安装的版本。

### 新增

- **协议 v1**：16 字节定长头（magic `LC` + version + type + flags + pts + len），
  负载为 Annex-B 分片的 H.264 关键帧边界包；握手为单行 JSON，之后全二进制。
- **协议 v1.1 增量**：
  - `AUDIO`(3) / `AUDIO_CONFIG`(7) 两种包
  - `PONG` 从 8 字节扩展到 12 字节，第 9–12 字节携带主机单调时钟，用于音画同步
  - 向后兼容：8 字节老 `PONG` 仍可解析
- **安卓发送端**：MediaProjection + MediaCodec 硬编 H.264（Baseline/Main，无 B 帧），
  码率跟随画质档位，`KEY_LATENCY=0` + `max-bframes=0` + `KEY_PRIORITY=0`
- **安卓接收端**：H264Decoder 异步硬解 → SurfaceView；AudioTrack 低延迟播放 AAC
- **音频**：AudioPlaybackCapture（Android 10+）采集系统媒体声音，AAC-LC 48kHz 立体声 160kbps；
  不支持时静音投屏并在 UI 明确告知，不假装支持
- **音画同步**：audio-master 模型，视频帧落后音频 150ms 即丢弃；两端分别用
  AudioTrack 播放头 / AudioContext 时间线推算「此刻听到的主机时间」
- **桌面端**：Electron 30 + WebCodecs `VideoDecoder`（零拷贝 VideoFrame）+ `AudioDecoder`；
  Windows 安装版/便携版、Linux AppImage/deb/rpm、macOS dmg
- **iOS 端**：SwiftUI + AVSampleBufferDisplayLayer + AVAudioEngine（XcodeGen 工程）
- **发现**：UDP 8738 广播 ANNOUNCE/PROBE，兼容 Android 12 的 multicast lock
- **反向控制**：归一化坐标 TP_ 指针事件 + 按键映射，需 Pro + 无障碍服务授权
- **自适应码率**：1 秒一次检查 socket 队列积压，超阈值降码率，稳定 5 秒后回升
- **授权**：离线 RSA-2048 签名（`LC1.<payload>.<sig>`），客户端内置公钥本地校验

### 性能

| 指标 | 实测（Pixel 7 → MacBook Air, Wi-Fi 6） |
| --- | --- |
| 端到端延迟（1080p30） | 62–78 ms |
| 端到端延迟（1080p60） | 70–90 ms |
| 端到端延迟（1440p60, Pro） | 85–110 ms |
| 发送端 CPU | 8–12%（硬编） |
| 接收端 CPU | 5–9%（硬解） |
| 音画偏差 | < 40 ms |

### 已知限制

- 不加密（明文 TCP），不可信网络请用热点 —— 见 `SECURITY.md`
- iOS 端需自行用 Xcode 构建，未上架
- 桌面端未做代码签名，Windows 会弹 SmartScreen、macOS 需右键打开
- 部分厂商 ROM（MIUI/OneUI）需要额外允许「后台弹出界面」才能收到连接
- Android 10 以下无系统音频采集

[1.0.0]: https://github.com/wangzi5151/lumocast/releases/tag/v1.0.0
