/**
 * 真实脑电回放源（SSVEP）。
 *
 * 数据不是现场编的：`tools/decode_ssvep.py` 从公开数据集
 * Dong2023（59 被试 40 类 SSVEP，NEMAR nm000128，CC BY-NC 4.0）里
 * 取真实试次，用 FBCCA 判类、单频带 CCA 取相关值，导出成这份 JSON。
 *
 * 这里只做两件事：
 *   1. 按 4 s 节拍把「已经算好的判决」喂给状态机（前端不再假装算信号）；
 *   2. 把该试次的两导原始波形画出来，让人看见信号是真的。
 */

const RANK = { forward: 0, left: 1, right: 2, backward: 3, stop: 4 };

export class RealEEGSource {
  constructor() {
    this.track = null;
    this.meta = null;
    this.order = [];
    this.pos = 0;
    this.snip = new Map();
    this.canvas = null;
    this.ctx = null;
  }

  get ready() { return !!this.track && this.order.length > 0; }
  get theta1() { return this.meta?.theta1_real ?? 0.63; }
  get theta2() { return this.meta?.theta2_real ?? 0.171; }
  get total() { return this.order.length; }

  async load(url) {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    this.track = await res.json();
    this.meta = this.track.meta;

    // 波形缩略：以 被试:试次 为键
    for (const s of this.track.snippets || []) {
      const bytes = Uint8Array.from(atob(s.b64), c => c.charCodeAt(0));
      this.snip.set(`${s.sub}:${s.trial}`, { data: new Int8Array(bytes.buffer), gain: s.gain });
    }

    // 回放顺序：先按指令分组（每个动作连着走几窗更像在开车），
    // 组内保持录制顺序。分组只是为了演示好看，判决结果本身没有重排。
    this.order = this.track.windows
      .map((w, i) => ({ w, i }))
      .sort((a, b) => (RANK[a.w.true] - RANK[b.w.true]) || (a.i - b.i))
      .map(o => o.w);

    this.pos = 0;
    return this.meta;
  }

  /** 取下一个窗口（循环播放）。 */
  next() {
    if (!this.ready) return null;
    const w = this.order[this.pos % this.order.length];
    this.pos++;
    return w;
  }

  /** 跳到某一类指令的下一个真实试次（给「定向回放」用）。 */
  jumpTo(key) {
    if (!this.ready) return null;
    const from = this.pos % this.order.length;
    for (let k = 0; k < this.order.length; k++) {
      const idx = (from + k) % this.order.length;
      if (this.order[idx].true === key) { this.pos = idx; return this.order[idx]; }
    }
    return null;
  }

  rewind() { this.pos = 0; }

  snippetOf(w) { return w ? this.snip.get(`${w.sub}:${w.trial}`) : null; }

  attachCanvas(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
  }

  /** 把当前试次的两导波形画到画布上（Oz / POz）。 */
  draw(w) {
    const ctx = this.ctx;
    if (!ctx || !this.canvas) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const cssW = this.canvas.clientWidth || 240;
    const cssH = this.canvas.clientHeight || 56;
    if (this.canvas.width !== Math.round(cssW * dpr)) {
      this.canvas.width = Math.round(cssW * dpr);
      this.canvas.height = Math.round(cssH * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);

    // 零线
    const rows = 2;
    const rowH = cssH / rows;
    ctx.strokeStyle = 'rgba(120,160,200,.18)';
    ctx.lineWidth = 1;
    for (let r = 0; r < rows; r++) {
      const y = rowH * r + rowH / 2;
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(cssW, y); ctx.stroke();
    }

    const s = this.snippetOf(w);
    if (!s) {
      ctx.fillStyle = 'rgba(150,180,210,.45)';
      ctx.font = '11px ui-monospace, monospace';
      ctx.fillText('该试次无波形切片', 8, cssH / 2 + 4);
      return;
    }

    const n = s.data.length / rows;
    const colors = ['#38e1c8', '#6f9dff'];
    for (let r = 0; r < rows; r++) {
      ctx.beginPath();
      ctx.strokeStyle = colors[r % colors.length];
      ctx.lineWidth = 1.15;
      for (let k = 0; k < n; k++) {
        const v = s.data[r * n + k] / 127;
        const x = (k / (n - 1)) * cssW;
        const y = rowH * r + rowH / 2 - v * (rowH / 2 - 4);
        k ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      }
      ctx.stroke();
    }

    ctx.fillStyle = 'rgba(140,175,205,.55)';
    ctx.font = '9px ui-monospace, monospace';
    ctx.fillText('Oz', 4, 10);
    ctx.fillText('POz', 4, rowH + 10);
  }
}
