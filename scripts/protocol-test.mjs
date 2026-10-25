#!/usr/bin/env node
/**
 * LumoCast 协议一致性测试（零依赖，`node scripts/protocol-test.mjs`）。
 *
 * 目的：保证 web/js/protocol.js 与 android/.../Protocol.kt 的字节级一致。
 * 这里用「已知向量」的方式：左手写期望的十六进制，右手跑实现，不一致就红。
 */

import assert from 'node:assert/strict';
import {
  encodeHeader,
  encodePacket,
  encodeControl,
  PacketParser,
  splitAnnexB,
  annexBToAvcc,
  buildAvcC,
  avcCodecString,
  extractSpsPps,
  parsePong,
  estimateClockOffset,
  T,
  F,
  HEADER_SIZE,
  VERSION,
} from '../web/js/protocol.js';

let passed = 0;
const test = (name, fn) => {
  try {
    fn();
    passed++;
    console.log(`  ✓ ${name}`);
  } catch (e) {
    console.error(`  ✗ ${name}\n    ${e.message}`);
    process.exitCode = 1;
  }
};

const hex = (u8) => Buffer.from(u8).toString('hex');

console.log('\nLumoCast protocol v1 — 一致性测试\n');

// ---------------------------------------------------------------- 包头

test('包头 16 字节且字段位置固定（与 Kotlin 实现对齐）', () => {
  const h = encodeHeader(T.VIDEO, F.KEYFRAME, 0x0000abcd, 0x00001234);
  assert.equal(h.length, HEADER_SIZE);
  assert.equal(hex(h), '4c43010201000000' + '0000abcd' + '00001234');
});

test('版本号为 1', () => {
  assert.equal(encodeHeader(T.VIDEO, 0, 0, 0)[2], VERSION);
});

test('4GB 以上的 pts 被截断到 u32（符合协议）', () => {
  const h = encodeHeader(T.VIDEO, 0, 0x1_0000_0005, 0);
  assert.equal(Buffer.from(h).readUInt32BE(8), 5);
});

// ---------------------------------------------------------------- 组包 / 拆包

test('组出的包能被自己的解析器还原', () => {
  const payload = new Uint8Array([1, 2, 3, 4, 5]);
  const pkt = encodePacket(T.VIDEO, F.KEYFRAME, 1234, payload);
  const [out] = new PacketParser().push(pkt);
  assert.equal(out.type, T.VIDEO);
  assert.equal(out.flags, F.KEYFRAME);
  assert.equal(out.ptsMs, 1234);
  assert.deepEqual([...out.payload], [1, 2, 3, 4, 5]);
});

test('TCP 任意分片（逐字节）也能正确重组', () => {
  const a = encodePacket(T.CONFIG, 0, 0, new Uint8Array([9, 9, 9]));
  const b = encodePacket(T.VIDEO, 1, 77, new Uint8Array(300).fill(7));
  const stream = new Uint8Array(a.length + b.length);
  stream.set(a, 0);
  stream.set(b, a.length);

  const parser = new PacketParser();
  const got = [];
  for (const byte of stream) got.push(...parser.push(new Uint8Array([byte])));
  assert.equal(got.length, 2);
  assert.equal(got[0].type, T.CONFIG);
  assert.equal(got[1].type, T.VIDEO);
  assert.equal(got[1].ptsMs, 77);
  assert.equal(got[1].payload.length, 300);
});

test('半个包头时不清空缓冲，等待后续字节', () => {
  const pkt = encodePacket(T.VIDEO, 0, 1, new Uint8Array([1]));
  const parser = new PacketParser();
  assert.deepEqual(parser.push(pkt.subarray(0, 10)), []);
  const out = parser.push(pkt.subarray(10));
  assert.equal(out.length, 1);
});

test('错误 magic 立刻抛错（防止对端不是 LumoCast 时静默卡死）', () => {
  const bad = new Uint8Array(HEADER_SIZE).fill(0);
  assert.throws(() => new PacketParser().push(bad), /bad magic/);
});

test('超大 payload 被拒绝', () => {
  const h = encodeHeader(T.VIDEO, 0, 0, 5 * 1024 * 1024);
  assert.throws(() => new PacketParser().push(h), /payload too large/);
});

test('CONTROL 包是合法 JSON', () => {
  const pkt = encodeControl({ t: 'stat', rtt: 12 });
  const [out] = new PacketParser().push(pkt);
  assert.equal(out.type, T.CONTROL);
  assert.deepEqual(JSON.parse(new TextDecoder().decode(out.payload)), { t: 'stat', rtt: 12 });
});

