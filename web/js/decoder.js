import {
  extractSpsPps,
  buildAvcC,
  avcCodecString,
  annexBToAvcc,
} from './protocol.js';

/**
 * WebCodecs H.264 解码 + Canvas 呈现。
 *
 * 为什么用 Canvas 2D 而不是 WebGL：一帧 1080p 的 drawImage 在 Chromium 上是
 * GPU 加速的，延迟与 WebGL 持平，但代码量只有 1/10，而且天然支持等比/翻转/裁剪。
 */
export class H264Renderer {
  constructor(canvas, options = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    this.decoder = null;
    this.configured = false;
    this.mirror = false;
    this.framesDecoded = 0;
    this.lastFrameAt = 0;
    this.onError = options.onError || (() => {});
    this.onFrame = options.onFrame || (() => {});
    this._width = 0;
    this._height = 0;
  }

  /** csd = CONFIG 包的 Annex-B 负载（SPS+PPS）。 */
  configure(csd) {
    const sp = extractSpsPps(csd);
    if (!sp) {
      this.onError(new Error('CONFIG 包缺少 SPS/PPS'));
      return false;
    }
    const description = buildAvcC(sp.sps, sp.pps);
    const codec = avcCodecString(sp.sps);

    this.destroy();
    try {
      const decoder = new VideoDecoder({
        output: (frame) => this._render(frame),
        error: (e) => this.onError(e),
      });
      decoder.configure({
        codec,
        description,
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware',
      });
      this.decoder = decoder;
      this.configured = true;
      return true;
    } catch (e) {
      // 有些显卡不支持 prefer-hardware，退化到软解
      try {
        const decoder = new VideoDecoder({
          output: (frame) => this._render(frame),
          error: (err) => this.onError(err),
        });
        decoder.configure({ codec, description, optimizeForLatency: true });
        this.decoder = decoder;
        this.configured = true;
        return true;
      } catch (e2) {
        this.onError(e2);
        return false;
      }
    }
  }

  /** 送一帧；不行就丢，绝不排队（延迟优先）。 */
  submit(data, ptsMs, keyFrame) {
    const dec = this.decoder;
    if (!dec || dec.state !== 'configured') return;

    // 解码队列堆积 = 该丢帧了。丢 delta 帧，等下一个 IDR。
    if (!keyFrame && dec.decodeQueueSize > 8) return;

    const avcc = annexBToAvcc(data);
    if (!avcc) return;
    try {
      dec.decode(
        new EncodedVideoChunk({
          type: keyFrame ? 'key' : 'delta',
          timestamp: Math.round(ptsMs * 1000),
          data: avcc,
        })
      );
    } catch (e) {
      this.onError(e);
    }
  }

  _render(frame) {
    try {
      if (this._width !== frame.displayWidth || this._height !== frame.displayHeight) {
        this._width = frame.displayWidth;
        this._height = frame.displayHeight;
        this.canvas.width = this._width;
        this.canvas.height = this._height;
      }
      const ctx = this.ctx;
      const w = this.canvas.width;
      const h = this.canvas.height;
      if (this.mirror) {
        ctx.save();
        ctx.translate(w, 0);
        ctx.scale(-1, 1);
        ctx.drawImage(frame, 0, 0, w, h);
        ctx.restore();
      } else {
        ctx.drawImage(frame, 0, 0, w, h);
      }
      this.framesDecoded++;
      this.lastFrameAt = performance.now();
      this.onFrame(frame.displayWidth, frame.displayHeight);
    } finally {
      frame.close();
    }
  }

  /** 花屏/黑屏时调用：通知上层要一个 IDR。 */
  get pending() {
    return this.decoder ? this.decoder.decodeQueueSize : 0;
  }

  destroy() {
    if (this.decoder) {
      try {
        this.decoder.close();
      } catch (_) {}
      this.decoder = null;
    }
    this.configured = false;
  }
}

/** 浏览器/Electron 是否具备 WebCodecs（需要 Chromium 94+ / secure context）。 */
export function webCodecsAvailable() {
  return typeof window !== 'undefined' && 'VideoDecoder' in window;
}
