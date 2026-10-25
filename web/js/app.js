import {
  PacketParser,
  T,
  encodeControl,
  encodePongFrom,
  encodePing,
  parsePong,
  estimateClockOffset,
  smoothOffset,
} from './protocol.js';
import { H264Renderer, webCodecsAvailable } from './decoder.js';
import { AudioPlayer } from './audio.js';

/**
 * 桌面端（Electron）渲染进程入口。
 *
 * 与主进程的约定（见 desktop/preload.js）：
 *   window.lumo.platform / version
 *   window.lumo.onDevices(cb)      主进程 UDP 发现结果
 *   window.lumo.refreshDevices()   发一次 PROBE
 *   window.lumo.connect({ip,port,pin})
 *   window.lumo.onStream(cb)       {kind:'welcome'|'data'|'closed', ...}
 *   window.lumo.send(uint8array)   往 socket 写控制包
 *   window.lumo.disconnect()
 *   window.lumo.setFullscreen(bool)
 *
 * 纯浏览器打开时没有 bridge → 进入「预览模式」，只展示 UI 并提示安装桌面版。
 */

const bridge = window.lumo || null;

const el = (id) => document.getElementById(id);
const screens = { home: el('screen-home'), player: el('screen-player') };

const state = {
  devices: [],
  connecting: false,
  connected: false,
  host: { ip: '', port: 0, name: 'LumoCast' },
  pendingDevice: null,
  frames: 0,
  bytes: 0,
  lastFrameAt: 0,
  rtt: 0,
  parser: new PacketParser(),
  renderer: null,
  audio: null,
  mirror: false,
  fit: 'contain',
  // ---- 音画同步 / 延迟统计
  clockOffset: null, // hostClock ≈ localClock + offset
  lastPts: 0,
  pingSeq: 1,
  latency: 0,
  dropped: 0,
  rtt: 0,
};

// ------------------------------------------------------------------ 启动

if (!webCodecsAvailable()) {
  showOverlay(
    '当前环境不支持 WebCodecs',
    '请使用 LumoCast 桌面版（Windows / Linux / macOS），或在 Chrome 111+ 中通过 https 打开本页。',
    false
  );
}

if (bridge) {
  bridge.onDevices((list) => {
    state.devices = list || [];
    renderDevices();
  });
  bridge.onStream(handleStreamEvent);
  el('plan-badge').textContent = 'FREE';
  el('net-info').textContent = `已连接局域网 · ${bridge.platform}`;
  bridge.refreshDevices();
} else {
  el('net-info').textContent = '预览模式（未连接桌面外壳）';
  el('device-empty').innerHTML = `
    <div class="pulse"><span></span></div>
    <h3>这是浏览器预览模式</h3>
    <p>浏览器无法打开原始 TCP 连接，请在桌面版中使用完整功能。</p>
    <p class="hint">桌面版下载：github.com/lumocast/lumocast/releases</p>`;
}

// ------------------------------------------------------------------ 渲染设备列表

function renderDevices() {
  const list = el('device-list');
  const empty = el('device-empty');
  list.innerHTML = '';

  if (!state.devices.length) {
    empty.classList.remove('hidden');
    return;
  }
  empty.classList.add('hidden');

  for (const d of state.devices) {
    const row = document.createElement('div');
    row.className = 'device';
    row.innerHTML = `
      <div class="device-icon">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <rect x="7" y="3" width="10" height="18" rx="2.5"/><path d="M10 20h4"/>
        </svg>
      </div>
      <div class="device-main">
        <div class="device-name"></div>
        <div class="device-sub">
          <span class="dot live"></span>
          <span class="sub-text"></span>
        </div>
      </div>
      <span class="tag ${d.free ? '' : 'pro'}">${d.free ? 'FREE' : 'PRO'}</span>
      ${d.pin ? '<span class="tag lock">PIN</span>' : ''}
    `;
    row.querySelector('.device-name').textContent = d.name || 'Android';
    row.querySelector('.sub-text').textContent =
      `${d.ip}:${d.port} · ${d.w}×${d.h} · ${d.fps}fps`;
    row.addEventListener('click', () => pickDevice(d));
    list.appendChild(row);
  }
}

function pickDevice(d) {
  state.pendingDevice = d;
  state.host = { ip: d.ip, port: d.port, name: d.name };
  if (d.pin) {
    el('pin-sub').textContent = `在「${d.name}」的屏幕上可以看到 4 位数字`;
    el('pin-input').value = '';
    el('pin-modal').classList.remove('hidden');
    el('pin-input').focus();
  } else {
    connect(d.ip, d.port, '');
  }
}