// ---------------------------------------------------------------- Annex-B

const START = [0, 0, 0, 1];
const SPS = [0x67, 0x4d, 0x00, 0x28, 0xec, 0xa0, 0x50, 0x17, 0xfc, 0xb8, 0x08, 0x80];
const PPS = [0x68, 0xee, 0x3c, 0x80];

function concat(...arrs) {
  const total = arrs.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const a of arrs) {
    out.set(a, o);
    o += a.length;
  }
  return out;
}

test('splitAnnexB 同时支持 3 字节与 4 字节起始码', () => {
  const data = concat(START, SPS, [0, 0, 1], PPS, START, [0x65, 1, 2, 3]);
  const nals = splitAnnexB(data);
  assert.equal(nals.length, 3);
  assert.equal(nals[0][0] & 0x1f, 7);
  assert.equal(nals[1][0] & 0x1f, 8);
  assert.equal(nals[2][0] & 0x1f, 5);
});

test('extractSpsPps 能从 CONFIG 负载里取到 SPS/PPS', () => {
  const csd = concat(START, SPS, START, PPS);
  const sp = extractSpsPps(csd);
  assert.deepEqual([...sp.sps], SPS);
  assert.deepEqual([...sp.pps], PPS);
});

test('buildAvcC 生成合法的 AVCDecoderConfigurationRecord', () => {
  const avcc = buildAvcC(new Uint8Array(SPS), new Uint8Array(PPS));
  assert.equal(avcc[0], 1);
  assert.equal(avcc[1], SPS[1]); // profile
  assert.equal(avcc[2], SPS[2]);
  assert.equal(avcc[3], SPS[3]);
  assert.equal(avcc[4], 0xff); // lengthSizeMinusOne = 3
  assert.equal(avcc[5], 0xe1);
  assert.equal((avcc[6] << 8) | avcc[7], SPS.length);
  const ppsPos = 8 + SPS.length;
  assert.equal(avcc[ppsPos], 1);
  assert.equal((avcc[ppsPos + 1] << 8) | avcc[ppsPos + 2], PPS.length);
});

test('avcCodecString 从 SPS 推出 avc1.PPCCLL', () => {
  assert.equal(avcCodecString(new Uint8Array(SPS)), 'avc1.4D0028');
});

test('annexBToAvcc 把 NAL 转成 4 字节长度前缀', () => {
  const data = concat(START, [0x65, 0xaa, 0xbb], START, [0x41, 0xcc]);
  const avcc = annexBToAvcc(data);
  const view = Buffer.from(avcc);
  assert.equal(view.readUInt32BE(0), 3);
  assert.equal(view[4], 0x65);
  assert.equal(view.readUInt32BE(7), 2);
  assert.equal(view[11], 0x41);
  assert.equal(avcc.length, 4 + 3 + 4 + 2);
});

test('annexBToAvcc 丢弃 SPS/PPS（它们走 description，不进帧）', () => {
  const data = concat(START, SPS, START, PPS, START, [0x65, 1]);
  const avcc = annexBToAvcc(data);
  assert.equal(Buffer.from(avcc).readUInt32BE(0), 2); // 只剩 IDR 那两个字节
});

// ---------------------------------------------------------------- 时钟同步（v1.1）

test('PONG 12 字节扩展（v1.1）可解析出主机时钟，8 字节老包不会崩', () => {
  const extended = new Uint8Array(12);
  new DataView(extended.buffer).setUint32(0, 42);
  new DataView(extended.buffer).setUint32(4, 1000);
  new DataView(extended.buffer).setUint32(8, 55_000);
  const a = parsePong(extended);
  assert.equal(a.seq, 42);
  assert.equal(a.clientSendMs, 1000);
  assert.equal(a.hostNowMs, 55_000);

  const legacy = new Uint8Array(8);
  new DataView(legacy.buffer).setUint32(0, 7);
  const b = parsePong(legacy);
  assert.equal(b.seq, 7);
  assert.equal(b.hostNowMs, null);
});

test('时钟偏移换算：offset = hostNow + rtt/2 - localRecv', () => {
  const offset = estimateClockOffset({
    clientSendMs: 10_000,
    clientRecvMs: 10_100, // rtt = 100ms
    hostNowMs: 500_050,
  });
  // 本地 10_100 时主机是 500_050 → 主机时钟 = 本地时钟 + 490_000（含 50ms 单程延迟）
  assert.equal(offset, 490_000);
});

console.log(`\n${passed} 项通过${process.exitCode ? '，存在失败' : ''}\n`);
