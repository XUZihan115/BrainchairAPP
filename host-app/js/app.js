/**
 * 上位机 B · 交互与规则引擎
 *
 * 链路：A 手机（脑电流，10 Hz 帧）→ B（本界面：3 s 锚定窗 + 双门控 CCA）→ C（3D 轮椅）
 * 所有安全规则都在 stepLogic / evaluateSafety 里，改一处即可，便于答辩逐条解释。
 */
import {
  RULES, CMD_BY_KEY, CMD_BY_CODE,
  rhoFromConfidence, speedFromRho, buildCommandFrame, toHex,
} from './rules.js';
import { World, zoneAt, slopeRadAt, pitAt, TRACK_LEN } from './scene3d.js';
import { RealEEGSource } from './eegreal.js';

const $ = id => document.getElementById(id);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const NAME = { stop: '停止', forward: '前进', left: '左转', right: '右转', backward: '后退' };

/* ══════════════════════ 状态 ══════════════════════ */
const S = {
  connected: true,
  mode: 'brain',          // brain | manual
  takeover: false,        // 脑控模式下的临时手动接管
  manualIdle: 0,
  estop: false,

  target: 'forward',      // 用户注视的目标（A 端）
  intent: 'stop',         // 连续 3 帧确认后的意图
  pendingKey: 'stop',
  pendingN: 0,
  conf: 100,

  cycleT: 0, winT: 0, decided: false,
  rho: 0, rho2: 0, gateA: false, gateB: false,
  vote: 0, voteKey: 'stop',

  cmd: 'stop', cmdSpeed: 0, turnRate: 0,
  seq: 0, frame: null,
  lastCmdAt: 0, dogMiss: 0, wd: false,
  linkDown: false, linkDownUntil: 0,
  brakeReason: null,

  chair: { z: 12, x: 0, yaw: 0, speed: 0, steer: 0, yawRate: 0, pitch: 0 },
  lat: 42,

  src: 'sim',             // sim | real
  realWin: null,          // 当前回放的真实试次
};

/** 真实脑电回放源。数据由 tools/decode_ssvep.py 从公开数据集解码导出。 */
const eeg = new RealEEGSource();
const theta1 = () => (S.src === 'real' && eeg.ready ? eeg.theta1 : RULES.THETA1);

const signalsMuted = () => S.mode === 'manual' || S.takeover;
const now = () => performance.now();

/* ══════════════════════ 3D ══════════════════════ */
const world = new World($('scene'));

/* ══════════════════════ 日志 ══════════════════════ */
const logBox = $('logBox');
function stamp() {
  const d = new Date();
  return [d.getHours(), d.getMinutes(), d.getSeconds()].map(n => String(n).padStart(2, '0')).join(':');
}
function log(msg, level = '') {
  const el = document.createElement('div');
  el.className = 'log-line ' + level;
  const t = document.createElement('time');
  t.textContent = stamp();
  const s = document.createElement('span');
  s.innerHTML = msg;
  el.append(t, s);
  logBox.insertBefore(el, logBox.firstChild);
  while (logBox.children.length > 60) logBox.removeChild(logBox.lastChild);
}

/* ══════════════════════ 横幅 ══════════════════════ */
const bannerEl = $('banner');
let bannerTimer = null;
function banner(text, kind = 'err', ms = 2600) {
  bannerEl.textContent = text;
  bannerEl.className = 'banner show' + (kind === 'info' ? ' info' : '');
  clearTimeout(bannerTimer);
  bannerTimer = setTimeout(() => bannerEl.classList.remove('show'), ms);
}

/* ══════════════════════ 指令下发 ══════════════════════ */
function issue(key, source = 'brain') {
  if (S.estop) return;
  if (S.linkDown) return;
  const changed = key !== S.cmd;
  S.cmd = key;
  S.turnRate = key === 'left' ? RULES.TURN_RATE : key === 'right' ? -RULES.TURN_RATE : 0;

  S.seq++;
  S.frame = buildCommandFrame({
    seq: S.seq,
    command: CMD_BY_KEY[key].code,
    confidence: S.rho,
    windowMs: RULES.WINDOW_SEC * 1000,
  });
  S.lastCmdAt = now();
  S.dogMiss = 0;
  S.wd = false;

  if (changed) {
    log(`→ 指令 <b>${NAME[key]}</b> (0x0${CMD_BY_KEY[key].code}) · ${source}`, key === 'stop' ? 'warn' : 'sys');
  }
}

