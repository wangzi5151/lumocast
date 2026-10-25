# 架构与数据流

## 1. 分层

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层     Compose(M3) / SwiftUI / Electron+Canvas           │  ← 只认识 State
├──────────────────────────────────────────────────────────────┤
│  会话层    HostSession / ViewerSession                       │  ← 状态机、重连、统计
├──────────────────────────────────────────────────────────────┤
│  编解码    MediaCodec(硬编/硬解) / VideoToolbox / WebCodecs   │  ← 只管裸 H.264
├──────────────────────────────────────────────────────────────┤
│  传输层    Protocol(Framing) + TcpLink + UdpDiscovery        │  ← 字节到了就往上抛
└──────────────────────────────────────────────────────────────┘
```

**铁律：下层不认识上层。** 编解码层不知道有「房间」；传输层不知道什么是「帧率」。
这样 `web/js/protocol.js` 才能和 Kotlin 的 `Protocol.kt` 逐字节对齐（`scripts/protocol-test.mjs` 负责验证）。

## 2. 发送端（Android）数据流

```
Surface (MediaProjection)
      │  每帧由系统合成器推入
      ▼
MediaCodec encoder (video/avc, surface input, CBR)
      │  drain：拿到 ByteBuffer + presentationTimeUs + BUFFER_FLAG_KEY_FRAME
      ▼
AnnexB.split()  ──►  取 SPS/PPS（首次 / 旋转时）→ 发 CONFIG 帧
      │
      ▼
FrameChannel.write(type=VIDEO, flags=KEY?, pts, payload)
      │  写线程 + 单槽背压（新帧顶掉旧帧，绝不阻塞采集）
      ▼
TCP socket ──► 局域网
```

关键取舍：

- **输入 surface 而不是 YUV buffer**：零拷贝，功耗低 30%，代价是必须用 `MediaProjection`。
- **背压策略是「丢旧帧」而不是「阻塞」**：阻塞会让系统合成器卡住整台手机。宁可掉帧也不能卡 UI。
- **`PARAMETER_KEY_REQUEST_SYNC_FRAME`**：接收端说「我花屏了」就立刻插 IDR，不重建编码器。
- **码率只调不重建**：`setParameters` 改 `BITRATE`，避免每秒一次关键帧。

## 3. 接收端数据流

```
TCP socket
   │  读线程：16 字节头 → payload
   ▼
Protocol.readPacket()
   │
   ├── CONFIG → 抽出 SPS/PPS → 建 decoder（或重建）
   ├── VIDEO  → 时间戳换算 + 送进解码器
   └── PING   → 立刻回 PONG（同一写队列，保证顺序）
   ▼
解码器  ──► Surface（Android / Swift AVSampleBufferDisplayLayer）
        └─► VideoFrame → canvas.drawImage（WebCodecs）
   ▼
UI：宽高比适配、旋转、镜像、HUD（延迟/码率/FPS）
```

**渲染原则：解码与渲染都在同一时钟源上。** 接收端不做「缓冲 N 帧再播」，而是
`socket 到达 → 解码 → 立刻呈现`，靠主机端 CBR + 局域网带宽保证不堆积；一旦测得队列 > 200ms
就丢到最近的 IDR 重新同步（`SeekToKeyframe`），这条路径比加缓冲更稳。

## 4. 各平台实现差异

| | Android 接收 | iOS 接收 | 桌面接收 |
| --- | --- | --- | --- |
| TCP | `java.net.Socket` 线程 | `Network.framework` (`NWConnection`) | Node `net` (主进程) |
| 解码 | `MediaCodec` → `SurfaceView` | `VideoToolbox` → `AVSampleBufferDisplayLayer` | 渲染进程 `WebCodecs` → `OffscreenCanvas` |
| 为什么这样 | 零拷贝直上屏 | 唯一能拿硬件 H.264 且支持裸流的系统 API | Chromium 自带硬解，跨三平台一套代码 |
| 权限 | 仅网络 | 本地网络（iOS 14+ 需 `NSLocalNetworkUsageDescription`） | 无 |

> 桌面的 TCP 放在 **Electron 主进程**：渲染进程是浏览器环境，拿不到裸 TCP；
> 主进程用 `MessagePort` 把 `ArrayBuffer` 零拷贝传给渲染进程。
> 这么做还顺带解决了 WebCodecs 的 secure-context 限制（页面走 `app://` 特权协议）。

