/**
 * LumoCast license 服务 —— 零依赖 Node HTTP 服务。
 *
 * 它只做三件事：
 *   1. 收 Stripe 的钱（Checkout + Webhook 验签）
 *   2. 用私钥签发离线 license（客户端永不联网校验）
 *   3. 换机重签（有次数限制）
 *
 * 它**不接触任何屏幕数据**，所以没有带宽成本，也能私有化部署。
 *
 *   node server.mjs            # 默认 :8787
 */
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8787);
const STRIPE_SECRET = process.env.STRIPE_SECRET || '';
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || '';
const PUBLIC_BASE = process.env.PUBLIC_BASE || 'http://localhost:5173';
const KEY_DIR = process.env.KEY_DIR || path.join(__dirname, 'keys');
const DB_FILE = process.env.DB_FILE || path.join(__dirname, 'licenses.json');

const PLANS = {
  yearly: { plan: 'pro_yearly', price: 48, days: 365, seats: 3 },
  lifetime: { plan: 'pro_lifetime', price: 128, days: 0, seats: 3 },
  team: { plan: 'team', price: 38, days: 365, seats: 5 },
};

// ------------------------------------------------------------------ 存储（够用就好）

function loadDb() {
  try {
    return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  } catch {
    return { licenses: {}, reissues: {} };
  }
}

function saveDb(db) {
  fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
}

// ------------------------------------------------------------------ 签发

function privateKey() {
  const p = path.join(KEY_DIR, process.env.KEY_FILE || 'dev-private.pem');
  if (!fs.existsSync(p)) {
    throw new Error(
      `缺少私钥 ${p}。先运行：node scripts/gen-license-keypair.mjs`
    );
  }
  return fs.readFileSync(p, 'utf8');
}

const b64url = (buf) => Buffer.from(buf).toString('base64url');

/** token = LC1.<payload_b64url>.<sig_b64url>；签名对象是「中间那段字符串的 ASCII 字节」 */
export function issueLicense({ sub, plan, seats, mid, org = null, days = 0 }) {
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    v: 1,
    sub,
    plan,
    seats: seats || 1,
    iat: now,
    exp: days > 0 ? now + days * 86400 : 0,
    mid: mid || null,
    org,
  };
  const payloadSeg = b64url(Buffer.from(JSON.stringify(payload), 'utf8'));
  const sig = crypto.sign('RSA-SHA256', Buffer.from(payloadSeg, 'ascii'), privateKey());
  return { token: `LC1.${payloadSeg}.${b64url(sig)}`, payload };
}