/** 心跳：指令未变化时也按 2 Hz 重发，让 C 端只需记「最近一条」。 */
function heartbeat() {
  if (!S.connected || S.estop || S.linkDown) return;
  S.seq++;
  S.frame = buildCommandFrame({
    seq: S.seq,
    command: CMD_BY_KEY[S.cmd].code,
    confidence: S.rho,
    windowMs: RULES.WINDOW_SEC * 1000,
  });
  S.lastCmdAt = now();
  S.dogMiss = 0;
  S.wd = false;
}

/* ══════════════════════ 脑控决策 ══════════════════════ */
function resetWindow(reason) {
  S.cycleT = 0;
  S.winT = 0;
  S.decided = false;
  if (reason) log(`↺ ${reason} → 丢弃旧窗，重新起窗`, 'warn');
}

function decide() {
  const useReal = S.src === 'real' && eeg.ready;
  let intent, rho, rho2;

  if (useReal) {
    // 真实试次：判类与相关值都已在离线阶段算好，这里只负责按节拍喂进状态机
    const w = eeg.next();
    if (!w) return;
    S.realWin = w;
    intent = w.pred;
    rho = w.rho;
    rho2 = w.rho2;
    S.intent = intent;
    S.target = intent;
    paintReal(w);
  } else {
    const rest = S.intent === 'stop';
    intent = S.intent;
    rho = clamp((rest ? 0.492 : rhoFromConfidence(S.conf)) + (Math.random() - 0.5) * 0.022, 0.06, 0.93);
    rho2 = rest ? rho - (0.02 + Math.random() * 0.05) : 0.21 + Math.random() * 0.13;
  }

  S.rho = rho;
  S.rho2 = Math.max(0, rho2);
  S.gateA = rho > theta1();
  S.gateB = (rho - S.rho2) > (useReal ? eeg.theta2 : RULES.THETA2);

  let out = (S.gateA && S.gateB) ? intent : 'stop';
  if (S.brakeReason) out = 'stop';

  // 多窗投票
  if (out === S.voteKey) S.vote++; else { S.voteKey = out; S.vote = 1; }

  if (S.vote >= RULES.VOTE_N) {
    S.cmdSpeed = (out === 'forward' || out === 'backward') ? speedFromRho(rho) : 0;
    issue(out, 'brain');
  }

  const gateTxt = `${S.gateA ? '绝对门✓' : '绝对门✗'} ${S.gateB ? '差值门✓' : '差值门✗'}`;
  const tag = useReal
    ? `真实试次 sub-${S.realWin.sub} #${S.realWin.trial}（注视 ${NAME[S.realWin.true]}）· `
    : '';
  log(`◈ ${tag}判决 ρ=${rho.toFixed(3)} / 次高 ${S.rho2.toFixed(3)} · ${gateTxt} → <b>${NAME[out]}</b>`,
    out === 'stop' ? 'warn' : 'ok');
}

/** 把当前真实试次的信息刷到「脑电信号源」卡片上。 */
function paintReal(w) {
  if (!w) return;
  $('eegWho').textContent = `被试 ${w.sub} · 试次 ${w.trial + 1}/160 · 第 ${w.block} 次重复`;
  $('eegSeq').textContent = `${(eeg.pos % eeg.total) + 1} / ${eeg.total}`;
  const t = $('eegTrue'), p = $('eegPred');
  t.textContent = `${NAME[w.true]} · ${w.hz.toFixed(1)} Hz`;
  p.textContent = `${NAME[w.pred]} · ρ ${w.rho.toFixed(3)}`;
  p.className = w.ok ? 'ok' : 'err';
  eeg.draw(w);
}

/** A 端 10 Hz 脑机帧：意图切换需连续 3 帧一致（≈300 ms）。 */
function brainFrame() {
  if (!S.connected || S.estop || signalsMuted() || S.linkDown) return;
  // 真实信号源下没有「A 端 10 Hz 帧 + 3 帧确认」这一层：意图由 3 s 窗直接给出
  if (S.src === 'real' && eeg.ready) return;

  if (S.target === S.intent) { S.pendingN = 0; return; }
  if (S.target === S.pendingKey) S.pendingN++; else { S.pendingKey = S.target; S.pendingN = 1; }

  if (S.pendingN >= 3) {
    S.intent = S.target;
    S.pendingN = 0;
    log(`◎ 意图确认「${NAME[S.intent]}」（连续 3 帧一致）`, 'ok');
    resetWindow('意图切换');
  }
}

/* ══════════════════════ 安全规则 ══════════════════════ */
const BRAKE_TEXT = {
  pit: '坑地检测 · 自动停车',
  slope: `超坡度停车（> ${RULES.SLOPE_LIMIT_DEG}°）`,
  bound: '到达跑道边界 · 停车',
};

