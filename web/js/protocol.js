/**
 * LumoCast wire protocol v1 —— 浏览器 / Electron 侧实现。
 * 与 android/.../core/net/Protocol.kt 逐字节对齐（docs/PROTOCOL.md）。
 *
 * 这个文件**没有任何依赖**，可以直接在 Node 里跑测试（scripts/protocol-test.mjs）。
 */

export const MAGIC_0 = 0x4c;
export const MAGIC_1 = 0x43;
export const VERSION = 1;
export const HEADER_SIZE = 16;
export const MAX_PAYLOAD = 4 * 1024 * 1024;

export const T = {
  CONFIG: 1,
  VIDEO: 2,
  AUDIO: 3,
  PING: 4,
  PONG: 5,
  CONTROL: 6,
  /** v1.1：AudioSpecificConfig（2 字节常见），必须先于任何 AUDIO 帧到达 */
  AUDIO_CONFIG: 7,
};

/** 音频默认参数：AAC-LC 48k 立体声，20ms 一组帧 —— 与 Android 端 AudioCapture 一致。 */
export const AUDIO = {
  SAMPLE_RATE: 48000,
  CHANNELS: 2,
  CODEC: 'mp4a.40.2',
  FRAME_MS: 20,
};

export const F = {
  KEYFRAME: 0x01,
  KEYFRAME_REQUESTED: 0x02,
};

export const PORT_CONTROL = 8737;
export const PORT_DISCOVERY = 8738;

/** 大端写 32 位无符号。 */
function putU32(view, offset, value) {
  view[offset] = (value >>> 24) & 0xff;
  view[offset + 1] = (value >>> 16) & 0xff;
  view[offset + 2] = (value >>> 8) & 0xff;
  view[offset + 3] = value & 0xff;
}

function getU32(bytes, offset) {
  return (
    ((bytes[offset] << 24) >>> 0) +
    (bytes[offset + 1] << 16) +
    (bytes[offset + 2] << 8) +
    bytes[offset + 3]
  );
}

/** 生成 16 字节包头。 */
export function encodeHeader(type, flags, ptsMs, payloadLen) {
  const out = new Uint8Array(HEADER_SIZE);
  out[0] = MAGIC_0;
  out[1] = MAGIC_1;
  out[2] = VERSION;
  out[3] = type;
  out[4] = flags;
  // out[5..7] 保留
  putU32(out, 8, ptsMs >>> 0);
  putU32(out, 12, payloadLen);
  return out;
}

/** 拼一个完整包（头 + 负载）。 */
export function encodePacket(type, flags, ptsMs, payload) {
  const body = payload || new Uint8Array(0);
  const out = new Uint8Array(HEADER_SIZE + body.length);
  out.set(encodeHeader(type, flags, ptsMs, body.length), 0);
  out.set(body, HEADER_SIZE);
  return out;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function encodeControl(obj) {
  return encodePacket(T.CONTROL, 0, 0, encoder.encode(JSON.stringify(obj)));
}

export function encodePing(seq) {
  const p = new Uint8Array(8);
  putU32(p, 0, seq);
  putU32(p, 4, Date.now() >>> 0);
  return encodePacket(T.PING, 0, 0, p);
}

export function encodePongFrom(pingPayload) {
  const p = new Uint8Array(8);
  p.set(pingPayload.subarray(0, 8));
  return encodePacket(T.PONG, 0, 0, p);
}

/**
 * 流式拆包器：TCP 不保证边界，把任意大小的 chunk 喂进来即可。
 * ```
 * const parser = new PacketParser();
 * for (const packet of parser.push(chunk)) { ... }
 * ```
 */
export class PacketParser {
  constructor() {
    this.buffer = new Uint8Array(0);
  }

  push(chunk) {
    const merged = new Uint8Array(this.buffer.length + chunk.length);
    merged.set(this.buffer, 0);
    merged.set(chunk, this.buffer.length);
    this.buffer = merged;

    const packets = [];
    let offset = 0;
    while (this.buffer.length - offset >= HEADER_SIZE) {
      const b = this.buffer;
      if (b[offset] !== MAGIC_0 || b[offset + 1] !== MAGIC_1) {
        throw new Error('bad magic — 对端可能不是 LumoCast，或流已错位');
      }
      const version = b[offset + 2];
      if (version !== VERSION) throw new Error(`unsupported protocol version ${version}`);

      const type = b[offset + 3];
      const flags = b[offset + 4];
      const ptsMs = getU32(b, offset + 8);
      const len = getU32(b, offset + 12);
      if (len < 0 || len > MAX_PAYLOAD) throw new Error(`payload too large: ${len}`);
      if (b.length - offset - HEADER_SIZE < len) break; // 还没收全，等下一次

      const payload = b.subarray(offset + HEADER_SIZE, offset + HEADER_SIZE + len);
      packets.push({ type, flags, ptsMs, payload: payload.slice() });
      offset += HEADER_SIZE + len;
    }
    this.buffer = this.buffer.subarray(offset).slice();
    return packets;
  }
}

/** 拆分 Annex-B 数据为 NAL 单元（不含起始码）。 */
export function splitAnnexB(data) {
  const nals = [];
  let i = 0;
  let start = -1;
  const end = data.length;
  while (i + 2 < end) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      if (start >= 0) pushNal(nals, data, start, i);
      start = i + 3;
      i += 3;
    } else if (i + 3 < end && data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 0 && data[i + 3] === 1) {
      if (start >= 0) pushNal(nals, data, start, i);
      start = i + 4;
      i += 4;
    } else {
      i++;
    }
  }
  if (start >= 0 && start < end) pushNal(nals, data, start, end);
  return nals;
}

