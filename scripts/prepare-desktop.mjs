#!/usr/bin/env node
// 把 web/ 拷进 desktop/web，让 electron-builder 能把它打进 asar。
import { cp, rm, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, 'web');
const dest = path.join(root, 'desktop', 'web');

await rm(dest, { recursive: true, force: true });
await cp(src, dest, {
  recursive: true,
  filter: (f) => !f.endsWith('package.json'),
});
await access(path.join(dest, 'index.html'));
console.log('✔ web/ → desktop/web');