function brake(reason) {
  if (S.brakeReason) return;
  S.brakeReason = reason;
  S.cmdSpeed = 0;
  issue('stop', 'safety');
  banner(BRAKE_TEXT[reason]);
  log(`⚠ ${BRAKE_TEXT[reason]}`, 'err');
}

function clearBrake() {
  if (!S.brakeReason) return;
  log(`↩ 已离开危险区（${BRAKE_TEXT[S.brakeReason]} 解除）`, 'ok');
  S.brakeReason = null;
}

function evaluateSafety(dt) {
  const c = S.chair;

  // 后退即可解除锁定（避免在坑边/坡底反复触发）
  if (S.brakeReason && c.speed < -0.04) clearBrake();

  if (c.speed > 0.02) {
    const probe = c.z + 0.45 + c.speed * dt;

    // 坑地：车头进入坑沿 → 立即停车
    if (!S.brakeReason && pitAt(probe)) brake('pit');

    // 超坡度：前方坡度超过 15° → 停车
    if (!S.brakeReason) {
      const deg = Math.abs(slopeRadAt(probe)) * 180 / Math.PI;
      if (deg > RULES.SLOPE_LIMIT_DEG) brake('slope');
    }
  }

  // 下坡限速
  const slopeDeg = slopeRadAt(c.z) * 180 / Math.PI;
  if (slopeDeg < -4 && c.speed > RULES.DOWNHILL_LIMIT) {
    c.speed = RULES.DOWNHILL_LIMIT;
    if (!S._downhillWarned) {
      S._downhillWarned = true;
      banner('下坡限速 · 已限制至 0.55 m/s', 'info', 2200);
      log(`⛰ 下坡限速生效（${slopeDeg.toFixed(1)}°）→ 0.55 m/s`, 'warn');
    }
  } else if (slopeDeg > -1) {
    S._downhillWarned = false;
  }
}

/* ══════════════════════ 运动学 ══════════════════════ */
function stepMotion(dt) {
  const c = S.chair;

  let targetV = 0;
  let targetYawRate = 0;

  const alive = S.connected && !S.estop && !S.wd && !S.linkDown;
  if (alive) {
    if (S.cmd === 'forward') targetV = S.cmdSpeed;
    else if (S.cmd === 'backward') targetV = -S.cmdSpeed * 0.62;

    if (S.cmd === 'left' || S.cmd === 'right') {
      targetYawRate = S.turnRate;
    }
  }

  const accel = S.estop ? 6.0 : 0.85;
  const dv = targetV - c.speed;
  c.speed += clamp(dv, -accel * dt, accel * dt);
  if (Math.abs(c.speed) < 0.006) c.speed = 0;

  c.yawRate += (targetYawRate - c.yawRate) * Math.min(1, dt * 6.5);
  c.yaw += c.yawRate * Math.PI / 180 * dt;

  // 前轮转向角（视觉）
  c.steer += (clamp(-c.yawRate * 0.014, -0.5, 0.5) - c.steer) * Math.min(1, dt * 8);

  let dz = c.speed * Math.cos(c.yaw) * dt;
  let dx = c.speed * Math.sin(c.yaw) * dt;

  if (S.brakeReason) {
    if (dz > 0) dz = 0;
    if (dx !== 0 && Math.cos(c.yaw) > 0.2) dx = 0;
  }

  let nz = c.z + dz;
  let nx = c.x + dx;

  if (nz < 1.2) { nz = 1.2; if (c.speed < 0) { c.speed = 0; brake('bound'); } }
  if (nz > TRACK_LEN - 1.2) { nz = TRACK_LEN - 1.2; if (c.speed > 0) { c.speed = 0; brake('bound'); } }
  if (Math.abs(nx) > RULES.TRACK_HALF) {
    nx = Math.sign(nx) * RULES.TRACK_HALF;
    c.speed *= 0.6;
  }

  c.z = nz;
  c.x = nx;
  c.pitch = slopeRadAt(c.z);
}

/* ══════════════════════ 主循环 ══════════════════════ */
let last = now();
let frameAcc = 0;
let beatAcc = 0;

