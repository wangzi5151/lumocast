# LumoCast Wire Protocol v1

Status: **stable** · Applies to Android / iOS / Desktop / any third-party receiver.

设计目标（按优先级）：**握手快**、**实现简单**、**可被 100 行代码的第三方客户端复刻**、**可扩展**。

---

## 0. 端口

| 用途 | 传输 | 端口 |
| --- | --- | --- |
| 设备发现（广播 / 探测） | UDP | `8738` |
| 控制 + 视频数据 | TCP | `8737`（冲突时主机在 `8737..8747` 内顺延，端口由发现报文/二维码告知） |

所有多字节整数均为 **big-endian**。所有 JSON 均为 **UTF-8**。

---

## 1. 发现层（UDP 8738）

主机每 **2000 ms** 向 `255.255.255.255:8738` 以及每个本地网卡的定向广播地址各发一次 `ANNOUNCE`，
接收端只监听即可，无需主动探测。

**ANNOUNCE**（主机 → 广播）

```json
{
  "svc": "lumocast",
  "t": "announce",
  "v": 1,
  "name": "Pixel 8 Pro",
  "port": 8737,
  "pin": true,
  "free": true,
  "w": 1080,
  "h": 2400,
  "fps": 30,
  "cap": ["h264", "touch"],
  "nonce": "9f3a1c"
}
```

| 字段 | 说明 |
| --- | --- |
| `name` | 人类可读设备名，接收端用于列表展示 |
| `pin` | 是否要求 4 位 PIN（已配对设备可免） |
| `free` | 主机是否处于免费模式（接收端据此展示 Pro 提示） |
| `cap` | 能力列表：`h264`、`touch`、`record`、`multi` |
| `nonce` | 每次会话变化；接收端用它去重与判断「主机是否重启过」 |

**PROBE**（接收端 → 广播，可选）

```json
{ "svc": "lumocast", "t": "probe", "v": 1 }
```

主机收到后立即向来源地址**单播**一次 `ANNOUNCE`（用于列表下拉刷新，避免等 2s）。

> 可选扩展：主机同时注册 mDNS 服务 `_lumocast._tcp.local`，TXT 记录与 ANNOUNCE 字段一致。
> v1 客户端不依赖 mDNS。

---

## 2. 控制通道（TCP 8737）

### 2.1 握手

1. 接收端建立 TCP 连接，发送一行 JSON + `\n`（**必须**在 10 s 内，否则主机断开）：

```json
{ "t": "hello", "v": 1, "role": "viewer", "device": "LumoCast iOS",
  "pin": "1234", "pair": "aK9f…", "license": null }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `device` | ✅ | 接收端展示名，主机用于「谁在看」列表 |
| `pin` | 视主机 | 4 位数字；主机 `pin=false` 时忽略 |
| `pair` | 否 | 上次会话拿到的 `pair` token，可免 PIN（有效期 7 天） |
| `license` | 否 | Pro license token，用于向主机申请 60fps / 回控等能力 |

2. 主机回一行 JSON + `\n`：

```json
{ "t": "welcome", "v": 1, "sid": "7b1e0c4a9d2f5583",
  "w": 1080, "h": 2400, "fps": 30, "codec": "avc",
  "free": true, "pin_required": true, "name": "Pixel 8 Pro",
  "pair": "aK9fZm…", "max_fps": 60, "cap": ["h264", "touch"] }
```

失败时：

```json
{ "t": "error", "code": "bad_pin", "msg": "PIN 不正确" }
```

错误码：`bad_pin` · `busy`（观看人数已满）· `unsupported`（协议版本过低）· `denied`（主机点了拒绝）· `expired`。
主机在发送 `error` 后 **立即关闭连接**。

3. 握手成功后，**同一 TCP 连接**全双工复用：主机下行视频，接收端上行控制。双向都用 §2.2 二进制帧。

### 2.2 二进制帧

```
 0        1        2        3        4        5   6   7
+--------+--------+--------+--------+--------+---+---+---+
|  'L'   |  'C'   |   ver  |  type  | flags  |  rsv ×3   |
+--------+--------+--------+--------+--------+---+---+---+
 8                              11  12                      15
