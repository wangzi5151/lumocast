'use strict';

const { contextBridge, ipcRenderer } = require('electron');

/**
 * 渲染进程只能用这几个能力：发现设备、连接、发控制包、收数据。
 * 没有 fs、没有 net、没有 require —— 最小权限原则。
 */
contextBridge.exposeInMainWorld('lumo', {
  platform: process.platform,
  version: process.versions.electron,

  onDevices: (cb) => {
    const handler = (_e, list) => cb(list);
    ipcRenderer.on('devices', handler);
    return () => ipcRenderer.removeListener('devices', handler);
  },

  refreshDevices: () => ipcRenderer.send('refresh'),

  connect: ({ ip, port, pin }) => ipcRenderer.send('connect', { ip, port, pin }),

  onStream: (cb) => {
    const handler = (_e, ev) => cb(ev);
    ipcRenderer.on('stream', handler);
    return () => ipcRenderer.removeListener('stream', handler);
  },

  send: (bytes) => ipcRenderer.send('send', bytes),

  disconnect: () => ipcRenderer.send('disconnect'),

  setFullscreen: (value) => ipcRenderer.send('fullscreen', value),
});