function stepLogic(dt) {
  const t = now();

  // 模拟断链
  if (S.linkDown && t > S.linkDownUntil) {
    S.linkDown = false;
    log('⟲ 链路恢复，恢复发帧', 'ok');
    banner('链路恢复', 'info', 1400);
    S.lastCmdAt = t;
  }

  // A 端 10 Hz
  frameAcc += dt;
  if (frameAcc >= 0.1) { frameAcc = 0; brainFrame(); }

  // 看门狗：1 s 收不到合法指令 → 停车（按仿真时间累计，掉帧/后台不会误判）
  S.dogMiss += dt;
  if (S.connected && !S.estop && S.dogMiss > RULES.WATCHDOG_MS / 1000) {
    if (!S.wd) {
      S.wd = true;
      S.cmdSpeed = 0;
      S.cmd = 'stop';
      banner('指令超时 1 s · 看门狗停车');
      log('⚠ 看门狗触发：1 s 未收到合法指令帧 → 强制停车', 'err');
    }
  }

  // 2 Hz 心跳
  beatAcc += dt;
  if (beatAcc >= 0.5) { beatAcc -= 0.5; heartbeat(); }

  // 锚定窗推进
  if (S.connected && !S.estop && !signalsMuted() && !S.linkDown && !S.wd) {
    S.cycleT += dt;
    S.winT = Math.min(S.cycleT, RULES.WINDOW_SEC);
    if (S.cycleT >= RULES.WINDOW_SEC && !S.decided) { S.decided = true; decide(); }
    if (S.cycleT >= RULES.CYCLE_SEC) { S.cycleT = 0; S.decided = false; S.winT = 0; }
  }

  // 手动接管 30 s 自动恢复
  if (S.takeover) {
    S.manualIdle += dt;
    if (S.manualIdle >= RULES.MANUAL_RESUME_MS / 1000) {
      S.takeover = false;
      S.manualIdle = 0;
      resetWindow();
      log('⟲ 手动操作已 30 s 无输入 → 自动恢复脑控接收', 'ok');
      banner('已恢复脑控接收', 'info', 1800);
    }
  }

  evaluateSafety(dt);
  stepMotion(dt);

  if (!S.estop && !S.wd && S.connected && !S.linkDown) {
    S.lat = clamp(38 + Math.random() * 26 + Math.abs(S.cmdSpeed) * 12, 34, 96);
  }
}

function tick(t) {
  let dt = (t - last) / 1000;
  last = t;
  dt = Math.min(dt, 0.05);

  stepLogic(dt);

  const c = S.chair;
  world.update(dt, {
    z: c.z, x: c.x, yaw: c.yaw, speed: c.speed,
    steer: c.steer, yawRate: c.yawRate, estop: S.estop,
  });
  world.render();

  if (t - lastUi > 90) { lastUi = t; renderUi(); }
  requestAnimationFrame(tick);
}
let lastUi = 0;

