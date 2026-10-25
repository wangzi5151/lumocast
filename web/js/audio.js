import { AUDIO } from './protocol.js';

/**
 * 桌面端音频播放器：WebCodecs AudioDecoder → WebAudio。
 *
 * 音画同步思路和 Android 端一致（audio-master），但实现更精确：
 * 每个解码出来的 AudioData 都被排进 WebAudio 的时间线，我们记下
 * 「这段音频的宿主 pts ↔ AudioContext 时间」的映射，于是能精确回答
 * 「此刻用户听到的是主机时间几点」，而不是估算。
 */
export class AudioPlayer {
  constructor() {
    this.ctx = null;
    this.gain = null;
    this.decoder = null;
    this.enabled = true;
    this.muted = false;

    this.sampleRate = AUDIO.SAMPLE_RATE;
    this.channels = AUDIO.CHANNELS;

    this.nextTime = 0;
    this.mapPts = 0;
    this.mapCtxStart = 0;
    this.mapDurationMs = 0;
    this.resyncs = 0;
  }

  async ensureContext() {
    if (!this.ctx) {
      this.ctx = new AudioContext({
        latencyHint: 'interactive',
        sampleRate: this.sampleRate,
      });
      this.gain = this.ctx.createGain();
      this.gain.gain.value = this.muted ? 0 : 1;
      this.gain.connect(this.ctx.destination);
    }
    if (this.ctx.state === 'suspended') {
      try {
        await this.ctx.resume();
      } catch (_) {}
    }
  }

  /** @param asc AudioSpecificConfig（2 字节常见） */
  configure(asc) {
    if (typeof window.AudioDecoder === 'undefined') return false;
    this.destroyDecoder();
    try {
      const decoder = new AudioDecoder({
        output: (data) => this._play(data),
        error: (e) => console.warn('[audio]', e.message),
      });
      decoder.configure({
        codec: AUDIO.CODEC,
        sampleRate: this.sampleRate,
        numberOfChannels: this.channels,
        description: asc,
      });
      this.decoder = decoder;
      this.ensureContext();
      return true;
    } catch (e) {
      console.warn('[audio] configure failed', e.message);
      return false;
    }
  }

  /** @param data 裸 AAC 帧 @param ptsMs 宿主时间戳（与视频同源） */
  submit(data, ptsMs) {
    const dec = this.decoder;
    if (!dec || dec.state !== 'configured' || !this.enabled) return;
    try {
      dec.decode(new EncodedAudioChunk({ type: 'key', timestamp: Math.round(ptsMs * 1000), data }));
    } catch (e) {
      console.warn('[audio] decode', e.message);
    }
  }

  _play(audioData) {
    try {
      const ctx = this.ctx;
      if (!ctx) {
        audioData.close();
        return;
      }
      const frames = audioData.numberOfFrames;
      const channels = audioData.numberOfChannels;
      const buffer = ctx.createBuffer(channels, frames, audioData.sampleRate);
      for (let c = 0; c < channels; c++) {
        audioData.copyTo(buffer.getChannelData(c), { planeIndex: c, format: 'f32-planar' });
      }

      const now = ctx.currentTime;
      // 目标延迟 40ms：足够吸收调度抖动，人耳感觉不到
      let at = Math.max(now + 0.04, this.nextTime);

      // 背压保护：如果已经排到 250ms 之后（说明解码超前太多），
      // 直接跳到当前时间附近重排，避免「声音越拖越晚」。
      if (at - now > 0.25) {
        at = now + 0.04;
        this.resyncs++;
      }
      this.nextTime = at + buffer.duration;

      const src = ctx.createBufferSource();
      src.buffer = buffer;
      src.connect(this.gain);
      src.start(at);

      // 记录映射：pts(ms) ↔ AudioContext 时间
      this.mapPts = audioData.timestamp / 1000;
      this.mapCtxStart = at;
      this.mapDurationMs = buffer.duration * 1000;
    } finally {
      audioData.close();
    }
  }

  /**
   * 此刻正在听到的宿主时间（毫秒）。视频帧拿它做闸门。
   * @returns {number|null} null = 还没开始出声，不做同步
   */
  currentHostMs() {
    if (!this.ctx || !this.mapCtxStart) return null;
    const elapsedMs = (this.ctx.currentTime - this.mapCtxStart) * 1000;
    if (elapsedMs < -100) return this.mapPts;
    if (elapsedMs > this.mapDurationMs + 250) {
      // 这一小段早已播完（可能是暂停/丢包），保守返回最后一帧的结束点
      return this.mapPts + this.mapDurationMs;
    }
    return this.mapPts + Math.max(elapsedMs, 0);
  }

  setMuted(muted) {
    this.muted = muted;
    if (this.gain) this.gain.gain.value = muted ? 0 : 1;
  }

  destroyDecoder() {
    if (this.decoder) {
      try {
        this.decoder.close();
      } catch (_) {}
      this.decoder = null;
    }
  }

  destroy() {
    this.destroyDecoder();
    if (this.ctx) {
      try {
        this.ctx.close();
      } catch (_) {}
      this.ctx = null;
    }
  }
}