// ------------------------------------------------------------------ 连接

function connect(ip, port, pin) {
  if (!bridge) return;
  state.connecting = true;
  state.connected = false;
  state.parser = new PacketParser();
  state.frames = 0;
  state.bytes = 0;
  state.lastFrameAt = 0;

  el('host-name').textContent = state.host.name || ip;
  el('overlay-title').textContent = '正在连接…';
  el('overlay-sub').textContent = `${ip}:${port}`;
  el('btn-retry').classList.add('hidden');
  showScreen('player');

  bridge.connect({ ip, port, pin });
}

function handleStreamEvent(ev) {
  if (!ev) return;

  if (ev.kind === 'welcome') {
    state.connecting = false;
    state.connected = true;
    state.host.name = ev.name || state.host.name;
    el('host-name').textContent = state.host.name;
    el('player-overlay').classList.add('hidden');
    el('watermark').classList.toggle('hidden', !ev.free);
    startStatsLoop();
    return;
  }

  if (ev.kind === 'data') {
    let packets;
    try {
      packets = state.parser.push(new Uint8Array(ev.bytes));
    } catch (e) {
      console.error(e);
      return;
    }
    for (const p of packets) handlePacket(p);
    return;
  }

  if (ev.kind === 'closed') {
    state.connected = false;
    showOverlay('连接已断开', ev.reason || '主机停止了共享', true);
  }
}

function handlePacket(p) {
  switch (p.type) {
    case T.CONFIG: {
      if (!state.renderer) {
        state.renderer = new H264Renderer(el('video'), {
          onError: (e) => console.error('decode', e),
        });
      }
      state.renderer.mirror = state.mirror;
      state.renderer.configure(p.payload);
      break;
    }
    case T.VIDEO: {
      const key = (p.flags & 1) !== 0;
      state.frames++;
      state.bytes += p.payload.length;

      // 音画同步闸门（audio-master）：落后音频 150ms 以上的 delta 帧直接丢
      const audioClock = state.audio?.currentHostMs() ?? null;
      if (audioClock != null && !key && p.ptsMs - audioClock < -150) {
        state.dropped++;
        break;
      }
      state.lastFrameAt = performance.now();
      state.lastPts = p.ptsMs;
      state.renderer?.submit(p.payload, p.ptsMs, key);
      break;
    }
    case T.AUDIO_CONFIG: {
      state.audio = state.audio || new AudioPlayer();
      state.audio.configure(p.payload);
      break;
    }
    case T.AUDIO: {
      state.audio?.submit(p.payload, p.ptsMs);
      break;
    }
    case T.PONG: {
      const pong = parsePong(p.payload);
      const recv = Date.now();
      if (pong.hostNowMs != null) {
        const sample = estimateClockOffset({
          clientSendMs: pong.clientSendMs,
          clientRecvMs: recv,
          hostNowMs: pong.hostNowMs,
        });
        state.clockOffset = smoothOffset(state.clockOffset, sample, 0.2);
        state.rtt = Math.max(0, recv - pong.clientSendMs);
      }
      break;
    }
    case T.PING: {
      bridge?.send(encodePongFrom(p.payload));
      break;
    }
    case T.CONTROL: {
      try {
        const msg = JSON.parse(new TextDecoder().decode(p.payload));
        if (msg.t === 'end') showOverlay('共享已结束', msg.reason || '', true);
        if (msg.t === 'rotate') {
          // 主机旋转：丢弃旧解码器，等新的 CONFIG
          state.renderer?.destroy();
        }
      } catch (_) {}
      break;
    }
    default:
      break; // 未知类型忽略
  }
}

// ------------------------------------------------------------------ 统计 / 心跳

let statsTimer = null;