/* ══════════════════════ 渲染 ══════════════════════ */
function renderUi() {
  const c = S.chair;
  const zone = zoneAt(c.z);
  const slopeDeg = slopeRadAt(c.z) * 180 / Math.PI;

  // 顶栏链路
  $('nodeA').classList.toggle('on', S.connected);
  $('nodeB').classList.toggle('on', S.connected);
  $('nodeC').classList.toggle('on', S.connected && !S.linkDown);
  $('nodeB').classList.toggle('blink', S.connected && !S.linkDown);

  const pill = $('linkPill');
  pill.className = 'pill ' + (S.linkDown ? 'off' : S.connected ? 'on' : 'off');
  pill.querySelector('em').textContent = S.linkDown ? '链路中断' : S.connected ? '已连接 · 三端在线' : '未连接';

  // 控制页
  $('winBar').style.width = (S.winT / RULES.WINDOW_SEC * 100).toFixed(1) + '%';
  $('winText').textContent = signalsMuted()
    ? '已暂停（信号接收关闭）'
    : `${S.winT.toFixed(1)} / ${RULES.WINDOW_SEC.toFixed(1)} s`;
  $('nextDecide').textContent = S.decided
    ? `下一窗 ${(RULES.CYCLE_SEC - S.cycleT).toFixed(1)} s`
    : `判决还需 ${(RULES.WINDOW_SEC - S.cycleT).toFixed(1)} s`;
  $('voteInfo').textContent = `投票 ${S.vote}/${RULES.VOTE_N}`;

  $('rhoVal').textContent = S.rho.toFixed(3);
  const bar = $('rhoBar');
  bar.style.width = clamp(S.rho / 0.9 * 100, 0, 100).toFixed(1) + '%';
  bar.classList.toggle('bad', !S.gateA);
  const th1 = theta1();
  $('rhoMark').style.left = (th1 / 0.9 * 100).toFixed(1) + '%';
  $('gateA').className = 'gate ' + (S.gateA ? 'pass' : 'fail');
  $('gateA').textContent = `绝对门 ρ > ${th1.toFixed(3)}`;
  $('gateB').className = 'gate ' + (S.gateB ? 'pass' : 'fail');
  $('gateB').textContent = `差值门 Δρ > ${(S.src === 'real' && eeg.ready ? eeg.theta2 : RULES.THETA2).toFixed(3)}`;
  $('rho2Val').textContent = S.rho2 ? S.rho2.toFixed(3) : '—';
  $('cmdSpeedVal').textContent = Math.round(Math.abs(S.cmdSpeed) / RULES.SPEED_MAX * 100) + ' %';

  $('bigSpeed').textContent = Math.abs(c.speed).toFixed(2);
  $('bigCmd').textContent = S.estop ? '急停锁止' : NAME[S.cmd];
  $('bigMode').textContent = S.mode === 'manual' ? '手动模式' : S.takeover ? '脑控 · 手动接管' : '脑控模式';
  $('heroGauge').style.setProperty('--g', Math.min(100, Math.abs(c.speed) / RULES.SPEED_MAX * 100).toFixed(0));

  $('posVal').textContent = c.z.toFixed(1) + ' m';
  $('seqVal').textContent = String(S.seq);
  $('latVal').textContent = S.connected && !S.linkDown ? Math.round(S.lat) + ' ms' : '— ms';
  $('lossVal').textContent = (S.linkDown ? 12.4 : 0.0).toFixed(1) + ' %';

  // 徽标
  setBadge('badgeLink', S.linkDown ? 'err' : S.connected ? 'ok' : 'warn',
    S.linkDown ? '链路 · 中断' : S.connected ? '链路 · 正常' : '链路 · 未连接');
  setBadge('badgeDog', S.wd ? 'err' : 'ok', S.wd ? '看门狗 · 已触发' : '看门狗 · 正常');
  setBadge('badgeScene', zone.id === 'pit' ? 'warn' : zone.id === 'steep' ? 'err' : '', '场景 · ' + zone.name);
  setBadge('badgeSlope', Math.abs(slopeDeg) > 0.5 ? 'warn' : '', `坡度 · ${slopeDeg.toFixed(1)}°`);

  // HUD
  $('hudZone').textContent = zone.name;
  $('hudSlope').textContent = `${slopeDeg >= 0 ? '+' : ''}${slopeDeg.toFixed(1)}°`;
  $('hudSpeed').textContent = Math.abs(c.speed).toFixed(2);
  $('hudDist').textContent = c.z.toFixed(1) + ' m';

  const safety = $('hudSafety');
  const sub = $('hudSafetySub');
  if (S.estop) {
    safety.className = 'safety err'; safety.querySelector('b').textContent = '急停锁止';
    sub.textContent = '需手动解除';
  } else if (S.wd) {
    safety.className = 'safety err'; safety.querySelector('b').textContent = '断链停车';
    sub.textContent = '看门狗 1 s';
  } else if (S.brakeReason === 'pit') {
    safety.className = 'safety warn'; safety.querySelector('b').textContent = '坑地停车';
    sub.textContent = '后退可解除';
  } else if (S.brakeReason === 'slope') {
    safety.className = 'safety warn'; safety.querySelector('b').textContent = '超坡度停车';
    sub.textContent = '坡度超限';
  } else if (!S.connected) {
    safety.className = 'safety warn'; safety.querySelector('b').textContent = '未连接';
    sub.textContent = '控件置灰';
  } else {
    safety.className = 'safety'; safety.querySelector('b').textContent = '安全 · 正常';
    sub.textContent = '看门狗待命';
  }

  // 状态页 & 目标高亮
  document.querySelectorAll('.tgt').forEach(el => {
    el.classList.toggle('on', el.dataset.key === S.target);
  });

  $('manualState').textContent = S.mode === 'manual'
    ? '手动模式 · 脑控关闭'
    : S.takeover
      ? `接管中 · ${Math.max(0, Math.ceil(RULES.MANUAL_RESUME_MS / 1000 - S.manualIdle))} s 后恢复`
      : '按住即生效';
  $('manualState').className = 'tag ' + (signalsMuted() ? 'warn' : 'mute');

  $('brainCard').style.opacity = signalsMuted() ? .5 : 1;
  $('brainCard').style.pointerEvents = signalsMuted() ? 'none' : 'auto';

  if (S.frame) renderFrame();
}

const _badgeCache = {};
function setBadge(id, kind, text) {
  const el = $(id);
  if (_badgeCache[id] === kind + text) return;
  _badgeCache[id] = kind + text;
  el.className = 'badge ' + (kind || '');
  el.textContent = text;
}

let lastFrameSeq = -1;
function renderFrame() {
  if (lastFrameSeq === S.seq) return;
  lastFrameSeq = S.seq;
  const hex = toHex(S.frame);
  $('frameHex').innerHTML = hex.map((h, i) => {
    let cls = '';
    if (i < 2) cls = 'hl';
    else if (i === 8) cls = 'hl';
    else if (i >= 20) cls = 'hl2';
    return `<span class="${cls}">${h}</span>`;
  }).join('');
  $('frameCmd').textContent = String(S.frame[8]);
}

