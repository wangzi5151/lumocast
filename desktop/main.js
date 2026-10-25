'use strict';

const { app, BrowserWindow, ipcMain, protocol, net, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const dgram = require('node:dgram');
const tcp = require('node:net');
const { pathToFileURL } = require('node:url');

// 开发时直接跑仓库里的 web/；打包后 scripts/prepare-desktop.mjs 会把它拷进 desktop/web
const WEB_ROOT = [
  path.join(__dirname, 'web'),
  path.join(__dirname, '..', 'web'),
].find((p) => fs.existsSync(path.join(p, 'index.html')));
const APP_SCHEME = 'app';
const PORT_CONTROL = 8737;
const PORT_DISCOVERY = 8738;
const VERSION = 1;

// WebCodecs 只在 secure context 可用，而 http://192.168.x.x 不是。
// 所以用注册为 privileged(secure) 的自定义协议把 UI 从 app:// 加载。
protocol.registerSchemesAsPrivileged([
  {
    scheme: APP_SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true },
  },
]);

// 桌面端默认允许自动播放：用户点「连接」就算交互了，不需要再点一次喇叭
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

let win = null;
let udpSocket = null;
let tcpSocket = null;
const devices = new Map(); // key(ip:port) → { lastSeen, ...info }

// ------------------------------------------------------------------ window

function createWindow() {
  win = new BrowserWindow({
    width: 1080,
    height: 720,
    minWidth: 720,
    minHeight: 480,
    backgroundColor: '#0b0d17',
    title: 'LumoCast',
    show: false,
    autoHideMenuBar: true,
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false,
    },
  });

  win.loadURL(`${APP_SCHEME}://lumocast/index.html`);
  win.once('ready-to-show', () => win.show());

  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  if (process.argv.includes('--dev')) win.webContents.openDevTools({ mode: 'detach' });
  win.on('closed', () => {
    win = null;
  });
}

app.whenReady().then(() => {
  protocol.handle(APP_SCHEME, (request) => {
    const url = new URL(request.url);
    let p = decodeURIComponent(url.pathname);
    if (p === '/' || p === '') p = '/index.html';
    const filePath = path.join(WEB_ROOT, path.normalize(p).replace(/^([/\\])+/, ''));
    if (!filePath.startsWith(WEB_ROOT)) {
      return new Response('forbidden', { status: 403 });
    }
    return net.fetch(pathToFileURL(filePath).toString());
  });

  createWindow();
  startDiscovery();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  stopDiscovery();
  disconnect();
  if (process.platform !== 'darwin') app.quit();
});

// ------------------------------------------------------------------ UDP 发现

function startDiscovery() {
  try {
    udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    udpSocket.on('message', (msg, rinfo) => {
      let obj;
      try {
        obj = JSON.parse(msg.toString('utf8'));
      } catch (_) {
        return;
      }
      if (obj.svc !== 'lumocast' || (obj.t && obj.t !== 'announce')) return;
      const port = obj.port || PORT_CONTROL;
      const key = `${rinfo.address}:${port}`;
      const prev = devices.get(key);
      devices.set(key, {
        ip: rinfo.address,
        port,
        name: obj.name || 'Android',
        pin: !!obj.pin,
        free: obj.free !== false,
        w: obj.w || 1080,
        h: obj.h || 1920,
        fps: obj.fps || 30,
        nonce: obj.nonce || '',
        lastSeen: Date.now(),
        changed: !prev || prev.nonce !== obj.nonce || prev.name !== obj.name,
      });
    });
    udpSocket.on('error', (e) => console.warn('[udp]', e.message));
    udpSocket.bind(PORT_DISCOVERY, () => {
      try {
        udpSocket.setBroadcast(true);
      } catch (_) {}
      sendProbe();
      setInterval(sendProbe, 4000);
      setInterval(publishDevices, 1000);
    });
  } catch (e) {
    console.warn('[discovery] failed:', e.message);
  }
}

function sendProbe() {
  if (!udpSocket) return;
  const payload = Buffer.from(JSON.stringify({ svc: 'lumocast', t: 'probe', v: VERSION }));
  for (const target of broadcastTargets()) {
    try {
      udpSocket.send(payload, 0, payload.length, PORT_DISCOVERY, target, () => {});
    } catch (_) {}
  }
}

/** 逐个网卡的 /24 广播地址 —— 比只用 255.255.255.255 更可靠（很多路由器不转发全局广播）。 */
function broadcastTargets() {
  const list = ['255.255.255.255'];
  const nets = require('node:os').networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const ni of nets[name] || []) {
      if (ni.family !== 'IPv4' || ni.internal || !ni.netmask) continue;
      const ip = ni.address.split('.').map(Number);
      const mask = ni.netmask.split('.').map(Number);
      const bcast = ip.map((o, i) => (o & mask[i]) | (~mask[i] & 255));
      list.push(bcast.join('.'));
    }
  }
  return [...new Set(list)];
}