function pushNal(list, data, s, e) {
  let end = e;
  while (end > s && data[end - 1] === 0) end--;
  if (end > s) list.push(data.subarray(s, end));
}

export function nalType(nal) {
  return nal.length ? nal[0] & 0x1f : 0;
}

/** 从 CONFIG 负载里找出 SPS / PPS。 */
export function extractSpsPps(csd) {
  let sps = null;
  let pps = null;
  for (const nal of splitAnnexB(csd)) {
    const type = nalType(nal);
    if (type === 7 && !sps) sps = nal;
    if (type === 8 && !pps) pps = nal;
  }
  return sps && pps ? { sps, pps } : null;
}

/** 生成 AVCDecoderConfigurationRecord（VideoDecoder 的 description 必须用它）。 */
export function buildAvcC(sps, pps) {
  const out = new Uint8Array(11 + sps.length + pps.length);
  out[0] = 1; // configurationVersion
  out[1] = sps[1]; // AVCProfileIndication
  out[2] = sps[2]; // profile_compatibility
  out[3] = sps[3]; // AVCLevelIndication
  out[4] = 0xff; // 6 bits reserved + lengthSizeMinusOne=3
  out[5] = 0xe1; // 3 bits reserved + numOfSPS=1
  out[6] = (sps.length >> 8) & 0xff;
  out[7] = sps.length & 0xff;
  out.set(sps, 8);
  let pos = 8 + sps.length;
  out[pos] = 1; // numOfPPS
  out[pos + 1] = (pps.length >> 8) & 0xff;
  out[pos + 2] = pps.length & 0xff;
  out.set(pps, pos + 3);
  return out;
}

/** 由 SPS 推出 `avc1.PPCCLL` 形式的 codec 字符串。 */
export function avcCodecString(sps) {
  const hex = (n) => n.toString(16).padStart(2, '0').toUpperCase();
  if (!sps || sps.length < 4) return 'avc1.42E01E';
  return `avc1.${hex(sps[1])}${hex(sps[2])}${hex(sps[3])}`;
}

/** Annex-B → AVCC（每 NAL 前置 4 字节长度），解码器期望这种格式。 */
export function annexBToAvcc(data) {
  const nals = splitAnnexB(data).filter((n) => {
    const t = nalType(n);
    return t >= 1 && t <= 5;
  });
  if (!nals.length) return null;
  let size = 0;
  for (const n of nals) size += 4 + n.length;
  const out = new Uint8Array(size);
  let pos = 0;
  for (const n of nals) {
    putU32(out, pos, n.length);
    pos += 4;
    out.set(n, pos);
    pos += n.length;
  }
  return out;
}

/** 解析首部 JSON 行（握手）。返回 { line, rest }。 */
export function readJsonLine(buffer) {
  for (let i = 0; i < buffer.length; i++) {
    if (buffer[i] === 0x0a) {
      const line = decoder.decode(buffer.subarray(0, i)).replace(/\r$/, '');
      return { line, rest: buffer.subarray(i + 1) };
    }
  }
  return null;
}

/* ==================================================================
 * 时钟同步（v1.1）
 *
 * 主机与接收端各有自己的单调时钟，不能直接比较 pts。
 * 做法：接收端发 PING（seq + 本地发送时刻），主机在 PONG 里**追加**自己的当前时钟
 * （第 9..12 字节），于是：
 *
 *   offset = hostNowMs + rtt/2 - clientRecvMs      // hostClock ≈ localClock + offset
 *
 * 8 字节的老 PONG 依然能解析（hostNowMs = null），保证向后兼容。
 * ================================================================== */

export function parsePong(payload) {
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const seq = payload.length >= 4 ? dv.getUint32(0) : 0;
  const clientSendMs = payload.length >= 8 ? dv.getUint32(4) : 0;
  const hostNowMs = payload.length >= 12 ? dv.getUint32(8) : null;
  return { seq, clientSendMs, hostNowMs };
}

export function estimateClockOffset({ clientSendMs, clientRecvMs, hostNowMs }) {
  if (hostNowMs == null) return null;
  const rtt = clientRecvMs - clientSendMs;
  return Math.round(hostNowMs + rtt / 2 - clientRecvMs);
}

/** 指数平滑，避免单次抖动导致音画跳动。 */
export function smoothOffset(previous, sample, alpha = 0.15) {
  if (sample == null) return previous;
  if (previous == null) return sample;
  return Math.round(previous + (sample - previous) * alpha);
}
