import * as THREE from 'three';
import { World, WHEELCHAIR_SPEC, zoneAt, TRACK_LEN } from './index.js';

const canvas = document.getElementById('gl');
const world = new World(canvas);
// 开场给一个 3/4 前侧视角，比正后方看得清坐垫、控制器和脚踏
world.orbit.az = -2.35;
world.orbit.el = 0.30;
world.orbit.dist = 3.15;

/* ────────── 运动状态 ────────── */
const S = { z: 12, x: 0, yaw: 0, speed: 0, steer: 0, yawRate: 0, estop: false };
const CRUISE = 1.15;

/* ────────── 尺寸表 ────────── */
const spec = WHEELCHAIR_SPEC;
const bbox = new THREE.Box3().setFromObject(world.chair);
const size = new THREE.Vector3();
bbox.getSize(size);

const rows = [
  ['车长', size.z.toFixed(2) + ' m'],
  ['车宽', size.x.toFixed(2) + ' m'],
  ['车高', size.y.toFixed(2) + ' m'],
  ['后轮直径', (spec.rearWheel.treadRadius * 2).toFixed(2) + ' m'],
  ['前轮直径', (spec.caster.treadRadius * 2).toFixed(2) + ' m'],
  ['轴距', spec.overall.wheelbase.toFixed(3) + ' m'],
  ['轮距', spec.rearWheel.track.toFixed(3) + ' m'],
  ['坐垫高', spec.seat.topY.toFixed(3) + ' m'],
];
const dimRows = document.getElementById('dimRows');
dimRows.innerHTML = rows.map(([k, v]) => `<div class="row"><span>${k}</span><b>${v}</b></div>`).join('');

/* ────────── 部件列表 ────────── */
const NAMES = {
  rearWheelL: '左后轮', rearWheelR: '右后轮',
  casterL: '左前万向轮', casterR: '右前万向轮',
  frame: '车架', seat: '座垫', backrest: '靠背',
  armrestL: '左扶手', armrestR: '右扶手',
  footrestL: '左脚踏', footrestR: '右脚踏',
  pushHandleL: '左推手', pushHandleR: '右推手',
  controller: '控制器', battery: '电池包',
};

const partList = document.getElementById('partList');
partList.innerHTML = Object.keys(world.parts)
  .filter(k => world.parts[k] && world.parts[k].isObject3D)
  .map(k => `<button data-part="${k}">${NAMES[k] || k}</button>`)
  .join('');

/* 高亮框：注意不能给空 Box3（min=+∞ / max=-∞），否则顶点是 NaN，会把渲染器拖崩 */
const hl = new THREE.Box3Helper(new THREE.Box3(new THREE.Vector3(), new THREE.Vector3()), 0x38e1c8);
hl.visible = false;
hl.material.transparent = true;
hl.material.depthTest = false;
world.scene.add(hl);
let hlUntil = 0;

partList.addEventListener('click', e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const obj = world.parts[btn.dataset.part];
  if (!obj) return;
  hl.box.setFromObject(obj);
  hl.visible = true;
  hlUntil = performance.now() + 2200;
});

/* ────────── 相机 ────────── */
document.getElementById('camSeg').addEventListener('click', e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  for (const b of document.querySelectorAll('#camSeg button')) b.classList.toggle('on', b === btn);
  world.setCamera(btn.dataset.cam);
});

/* ────────── 显示开关 ────────── */
document.getElementById('ckTrack').addEventListener('change', e => {
  world.track.group.visible = e.target.checked;
  world.props.group.visible = e.target.checked;
});

let wire = false;
document.getElementById('ckWire').addEventListener('change', e => {
  wire = e.target.checked;
  world.chair.traverse(o => {
    if (!o.isMesh) return;
    // 首次切换时克隆材质，避免线框串到跑道共用的材质上
    if (!o.userData._ownMat) {
      o.material = o.material.clone();
      o.userData._ownMat = true;
    }
    o.material.wireframe = wire;
  });
});

const drive = document.getElementById('ckDrive');

/* ────────── 主循环 ────────── */
const badge = document.getElementById('badge');
let last = performance.now();
let lastBadge = 0;

/** 推进一帧（纯逻辑，不含 rAF） */
function step(now) {
  const dt = Math.min(0.05, (now - last) / 1000);
  last = now;

  if (drive.checked) {
    S.speed = CRUISE;
    S.z += S.speed * dt;
    if (S.z > TRACK_LEN - 4) S.z = 6; // 到终点回到起点，循环演示
  } else {
    S.speed = 0;
  }

  world.update(dt, S);
  world.render();

  if (hl.visible) {
    const t = (hlUntil - now) / 2200;
    hl.material.opacity = Math.max(0, Math.min(1, t * 2));
    if (now > hlUntil) hl.visible = false;
  }

  if (now - lastBadge > 120) {
    lastBadge = now;
    const z = zoneAt(S.z);
    badge.innerHTML = `z = <b>${S.z.toFixed(1)}</b> m · ${z.name} · 坡度 <b>${(Math.atan2(z.h1 - z.h0, z.z1 - z.z0) * 180 / Math.PI).toFixed(1)}</b>°`;
  }
}

function tick(now) {
  step(now);
  requestAnimationFrame(tick);
}

// ?frames=N 跑 N 帧后停住 —— 给无头截图 / 自动化用，正常打开走 rAF
const frames = Number(new URLSearchParams(location.search).get('frames') || 0);
if (frames > 0) {
  const t0 = performance.now();
  for (let i = 1; i <= frames; i++) step(t0 + i * 16.7);
} else {
  requestAnimationFrame(tick);
}
