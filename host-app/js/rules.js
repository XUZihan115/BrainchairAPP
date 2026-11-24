/**
 * 规则常量与协议编码。
 *
 * 数值来源（仓库内已提交的文档，逐条可答）：
 *  - docs/handover-to-team-b.md v1.4 §2.4 / §7.2   3 s 锚定窗、θ₁≈0.63、θ₂≈0.171
 *  - docs/system-architecture.md §4                B→C 指令帧 24 B、magic 0x47F0、端口 9100
 *  - docs/PRD-BrainchairAPP-MVP.md FR-02/FR-03     急停连发 3 次、看门狗 1 s、手动接管 30 s
 */

export const RULES = Object.freeze({
  THETA1: 0.63,          // 绝对门：获胜相关值下限
  THETA2: 0.171,         // 差值门：获胜与次高的差值下限
  VOTE_N: 1,             // 连续一致窗数（演示取 1，答辩可改 2）
  WINDOW_SEC: 3.0,       // 锚定窗长度
  CYCLE_SEC: 4.0,        // 每试次一个锚定窗 → 约 4 s 一次判决
  DEBOUNCE_MS: 300,      // 指令切换最小保持
  WATCHDOG_MS: 1000,     // C 端 1 s 收不到指令即停车
  ESTOP_BURST: 3,        // 急停连发次数
  ESTOP_INTERVAL_MS: 100,
  MANUAL_RESUME_MS: 30000,
  SPEED_MAX: 1.2,        // m/s，对应 ρ=0.74
  RHO_REF: 0.74,         // 3 s 窗、试次头对齐下的方向窗平均相关
  MANUAL_SPEED_SCALE: 0.72,
  TURN_RATE: 58,         // deg/s
  SLOPE_LIMIT_DEG: 15,   // 超坡度停车
  DOWNHILL_LIMIT: 0.55,  // 下坡限速 m/s
  PIT_BLOCK_M: 0.42,     // 车头距坑边的安全距离
  TRACK_HALF: 3.2,       // 可行驶半宽
});

/** 指令表：编号对齐 system-architecture §3（0 停 / 1 前 / 2 左 / 3 右 / 4 后）。 */
export const CMDS = [
  { code: 0, key: 'stop',     name: '停止', hz: null,   arrow: '■' },
  { code: 1, key: 'forward',  name: '前进', hz: 12.00,  arrow: '↑' },
  { code: 2, key: 'left',     name: '左转', hz: 8.57,   arrow: '←' },
  { code: 3, key: 'right',    name: '右转', hz: 6.67,   arrow: '→' },
  { code: 4, key: 'backward', name: '后退', hz: 5.45,   arrow: '↓' },
];

export const CMD_BY_KEY = Object.fromEntries(CMDS.map(c => [c.key, c]));
export const CMD_BY_CODE = Object.fromEntries(CMDS.map(c => [c.code, c]));

/**
 * A 端降质滑块 → 3 s 锚定窗下预期相关值 ρ。
 *
 * 端点来自 handover §7.2 / system-architecture §7.5：
 * 100 % ≈ 0.74（原样发送），0 % ≈ 0.30（地板）；翻转点在 76 % 附近
 * （此处 ρ(76)≈0.557，正好落到静息段最高相关 0.578 之下）。
 * 这只是给界面用的粗略刻度，真实阈值必须按自己的滤波器重标。
 */
export function rhoFromConfidence(conf) {
  return 0.30 + 0.44 * Math.pow(Math.max(0, Math.min(100, conf)) / 100, 1.9);
}

/** 速度映射：ρ 从 θ₁ 线性升到 100 %，采用 docs 的 (x-30)/70 思路但以 ρ 为变量。 */
export function speedFromRho(rho) {
  const t = (rho - RULES.THETA1) / (RULES.RHO_REF - RULES.THETA1);
  return RULES.SPEED_MAX * Math.max(0.18, Math.min(1, t));
}

/* ───────────── CRC-32（IEEE 802.3 / zlib） ───────────── */
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(bytes) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

/**
 * B → C 指令帧，固定 24 字节，布局见 system-architecture §4.1。
 * 全部小端序。
 */
export function buildCommandFrame({ seq, command, confidence, windowMs }) {
  const buf = new ArrayBuffer(24);
  const v = new DataView(buf);
  v.setUint16(0, 0x47F0, true);       // magic（线上 F0 47）
  v.setUint8(2, 0x01);                // version
  v.setUint8(3, 0x01);                // msgType = 指令
  v.setUint32(4, seq >>> 0, true);    // seq
  v.setUint8(8, command & 0xFF);      // command
  v.setUint8(9, 0x01);                // flags: bit0 confidence 有效
  v.setUint16(10, 0, true);           // reserved
  v.setFloat32(12, confidence, true); // confidence = CCA 获胜相关值
  v.setUint32(16, windowMs >>> 0, true); // windowMs
  v.setUint32(20, crc32(new Uint8Array(buf, 0, 20)), true);
  return new Uint8Array(buf);
}

export function toHex(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, '0').toUpperCase());
}