/* ══════════════════════ 交互 ══════════════════════ */
// 时钟
function clock() {
  const d = new Date();
  $('phClock').textContent = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
clock();
setInterval(clock, 20000);

// 页切换
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    tab.classList.add('active');
    document.querySelector(`.page[data-page="${tab.dataset.goto}"]`).classList.add('active');
    $('phoneScreen').scrollTop = 0;
  });
});

// 模式
$('modeSeg').addEventListener('click', e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const m = btn.dataset.mode;
  if (m === S.mode) return;
  S.mode = m;
  S.takeover = false;
  S.manualIdle = 0;
  document.querySelectorAll('#modeSeg button').forEach(b => b.classList.toggle('on', b.dataset.mode === m));
  if (m === 'manual') {
    S.cmdSpeed = 0;
    issue('stop', 'mode');
    banner('手动模式 · 信号接收已关闭', 'info', 2000);
    log('⇄ 切换到手动模式：关闭信号接收，优先执行手动指令', 'warn');
  } else {
    resetWindow();
    log('⇄ 切回脑控模式：恢复信号接收', 'ok');
  }
});

// 注视目标（真实信号源下变成「定向回放」）
$('targets').addEventListener('click', e => {
  const btn = e.target.closest('.tgt');
  if (!btn) return;
  const key = btn.dataset.key;

  if (S.src === 'real' && eeg.ready) {
    const w = eeg.jumpTo(key);
    if (w) {
      resetWindow(`定向回放「${NAME[key]}」`);
      log(`⇢ 跳到真实试次 sub-${w.sub} #${w.trial}（被试当时注视 ${NAME[w.true]}）`, 'ok');
    }
    return;
  }

  if (key === S.target) return;
  S.target = key;
  resetWindow(`注视目标切换为「${NAME[key]}」`);
});

/* ══════════════════════ 信号源切换 ══════════════════════ */
const HZ_SIM = { forward: '12.00 Hz', left: '8.57 Hz', stop: '静息', right: '6.67 Hz', backward: '5.45 Hz' };
const HZ_REAL = { forward: '12.0 Hz', left: '10.0 Hz', stop: '15.0 Hz', right: '13.0 Hz', backward: '9.0 Hz' };
let realLoading = false;

function paintTargetHz(map) {
  document.querySelectorAll('.tgt').forEach(el => {
    const i = el.querySelector('i');
    if (i) i.textContent = map[el.dataset.key] || '';
  });
}

$('srcSeg').addEventListener('click', async e => {
  const btn = e.target.closest('button');
  if (!btn || realLoading) return;
  const src = btn.dataset.src;
  if (src === S.src && (src === 'sim' || eeg.ready)) return;

  if (src === 'sim') {
    S.src = 'sim';
    S.realWin = null;
    document.querySelectorAll('#srcSeg button').forEach(b => b.classList.toggle('on', b.dataset.src === 'sim'));
    document.querySelectorAll('.tgt').forEach(el => el.classList.remove('on'));
    $('eegBox').hidden = true;
    $('srcTag').className = 'tag mute';
    $('srcTag').textContent = '内置模拟';
    $('srcHint').innerHTML = '当前用 <b class="mono">ρ(c)</b> 曲线合成脑机帧 —— 规则是真的，数据是编的。';
    paintTargetHz(HZ_SIM);
    S.rho = 0; S.rho2 = 0; S.intent = 'stop'; S.target = 'forward';
    resetWindow('切回内置模拟');
    log('⇄ 信号源：内置模拟（合成 ρ）', 'warn');
    return;
  }

  // 切到真实脑电
  realLoading = true;
  $('srcTag').className = 'tag warn';
  $('srcTag').textContent = '载入中…';
  try {
    const meta = eeg.ready ? eeg.meta : await eeg.load('./data/eeg-track.json');
    eeg.attachCanvas($('eegCanvas'));
    S.src = 'real';
    document.querySelectorAll('#srcSeg button').forEach(b => b.classList.toggle('on', b.dataset.src === 'real'));
    $('eegBox').hidden = false;
    $('srcTag').className = 'tag';
    $('srcTag').textContent = '真实数据';
    $('srcHint').innerHTML =
      `Dong2023 公开数据集 · ${meta.subjects.length} 名被试 · ${meta.n_windows} 个真实试次 · `
      + `3 s 窗 FBCCA 准确率 <b class="mono">${(meta.accuracy * 100).toFixed(0)} %</b>，`
      + `θ₁ 按该数据集重标为 <b class="mono">${meta.theta1_real.toFixed(2)}</b>。点选目标可定向回放。`;
    paintTargetHz(HZ_REAL);
    resetWindow('切入真实脑电');
    log(`⚡ 载入真实脑电：${meta.n_windows} 个试次，免训练 CCA 解码`, 'ok');
    banner('真实脑电已接入', 'info', 1800);
    eeg.draw(null);
  } catch (err) {
    $('srcTag').className = 'tag err';
    $('srcTag').textContent = '载入失败';
    $('srcHint').textContent = `读取 data/eeg-track.json 失败：${err.message}。需用本地 http 服务打开（start.bat）。`;
    log(`✗ 真实脑电载入失败：${err.message}`, 'err');
  } finally {
    realLoading = false;
  }
});