+--------------------------------+---------------------------+
|      pts_ms (uint32, BE)       |    payload_len (uint32)   |
+--------------------------------+---------------------------+
|                     payload (payload_len bytes)            |
```

固定头 **16 字节**。`payload` 最大 4 MiB；实现方应拒绝更大的帧并断开（防御性上限）。

`type`：

| 值 | 名称 | 方向 | payload |
| --- | --- | --- | --- |
| `1` | `CONFIG` | 主机 → 接收端 | Annex-B：`SPS` 后接 `PPS`（各带 4 字节起始码 `00 00 00 01`） |
| `2` | `VIDEO` | 主机 → 接收端 | Annex-B：一个或多个 NAL 单元，不带 SPS/PPS |
| `3` | `AUDIO` | 主机 → 接收端 | 裸 AAC 帧（AAC-LC，**不带** ADTS 头；解码参数来自 `AUDIO_CONFIG`） |
| `4` | `PING` | 接收端 → 主机 | 8 字节：`seq(uint32)` + `send_ms(uint32)` |
| `5` | `PONG` | 主机 → 接收端 | 8 或 12 字节，见 §2.4 |
| `6` | `CONTROL` | 双向 | JSON（UTF-8，无换行） |
| `7` | `AUDIO_CONFIG` | 主机 → 接收端 | `AudioSpecificConfig`（AAC-LC 48k 立体声通常为 `11 90`，2 字节） |

`flags`：

| bit | 含义 |
| --- | --- |
| `0x01` | `KEYFRAME`：该 VIDEO 帧为 IDR，解码器可从此处起播 |
| `0x02` | 该帧由接收端 `kf` 请求触发（主机可选置位） |
| `0x04` | `LAST_RETRANSMIT` / 预留 |
| 其余 | 必须为 0，接收端忽略未知位 |

`pts_ms`：主机单调时钟毫秒（`SystemClock.elapsedRealtime`）。视频与音频**共用同一个时钟源**，
因此可以直接互相比较 —— 这是音画同步的前提（见 §2.4）。

接收端默认只把它当相对时间用（不需要对齐绝对时间）。若要显示「端到端延迟」或做 AV 同步，
通过 `PING`/`PONG` 换算出与本机时钟的偏移即可。

### 2.4 音频与音画同步（v1.1 增量）

**为什么音频走同一条 TCP**：分成两条连接时，两条流的内核缓冲、重传时序各自独立，
实测会出现 100–400ms 的漂移，而且随网络抖动变化 —— 这种「声音慢半拍」比画面掉帧难受得多。

**发送顺序约束**：

1. `CONFIG`（视频参数）→ 2. `AUDIO_CONFIG`（音频参数）→ 3. 之后才可以发 `VIDEO` / `AUDIO`。
   接收端收到 `AUDIO_CONFIG` 之前到达的 `AUDIO` 包必须丢弃。

**PONG 的 12 字节扩展**：

```
+--------+--------+--------+
| seq    | send_ms| host_ms|
| uint32 | uint32 | uint32 |   ← host_ms 是新增的（v1.0 只有前 8 字节）
+--------+--------+--------+
```

`host_ms` = 主机收到该 PING 时的 `SystemClock.elapsedRealtime()`。接收端这样算偏移：

```
rtt      = localRecvMs - send_ms
offset   = host_ms + rtt/2 - localRecvMs      // hostClock ≈ localClock + offset
```

`offset` 建议做指数平滑（α≈0.15），并且**只用它做同步，不要用它改 pts**。

**同步模型：音频做主时钟（audio-master）**

```
接收端每秒：
   audioClock = 「此刻正在听到的音频对应的主机时间」
   对每个视频帧：
      if (!keyframe && pts_ms - audioClock < -150)  →  丢弃（已经过时，显示出来只会更晚）
      else                                          →  送解码器
```

- `audioClock` 在 Android 上是 `lastPts - (已写入帧数 - AudioTrack.playbackHeadPosition) / 采样率`
- 在 Web 上是「AudioContext 时间 → 该段音频 pts」的映射，更精确
- 一旦开始播放音频，**永远让视频追音频**；人耳对音频断续的敏感度（>30ms 即可察觉）远高于
  对掉一帧的敏感度

**不支持音频的平台**（Android < 10、用户拒绝 `RECORD_AUDIO`）：主机只发视频，
不设 `AUDIO_CONFIG`，接收端 `audioClock = null` 时不做任何丢帧判断 —— 保证首帧最快。

### 2.3 CONTROL 消息

接收端 → 主机：

| `t` | 附加字段 | 说明 |
| --- | --- | --- |
| `kf` | — | 立刻产生 IDR（接收端检测到花屏/首帧时发） |
| `q` | `br`(bps) `fps` | 请求码率/帧率（主机可拒绝，Pro 才有权申请 60fps） |
| `in` | `e`:`down`/`move`/`up`/`cancel`, `x`,`y` ∈[0,1] | 触摸回控（需 `cap` 含 `touch` 且 license 有效） |
| `key` | `code`,`down` | 物理键回控：`back` / `home` / `recents` |
| `stat` | `recv`,`drop`,`rtt`,`dec_ms` | 每秒一次，主机据此做码率自适应 |
| `bye` | — | 主动断开，主机释放会话 |

主机 → 接收端：

| `t` | 附加字段 | 说明 |
| --- | --- | --- |
| `stat` | `br`,`fps`,`enc_ms`,`batt`,`therm` | 每秒一次，接收端 UI 显示 |
| `rotate` | `w`,`h`,`rot` | 设备旋转，需重建解码器（接收端应丢弃旧 CSD 并等新 CONFIG） |
| `end` | `reason` | `user_stop` / `timeout` / `thermal` / `battery` |

---

## 3. 时序：一次完整会话

```
receiver                                     host
   │  UDP listen :8738                          │
   │ ◀──── ANNOUNCE (2s) ─────────────────────  │
   │                                            │
   │  TCP connect :8737                         │
   │ ──── "hello\n" ──────────────────────────▶ │
   │ ◀─────────────── "welcome\n" ────────────  │
   │                                            │
   │ ◀──── CONFIG (SPS+PPS) ──────────────────  │
   │ ◀──── VIDEO (IDR, flags=KEYFRAME) ───────  │
   │ ◀──── VIDEO (P) ─────────────────────────  │
   │ ──── PING / stat ───────────────────────▶  │
   │ ◀──────────── PONG / stat ───────────────  │
   │ ──── CONTROL {"t":"kf"} ────────────────▶  │  （仅在需要时）
   │ ◀──── VIDEO (IDR) ───────────────────────  │