## 5. 线程模型（Android）

| 线程 | 职责 | 禁止 |
| --- | --- | --- |
| Main | Compose、通知 | 任何 IO |
| `EncoderLooper` | `MediaCodec` drain loop（固定 8ms 轮询） | 加锁等网络 |
| `SendThread` | 单写者，独占 socket 输出流 | 解码 |
| `AcceptThread` | 接受连接、握手 | 转发视频 |
| `TxThread` ×N | 每观看者一个，从环形队列取帧写 socket | 阻塞采集 |

帧队列：每个观看者一个 **`ArrayBlockingQueue(2)`**，`offer()` 失败即 `poll()` 再 `offer()` —— 这就是「丢旧帧」。

## 6. 失败模式

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 列表里搜不到设备 | 路由器开了 AP 隔离 / 客机网络 | UI 直接提示「可能是路由器隔离，请用二维码连接」并提供手输 IP |
| 连上黑屏 3s | 首帧 IDR 丢了 | 接收端 1.5s 无帧 → 发 `kf`，2 次失败 → 重连 |
| 画面糊 | 码率被自适应压低 | HUD 显示码率；可在设置里固定画质上限 |
| 发热掉帧 | 长时 1080p60 | 温度 > 42℃ 降 30fps 并提示，> 45℃ 建议停止 |
| 息屏断流 | `MediaProjection` 被系统回收 | 前台服务 + `stopWithTask=false`，并在通知里「续播」按钮 |

---

## 延迟预算（实测分解，1080p60，Wi-Fi 6）

| 环节 | 耗时 | 怎么压下去的 |
| --- | --- | --- |
| 屏幕采集 → 编码器输入 | 4–8 ms | `KEY_LATENCY=0`，虚拟显示直接把 Surface 交给编码器 |
| H.264 硬编 | 6–12 ms | 无 B 帧、`max-bframes=0`、CBR、实时优先 `KEY_PRIORITY=0` |
| 打包 + 发送 | 1–3 ms | `TCP_NODELAY`（关 Nagle）+ 头与负载合成一次 `write` |
| 网络传输 | 3–8 ms | 局域网直连，一跳路由 |
| 接收 + 拆包 | 1–2 ms | 零拷贝：直接写进解码器 input buffer |
| H.264 硬解 | 5–10 ms | 解码器 `low-latency=1` + 实时优先，`releaseOutputBuffer(idx, true)` |
| 合成上屏 | 8–16 ms | 一个 vsync |
| **合计** | **28–59 ms**（HUD 显示 60–90ms，含测量误差与 Wi-Fi 重传） | |

三个「一改就毁」的点：

1. **Nagle**：不设 `TCP_NODELAY`，每个小包会被攒 40ms，延迟直接翻倍
2. **B 帧**：编码器要等后续帧才能输出，等于凭空加 1–2 帧延迟
3. **两次 `write`**：头一次、负载一次，会让 TCP 把两个小段分批推，偶发多一个往返

## 音画同步的实现细节

音视频共用主机单调时钟（`SystemClock.elapsedRealtime`）作为 `pts_ms`，但两端的**本地时钟**
与主机时钟不同源，所以要先测偏移（`PING`/`PONG` 12 字节扩展，见 `docs/PROTOCOL.md` §2.4）。

同步的锚点是**音频**，不是视频：

```
audioClock = 「此刻用户耳朵听到的」主机时间
  Android: lastPts - (writtenFrames - AudioTrack.playbackHeadPosition) / sampleRate
  Web:     该段音频 pts ↔ AudioContext 时间线映射
  iOS:     hostNow - (outputLatency + ioBufferDuration + 调度提前量)

每个视频帧：pts < audioClock - 150ms → 丢弃
```

为什么不用「视频等音频」而用「丢视频帧」：解码器排队不会让画面变准，只会让延迟持续累积；
而丢掉已经过时的帧能立刻把延迟拉回基线。150ms 是「口型开始对不上」的心理阈值。

音频包永远**不丢**（队列满时优先丢 delta 视频帧），因为音频缺口听起来是爆音/卡顿，
比画面少一帧难受得多。