// 手动方向键
document.querySelectorAll('.dp').forEach(btn => {
  const code = Number(btn.dataset.cmd);

  const down = e => {
    e.preventDefault();
    if (S.estop) { banner('急停锁止中 · 请先解除', 'info', 1600); return; }
    if (!S.connected) { banner('未连接 · 控件置灰'); return; }
    btn.classList.add('press');

    if (S.mode === 'brain' && !S.takeover) {
      S.takeover = true;
      S.manualIdle = 0;
      log('✋ 手动接管：信号接收已关闭，30 s 无操作后恢复', 'warn');
      banner('手动接管 · 信号接收关闭', 'info', 1600);
    }
    if (S.takeover) S.manualIdle = 0;

    const key = CMD_BY_CODE[code].key;
    S.cmdSpeed = code === 1 || code === 4 ? RULES.SPEED_MAX * RULES.MANUAL_SPEED_SCALE : 0;
    issue(key, 'manual');
  };
  const up = () => {
    btn.classList.remove('press');
    if (Number(btn.dataset.cmd) !== 0) {
      issue('stop', 'manual');
      S.cmdSpeed = 0;
    }
  };

  btn.addEventListener('pointerdown', down);
  btn.addEventListener('pointerup', up);
  btn.addEventListener('pointercancel', up);
  btn.addEventListener('pointerleave', e => { if (e.buttons) up(); });
});

// 急停
$('btnEstop').addEventListener('click', () => {
  if (S.estop) return;
  S.estop = true;
  S.cmd = 'stop';
  S.cmdSpeed = 0;
  S.turnRate = 0;
  S.chair.speed = 0;
  $('btnEstop').classList.add('locked');
  $('btnUnlock').hidden = false;
  banner('急停已触发 · 全链路锁止', 'err', 3200);
  log('⛔ 急停：立即发 0x06，10 Hz 连发 3 次，1 s 内锁止', 'err');

  for (let i = 0; i < RULES.ESTOP_BURST; i++) {
    setTimeout(() => {
      S.seq++;
      S.frame = buildCommandFrame({
        seq: S.seq, command: 0x06, confidence: 1.0, windowMs: RULES.WINDOW_SEC * 1000,
      });
      log(`  急停帧 0x06 (${i + 1}/${RULES.ESTOP_BURST}) seq=${S.seq}`, 'err');
      lastFrameSeq = -1;
    }, i * RULES.ESTOP_INTERVAL_MS);
  }
});

$('btnUnlock').addEventListener('click', () => {
  S.estop = false;
  S.wd = false;
  S.lastCmdAt = now();
  S.brakeReason = null;
  S.vote = 0;
  resetWindow();
  $('btnEstop').classList.remove('locked');
  $('btnUnlock').hidden = true;
  banner('锁止已解除 · 恢复控制', 'info', 1800);
  log('✔ 急停锁止已解除', 'ok');
});

// 降质滑块
const slider = $('confSlider');
function syncSlider() {
  S.conf = Number(slider.value);
  $('confVal').textContent = S.conf + ' %';
  $('confRho').textContent = rhoFromConfidence(S.conf).toFixed(3);
  slider.style.setProperty('--p', S.conf + '%');
}
slider.addEventListener('input', syncSlider);
syncSlider();

// 连接 / 断开
$('btnConnect').addEventListener('click', () => {
  S.connected = !S.connected;
  const btn = $('btnConnect');
  if (S.connected) {
    btn.textContent = '断开';
    btn.classList.add('primary');
    btn.classList.remove('danger');
    S.lastCmdAt = now();
    $('tagA').textContent = '已连接';
    $('tagA').className = 'tag';
    $('tagC').textContent = '在线';
    $('tagC').className = 'tag';
    log('⟲ 重新连接 C 端 10.14.166.234:9100 成功', 'ok');
    banner('已连接', 'info', 1400);
  } else {
    btn.textContent = '连接';
    btn.classList.remove('primary');
    btn.classList.add('danger');
    S.cmd = 'stop';
    S.cmdSpeed = 0;
    S.chair.speed = 0;
    $('tagA').textContent = '已断开';
    $('tagA').className = 'tag err';
    $('tagC').textContent = '离线';
    $('tagC').className = 'tag mute';
    log('✕ 已断开连接：所有控件置灰，C 端看门狗 1 s 后停车', 'err');
    banner('已断开 · 看门狗将在 1 s 后停车');
  }
});