// ------------------------------------------------------------------ HTTP

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  const json = (status, body) => {
    const data = JSON.stringify(body);
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': '*',
      'content-length': Buffer.byteLength(data),
    });
    res.end(data);
  };

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'content-type,stripe-signature',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
    });
    return res.end();
  }

  try {
    // -------------------------------------------------- 健康检查
    if (req.method === 'GET' && url.pathname === '/healthz') {
      return json(200, { ok: true, plans: Object.keys(PLANS), stripe: !!STRIPE_SECRET });
    }

    // -------------------------------------------------- 下单
    if (req.method === 'POST' && url.pathname === '/v1/checkout') {
      const body = await readJson(req);
      const plan = PLANS[body.plan || url.searchParams.get('plan')];
      if (!plan) return json(400, { error: 'unknown_plan' });
      if (!STRIPE_SECRET) {
        return json(503, { error: 'stripe_not_configured', hint: '开发模式可直接 POST /v1/dev/issue' });
      }

      const form = new URLSearchParams();
      form.set('mode', plan.days ? 'subscription' : 'payment');
      form.set('success_url', `${PUBLIC_BASE}/pro?ok=1&session_id={CHECKOUT_SESSION_ID}`);
      form.set('cancel_url', `${PUBLIC_BASE}/pro?cancel=1`);
      form.set('line_items[0][quantity]', '1');
      form.set('line_items[0][price_data][currency]', 'cny');
      form.set('line_items[0][price_data][unit_amount]', String(plan.price * 100));
      form.set(
        'line_items[0][price_data][product_data][name]',
        `LumoCast Pro ${plan.plan}`
      );
      if (plan.days) {
        form.set('line_items[0][price_data][recurring][interval]', 'year');
      }

      const r = await fetch('https://api.stripe.com/v1/checkout/sessions', {
        method: 'POST',
        headers: {
          authorization: `Bearer ${STRIPE_SECRET}`,
          'content-type': 'application/x-www-form-urlencoded',
        },
        body: form,
      });
      const session = await r.json();
      if (!r.ok) return json(502, { error: 'stripe_error', detail: session.error?.message });
      return json(200, { url: session.url, id: session.id });
    }

    // -------------------------------------------------- Stripe webhook
    if (req.method === 'POST' && url.pathname === '/v1/webhook/stripe') {
      const raw = await readRaw(req);
      if (WEBHOOK_SECRET && !verifyStripe(raw, req.headers['stripe-signature'], WEBHOOK_SECRET)) {
        return json(400, { error: 'bad_signature' });
      }
      const event = JSON.parse(raw.toString('utf8'));

      if (event.type === 'checkout.session.completed') {
        const session = event.data.object;
        const mid = session.client_reference_id || null;
        const meta = session.metadata || {};
        const plan = PLANS[meta.plan] || PLANS.yearly;
        const { token, payload } = issueLicense({
          sub: session.customer || session.id,
          plan: plan.plan,
          seats: plan.seats,
          mid,
          days: plan.days,
        });
        const db = loadDb();
        db.licenses[session.id] = { token, payload, email: session.customer_details?.email };
        saveDb(db);
        console.log(`[license] issued ${plan.plan} for ${session.customer}`);
      }
      return json(200, { received: true });
    }

    // -------------------------------------------------- 换机重签
    if (req.method === 'POST' && url.pathname === '/v1/license/reissue') {
      const body = await readJson(req);
      const db = loadDb();
      const entry = db.licenses[body.sessionId];
      if (!entry) return json(404, { error: 'not_found' });

      const used = db.reissues[body.sessionId] || 0;
      if (used >= (entry.payload.seats || 3)) {
        return json(429, { error: 'seat_limit', used });
      }
      const { token, payload } = issueLicense({
        sub: entry.payload.sub,
        plan: entry.payload.plan,
        seats: entry.payload.seats,
        mid: body.mid,
        org: entry.payload.org,
        days: entry.payload.exp ? Math.max(1, Math.ceil((entry.payload.exp - Date.now() / 1000) / 86400)) : 0,
      });
      db.reissues[body.sessionId] = used + 1;
      db.licenses[body.sessionId] = { ...entry, token, payload };
      saveDb(db);
      return json(200, { token, remaining: (entry.payload.seats || 3) - used - 1 });
    }

    // -------------------------------------------------- 本地校验（企业审计用）
    if (req.method === 'GET' && url.pathname === '/v1/license/verify') {
      const token = url.searchParams.get('token') || '';
      return json(200, verifyToken(token));
    }

    // -------------------------------------------------- 开发用：直接发一张
    if (req.method === 'POST' && url.pathname === '/v1/dev/issue') {
      if (process.env.LUMO_DEV !== '1') return json(403, { error: 'dev_only' });
      const body = await readJson(req);
      const plan = PLANS[body.plan] || PLANS.lifetime;
      const { token, payload } = issueLicense({
        sub: body.sub || 'dev',
        plan: plan.plan,
        seats: plan.seats,
        mid: body.mid,
        days: plan.days,
      });
      const db = loadDb();
      db.licenses[body.sub || `dev-${Date.now()}`] = { token, payload };
      saveDb(db);
      return json(200, { token, payload });
    }

    return json(404, { error: 'not_found' });
  } catch (e) {
    console.error(e);
    return json(500, { error: 'internal', detail: e.message });
  }
});

// ------------------------------------------------------------------ 工具

function readRaw(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function readJson(req) {
  const raw = await readRaw(req);
  if (!raw.length) return {};
  return JSON.parse(raw.toString('utf8'));
}

/** Stripe 的 t=...,v1=... 签名校验（不引 SDK 也能做） */
function verifyStripe(payload, header, secret, toleranceSec = 300) {
  if (!header) return false;
  const parts = Object.fromEntries(
    header.split(',').map((kv) => {
      const i = kv.indexOf('=');
      return [kv.slice(0, i), kv.slice(i + 1)];
    })
  );
  const timestamp = Number(parts.t);
  if (!timestamp || Math.abs(Date.now() / 1000 - timestamp) > toleranceSec) return false;
  const expected = crypto
    .createHmac('sha256', secret)
    .update(`${parts.t}.${payload.toString('utf8')}`)
    .digest('hex');
  const given = parts.v1 || '';
  return (
    expected.length === given.length &&
    crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(given))
  );
}

/** 用公钥验证 token（服务端自己也要能验，方便排查） */
export function verifyToken(token) {
  const parts = (token || '').split('.');
  if (parts.length !== 3 || parts[0] !== 'LC1') return { valid: false, reason: 'format' };
  try {
    const pubPath = path.join(KEY_DIR, 'dev-public.pem');
    const pub = fs.readFileSync(pubPath, 'utf8');
    const ok = crypto.verify(
      'RSA-SHA256',
      Buffer.from(parts[1], 'ascii'),
      pub,
      Buffer.from(parts[2], 'base64url')
    );
    if (!ok) return { valid: false, reason: 'signature' };
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    const expired = payload.exp !== 0 && Date.now() / 1000 > payload.exp;
    return { valid: !expired, expired, payload };
  } catch (e) {
    return { valid: false, reason: e.message };
  }
}

server.listen(PORT, () => {
  console.log(`LumoCast license server → http://localhost:${PORT}`);
  console.log(`  计划: ${Object.keys(PLANS).join(', ')}`);
  console.log(`  Stripe: ${STRIPE_SECRET ? '已配置' : '未配置（仅开发模式）'}`);
});