function startStatsLoop() {
  if (statsTimer) return;
  let lastFrames = 0;
  let lastBytes = 0;

  statsTimer = setInterval(() => {
    if (!state.connected) return;
    const fps = state.frames - lastFrames;
    const mbps = ((state.bytes - lastBytes) * 8) / 1_000_000;
    lastFrames = state.frames;
    lastBytes = state.bytes;

    el('hud-stats').textContent =
      `${fps} fps · ${mbps.toFixed(1)} Mbps` +
      (state.latency ? ` · ${state.latency}ms 延迟` : '') +
      (state.audio?.decoder ? ' · 有声' : '');

    // 收不到新帧 → 要一个关键帧（不动不动就重连是更差的选择）
    if (state.lastFrameAt && performance.now() - state.lastFrameAt > 1500) {
      bridge?.send(encodeControl({ t: 'kf' }));
    }
    bridge?.send(
      encodeControl({
        t: 'stat',
        recv: state.frames,
        drop: state.dropped,
        rtt: state.rtt,
        dec_ms: 0,
      })
    );
    // PING 用来测 RTT + 主机时钟偏移（音画同步就靠它）
    bridge?.send(encodePing(state.pingSeq++));

    // 端到端延迟 = 本地此刻 − (帧的宿主 pts − 偏移)
    if (state.clockOffset != null && state.lastPts > 0) {
      state.latency = Math.max(0, Math.round(Date.now() - (state.lastPts - state.clockOffset)));
    }
  }, 1000);
}

// ------------------------------------------------------------------ UI 事件

function showScreen(name) {
  Object.entries(screens).forEach(([key, node]) => {
    node.classList.toggle('active', key === name);
  });
  if (name === 'player') {
    el('hud').classList.remove('fade');
    autoHideHud();
  }
}

let hudTimer = null;
function autoHideHud() {
  clearTimeout(hudTimer);
  hudTimer = setTimeout(() => {
    if (state.connected) el('hud').classList.add('fade');
  }, 3200);
}

function showOverlay(title, sub, withRetry) {
  el('overlay-title').textContent = title;
  el('overlay-sub').textContent = sub || '';
  el('btn-retry').classList.toggle('hidden', !withRetry);
  el('player-overlay').classList.remove('hidden');
}

el('btn-refresh').addEventListener('click', () => {
  bridge?.refreshDevices();
  el('device-empty').classList.remove('hidden');
});

el('btn-settings').addEventListener('click', () => {
  alert(
    'LumoCast 1.1.0\n\n' +
      '· 视频仅在局域网内点对点传输，不经过任何服务器\n' +
      '· 不收集屏幕内容、无账号、无遥测\n' +
      '· 完全免费：无广告、无内购、无功能限制\n' +
      '· 支持 1440p / 60fps、多设备同时观看'
  );
});

el('btn-back').addEventListener('click', () => {
  bridge?.disconnect();
  state.connected = false;
  state.renderer?.destroy();
  state.renderer = null;
  state.audio?.destroy();
  state.audio = null;
  state.clockOffset = null;
  showScreen('home');
});

el('btn-retry').addEventListener('click', () => {
  connect(state.host.ip, state.host.port, '');
});

el('btn-mirror').addEventListener('click', (e) => {
  state.mirror = !state.mirror;
  if (state.renderer) state.renderer.mirror = state.mirror;
  e.currentTarget.classList.toggle('on', state.mirror);
});

el('btn-fit').addEventListener('click', (e) => {
  state.fit = state.fit === 'contain' ? 'cover' : 'contain';
  const v = el('video');
  v.classList.toggle('fit-cover', state.fit === 'cover');
  e.currentTarget.textContent = state.fit === 'contain' ? '适应' : '铺满';
});

el('btn-manual').addEventListener('click', () => {
  el('manual-input').value = '';
  el('manual-modal').classList.remove('hidden');
  el('manual-input').focus();
});

el('manual-cancel').addEventListener('click', () => el('manual-modal').classList.add('hidden'));
el('manual-ok').addEventListener('click', () => {
  const raw = el('manual-input').value.trim();
  const [ip, portStr] = raw.split(':');
  if (!ip) return;
  el('manual-modal').classList.add('hidden');
  state.host = { ip, port: Number(portStr) || 8737, name: ip };
  connect(ip, Number(portStr) || 8737, '');
});

el('pin-cancel').addEventListener('click', () => el('pin-modal').classList.add('hidden'));
el('pin-ok').addEventListener('click', () => {
  const pin = el('pin-input').value.trim();
  if (pin.length !== 4) return;
  el('pin-modal').classList.add('hidden');
  connect(state.pendingDevice.ip, state.pendingDevice.port, pin);
});
el('pin-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') el('pin-ok').click();
});
el('manual-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') el('manual-ok').click();
});

// 播放页：鼠标移动时显示 HUD，双击全屏
el('screen-player').addEventListener('mousemove', () => {
  el('hud').classList.remove('fade');
  autoHideHud();
});
el('screen-player').addEventListener('dblclick', () => {
  bridge?.setFullscreen(true);
});

// 主机 RTT：主进程会通过 welcome 附带，之后用 stat 回环估算
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && screens.player.classList.contains('active')) {
    el('btn-back').click();
  }
});