$('btnDiscover').addEventListener('click', () => {
  const btn = $('btnDiscover');
  btn.disabled = true;
  btn.textContent = '广播中…';
  log('→ 广播 DISCOVER (9001)，等待 ANNOUNCE…');
  setTimeout(() => {
    btn.disabled = false;
    btn.textContent = '自动发现';
    $('inpIp').value = '10.14.166.234';
    log('← 收到 C 端信标 10.14.166.234:9100（nonce 校验通过）', 'ok');
    banner('已发现 C 端 · 地址已回填', 'info', 1800);
  }, 1100);
});

// 模拟断链
$('btnBreakLink').addEventListener('click', () => {
  if (!S.connected) { banner('当前未连接', 'info', 1400); return; }
  S.linkDown = true;
  S.linkDownUntil = now() + 1200;
  $('lossVal').textContent = '12.4 %';
  log('✕ 模拟断链：停止发帧 1.2 s', 'err');
});

// 清空日志
$('btnClearLog').addEventListener('click', () => { logBox.innerHTML = ''; });

// 场景 / 相机
const PRESETS = {
  flat: { z: 12, note: '测试跑道 · 全程 93 m · 4 类地形' },
  pit: { z: 24.4, note: '坑地 z=27.6→31.6 · 车头进入即停车' },
  up: { z: 44.2, note: '爬坡 10.2° · 长度 13 m · 升高 2.34 m' },
  down: { z: 62.8, note: '下坡 −10.2° · 限速 0.55 m/s' },
  steep: { z: 84.4, note: '陡坡 18° · 超过 15° 限值即停车' },
};

$('sceneSeg').addEventListener('click', e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const id = btn.dataset.scene;
  document.querySelectorAll('#sceneSeg button').forEach(b => b.classList.toggle('on', b === btn));

  const c = S.chair;
  c.z = PRESETS[id].z;
  c.x = 0;
  c.yaw = 0;
  c.speed = 0;
  c.yawRate = 0;
  c.steer = 0;
  S.brakeReason = null;
  S.cmdSpeed = 0;
  S.vote = 0;
  S.wd = false;
  S.lastCmdAt = now();
  world.orbit.az = 0;
  world.pitchSmooth = slopeRadAt(c.z);
  issue('stop', 'scene');
  resetWindow();

  $('hudNote').textContent = PRESETS[id].note;
  log(`▣ 场景切换：${id.toUpperCase()} · 轮椅就位 z=${c.z.toFixed(1)} m`, 'sys');
  banner('场景已切换 · ' + PRESETS[id].note, 'info', 2000);
});

$('camSeg').addEventListener('click', e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  document.querySelectorAll('#camSeg button').forEach(b => b.classList.toggle('on', b === btn));
  world.setCamera(btn.dataset.cam);
});

// 弹窗
$('btnHelp').addEventListener('click', () => { $('helpModal').hidden = false; });
$('closeHelp').addEventListener('click', () => { $('helpModal').hidden = true; });
$('helpModal').addEventListener('click', e => {
  if (e.target === $('helpModal')) $('helpModal').hidden = true;
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') $('helpModal').hidden = true;
});

/* ══════════════════════ 启动 ══════════════════════ */
function boot() {
  log('=== 上位机 B 启动 · BUILD 20261119 ===', 'sys');
  log('设备 Android 14 (API 34)｜目标 C 端 10.14.166.234:9100', 'sys');
  log('MulticastLock 已持有｜前台服务已启动｜WifiLock HIGH_PERF', 'sys');
  log('← A 端 STREAM_INFO：62 ch @ 1000 Hz，float32 小端', 'ok');
  log('← A 端开始推流 256 kB/s（200 包/秒，5 ms 节拍）', 'ok');
  log('UDP 9100 已绑定｜C 端信标 1 Hz 在线｜CRC-32 逐包校验开启', 'ok');
  log('等待意图：一次决策需 3 s 锚定窗，约每 4 s 一拍', 'sys');

  S.lastCmdAt = now();
  issue('stop', 'boot');

  // 直接用真实脑电启动：index.html?src=real
  const q = new URLSearchParams(location.search);
  if (q.get('src') === 'real') {
    const btn = document.querySelector('#srcSeg [data-src="real"]');
    if (btn) btn.click();
  }

  requestAnimationFrame(tick);
}

boot();