function publishDevices() {
  const now = Date.now();
  let changed = false;
  for (const [key, d] of devices) {
    if (now - d.lastSeen > 6500) {
      devices.delete(key);
      changed = true;
    } else if (d.changed) {
      d.changed = false;
      changed = true;
    }
  }
  if (!changed && devices.size === 0) return;
  const list = [...devices.values()]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(({ changed, lastSeen, ...rest }) => rest);
  send('devices', list);
}

function stopDiscovery() {
  try {
    udpSocket?.close();
  } catch (_) {}
  udpSocket = null;
}

// ------------------------------------------------------------------ TCP 会话

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function disconnect() {
  if (tcpSocket) {
    try {
      tcpSocket.write(controlPacket({ t: 'bye' }));
    } catch (_) {}
    try {
      tcpSocket.destroy();
    } catch (_) {}
    tcpSocket = null;
  }
}

ipcMain.on('connect', (_evt, { ip, port, pin }) => {
  disconnect();
  const socket = new tcp.Socket();
  tcpSocket = socket;
  socket.setNoDelay(true);

  let buf = Buffer.alloc(0);
  let handshakeDone = false;

  socket.connect(port || PORT_CONTROL, ip, () => {
    const hello = JSON.stringify({
      t: 'hello',
      v: VERSION,
      role: 'viewer',
      device: `LumoCast Desktop (${process.platform})`,
      pin: pin || '',
    });
    socket.write(hello + '\n');
  });

  socket.on('data', (chunk) => {
    if (!handshakeDone) {
      buf = Buffer.concat([buf, chunk]);
      const nl = buf.indexOf(0x0a);
      if (nl < 0) return;
      const line = buf.subarray(0, nl).toString('utf8').replace(/\r$/, '');
      const rest = buf.subarray(nl + 1);
      handshakeDone = true;
      let obj;
      try {
        obj = JSON.parse(line);
      } catch (_) {
        send('stream', { kind: 'closed', reason: '握手响应无法解析' });
        socket.destroy();
        return;
      }
      if (obj.t === 'error') {
        send('stream', { kind: 'closed', reason: obj.msg || `被拒绝：${obj.code}` });
        socket.destroy();
        return;
      }
      if (obj.t !== 'welcome') {
        send('stream', { kind: 'closed', reason: '协议错误：期望 welcome' });
        socket.destroy();
        return;
      }
      send('stream', {
        kind: 'welcome',
        sid: obj.sid,
        width: obj.w,
        height: obj.h,
        fps: obj.fps,
        free: !!obj.free,
        pinRequired: !!obj.pin_required,
        name: obj.name,
        cap: obj.cap || [],
      });
      if (rest.length) send('stream', { kind: 'data', bytes: new Uint8Array(rest) });
      return;
    }
    send('stream', { kind: 'data', bytes: new Uint8Array(chunk) });
  });

  socket.on('error', (e) => {
    send('stream', { kind: 'closed', reason: e.message });
  });
  socket.on('close', () => {
    if (tcpSocket === socket) tcpSocket = null;
    send('stream', { kind: 'closed', reason: '连接已关闭' });
  });
});

ipcMain.on('send', (_evt, bytes) => {
  if (!tcpSocket) return;
  try {
    tcpSocket.write(Buffer.from(bytes));
  } catch (_) {}
});

ipcMain.on('disconnect', () => disconnect());
ipcMain.on('refresh', () => sendProbe());

ipcMain.on('fullscreen', (_evt, value) => {
  if (win) win.setFullScreen(value !== false);
});

// 说明：RTT 与时钟偏移由渲染进程自己发 PING / 解析 PONG 计算（它才有 Date.now 语义一致的时钟），
// 主进程只做字节搬运，避免两个时钟混用导致音画同步抖动。

// ------------------------------------------------------------------ 包构造（最小实现，避免主进程引入 ESM 依赖）

function header(type, flags, ptsMs, len) {
  const b = Buffer.alloc(16);
  b[0] = 0x4c;
  b[1] = 0x43;
  b[2] = VERSION;
  b[3] = type;
  b[4] = flags;
  b.writeUInt32BE(ptsMs >>> 0, 8);
  b.writeUInt32BE(len, 12);
  return b;
}

function pingPacket(seq) {
  const payload = Buffer.alloc(8);
  payload.writeUInt32BE(seq >>> 0, 0);
  payload.writeUInt32BE(Date.now() >>> 0, 4);
  return Buffer.concat([header(4, 0, 0, 8), payload]);
}

function controlPacket(obj) {
  const payload = Buffer.from(JSON.stringify(obj), 'utf8');
  return Buffer.concat([header(6, 0, 0, payload.length), payload]);
}