```

首帧预算：`TCP connect ≤ 30ms` + `hello/welcome ≤ 20ms` + `CONFIG+IDR ≤ 150ms`。
主机会在握手成功后**立刻**请求一个 IDR，不等编码器下一个 GOP。

---

## 4. 码率自适应（建议实现）

主机每秒拿到接收端的 `stat.rtt` 与 `stat.drop`（丢帧数/秒）后：

```
if rtt > 250ms or drop > 3:   target_br *= 0.8      # 降到 2 Mbps 为止
elif rtt < 120ms and drop == 0: target_br *= 1.08   # 升到 12 Mbps 或设备上限为止
apply every 2s, hysteresis: only change if |Δ| > 15%
```

编码器侧：CBR + `I-frame interval = 2s`；码率变化时**不**重建编码器（用 `MediaCodec.setParameters`），
避免关键帧风暴。分辨率降档（1080p → 720p → 540p）需要重建，仅在 `< 2 Mbps` 仍不达标时触发。

---

## 5. 安全模型

| 威胁 | 缓解 |
| --- | --- |
| 同 Wi-Fi 的陌生人偷看 | 4 位 PIN（60s 内最多 5 次尝试，之后 5 分钟冷却）+ 主机端「新的观看请求」提示 |
| 中间人窃听 | v1 为明文 TCP，**安全边界 = 你信任你家/公司 Wi-Fi**。v2 计划：X25519 + ChaCha20-Poly1305（`cap` 里加 `e2ee`），PIN 参与 PSK |
| PIN 暴力破解 | 指数退避 + 失败记录写进 `Prefs`，主机会在通知栏提示 |
| 伪造 license | 离线公钥验签（见 `docs/PRICING.md`），私钥只在发卡服务 |
| 拒绝服务 | 单机最多 8 个 TCP 连接，超过即拒绝；单帧上限 4 MiB |

> **对外宣传口径必须诚实**：LumoCast v1 是「局域网内明文传输 + PIN 防误连」，
> 不是端到端加密产品。E2EE 在 v2 路线图上（`docs/ROADMAP.md`）。

---

## 6. 版本兼容

- `v` 字段为**主版本**。接收端 `v` 小于主机要求时，主机回 `error/unsupported` 并在文案里给出下载链接。
- 未知 `type` / 未知 `flags` 位 / 未知 CONTROL `t` → **必须忽略**，不得断开。
- `PONG` 长度 8 → 老主机，不做 AV 同步（仅测 RTT）；长度 12 → v1.1，启用时钟偏移。
- 收到 `AUDIO` / `AUDIO_CONFIG` 但实现不支持音频 → 静默丢弃，继续放视频。
- 新增能力一律走 `cap` 数组协商，不做隐式假设。

## 7. 参考实现

| 语言 | 文件 | 行数（约） |
| --- | --- | --- |
| Kotlin（主机） | `android/.../core/net/HostServer.kt` | 260 |
| Kotlin（接收） | `android/.../core/net/ViewerClient.kt` | 180 |
| JS（接收） | `web/js/protocol.js` + `web/js/decoder.js` | 300 |
| Swift（接收） | `ios/LumoCast/StreamClient.swift` | 240 |

`scripts/protocol-test.mjs` 是 Node 下的 round-trip 一致性测试（`npm test`）。
