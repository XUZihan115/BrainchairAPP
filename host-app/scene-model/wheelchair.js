/**
 * 电动轮椅 —— 纯程序化建模，零外部资源（无 glTF / OBJ / 贴图）。
 *
 * 坐标系（与 track.js 一致）：+Z 前进，+Y 向上，+X 为驾驶员左手侧。
 * 原点在**地面**、两后轮轴心连线的中点正下方，所以 group.position.y 直接等于路面高度。
 *
 * 所有尺寸都能在 WHEELCHAIR_SPEC 里查到，可直接对照重建成 C# / Unity 或其它引擎。
 */
import * as THREE from 'three';

/* ══════════════════════ 尺寸表 ══════════════════════ */

const R = 0.281;      // 后轮半径（到胎面中线）
const TUBE = 0.029;   // 胎管半径
const LEAN = -0.16;   // 靠背后倾（rad，约 9.2°）

export const WHEELCHAIR_SPEC = Object.freeze({
  unit: 'm',
  axes: { forward: '+Z', up: '+Y', left: '+X', origin: '地面 · 两后轮轴心中点' },

  rearWheel: {
    radius: R,                 // 胎面中线半径
    tube: TUBE,                // 胎管半径
    treadRadius: R + TUBE,     // 外缘 0.310 → 直径 0.62 m
    width: TUBE * 2,           // 0.058
    spokes: 10,
    track: 0.644,              // 左右轮距（轴心 x = ±0.322）
    axleY: R + TUBE,           // 轴心离地高 = 外缘半径 0.310
    axleZ: -0.155,
    handrimRadius: R - 0.026,  // 手推圈半径 0.255（在胎圈内侧）
    handrimTube: 0.012,
  },

  caster: {
    radius: 0.062,             // 胎面中线
    tube: 0.021,
    treadRadius: 0.083,        // 外缘直径 0.166 m
    track: 0.476,              // x = ±0.238
    pivotY: 0.16,              // 转向轴高度
    axleZ: 0.315,
    forkDrop: 0.077,           // 轮心相对转向轴的落差（= pivotY − treadRadius）
  },

  seat: { width: 0.50, depth: 0.44, topY: 0.550, cushion: 0.075 },
  backrest: { width: 0.45, height: 0.44, centerY: 0.79, tiltRad: LEAN, tiltDeg: 9.2 },
  armrest: { topY: 0.735, width: 0.065, length: 0.36, track: 0.570 },
  footrest: { plateY: 0.156, plateDepth: 0.24, plateWidth: 0.15, frontZ: 0.415 },
  pushHandle: { topY: 1.065, z: -0.50, track: 0.470 },

  // 实测值（buildWheelchair 里用 Box3 量出来的，package 内 dimensions.json 同源）
  overall: { length: 1.037, width: 0.702, height: 1.084, wheelbase: 0.470, mass: '—' },
});

/* ══════════════════════ 构建 ══════════════════════ */

const V3 = (x, y, z) => new THREE.Vector3(x, y, z);

/**
 * @param {ReturnType<import('./materials.js').makeMaterials>} M
 * @param {{shadows?: boolean}} [opts]
 * @returns {{group: THREE.Group, parts: Record<string, any>, spec: typeof WHEELCHAIR_SPEC}}
 */
export function buildWheelchair(M, opts = {}) {
  const shadows = opts.shadows !== false;

  const chair = new THREE.Group();
  chair.name = 'wheelchair';
  chair.rotation.order = 'YXZ';

  const parts = {};

  /** 建一个具名子组，挂在车身上；只有顶层部件会进 parts 索引 */
  const group = (name, parent = chair) => {
    const g = new THREE.Group();
    g.name = name;
    parent.add(g);
    if (parent === chair) parts[name] = g;
    return g;
  };

  const add = (parent, mesh, name) => {
    if (shadows) mesh.castShadow = true;
    mesh.receiveShadow = shadows;
    if (name) mesh.name = name;
    parent.add(mesh);
    return mesh;
  };

  const box = (parent, w, h, d, mat, x, y, z, name, rx = 0, ry = 0, rz = 0) => {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
    m.position.set(x, y, z);
    m.rotation.set(rx, ry, rz);
    return add(parent, m, name);
  };

  /** 两点之间的一根圆管 */
  const tube = (parent, p1, p2, r, mat, name) => {
    const a = V3(...p1);
    const b = V3(...p2);
    const dir = new THREE.Vector3().subVectors(b, a);
    const len = dir.length();
    const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, len, 12), mat);
    m.position.copy(a).addScaledVector(dir, 0.5);
    m.quaternion.setFromUnitVectors(V3(0, 1, 0), dir.normalize());
    return add(parent, m, name);
  };

  /* ——— 后轮 ——— */
  const makeWheel = (radius, tubeR, spokes, name) => {
    const g = group(name);

    const tire = new THREE.Mesh(new THREE.TorusGeometry(radius, tubeR, 12, 44), M.tire);
    tire.rotation.y = Math.PI / 2;
    add(g, tire, 'tire');

    const rimR = radius - tubeR * 2.1;
    const rim = new THREE.Mesh(new THREE.TorusGeometry(rimR, 0.011, 8, 44), M.metal);
    rim.rotation.y = Math.PI / 2;
    add(g, rim, 'rim');

    // 轮毂轴向沿 X，长度压在胎宽以内，避免从侧面凸出来
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(radius * 0.21, radius * 0.21, tubeR * 1.8, 18), M.metal);
    hub.rotation.z = Math.PI / 2;
    add(g, hub, 'hub');

    const cap = new THREE.Mesh(new THREE.CylinderGeometry(radius * 0.1, radius * 0.1, tubeR * 1.9, 16), M.accent);
    cap.rotation.z = Math.PI / 2;
    add(g, cap, 'hubCap');

    for (let i = 0; i < spokes; i++) {
      const holder = new THREE.Group();
      holder.rotation.x = (i / spokes) * Math.PI * 2;
      const sp = new THREE.Mesh(new THREE.CylinderGeometry(0.0052, 0.0052, radius * 0.94, 6), M.metal);
      sp.position.y = radius * 0.47;
      holder.add(sp);
      g.add(holder);
    }

    // 手推圈：装在胎圈内侧（半径小于胎面外缘），再用短连杆接到轮圈
    const handR = radius - 0.026;
    const handrim = new THREE.Mesh(new THREE.TorusGeometry(handR, 0.012, 8, 44), M.metalDark);
    handrim.rotation.y = Math.PI / 2;
    add(g, handrim, 'handrim');

    const rodLen = Math.max(0.012, handR - rimR + 0.004);
    for (let i = 0; i < 6; i++) {
      const a = (i / 6) * Math.PI * 2;
      const rod = new THREE.Mesh(new THREE.CylinderGeometry(0.006, 0.006, rodLen, 6), M.metalDark);
      const rr = (handR + rimR) / 2;
      rod.position.set(0, Math.cos(a) * rr, Math.sin(a) * rr);
      rod.rotation.x = a;
      g.add(rod);
    }

    return g;
  };

  // 轴心抬到胎面外缘半径处，轮子才真正落在路面上
  const AXLE_Y = R + TUBE;

  const wheelL = makeWheel(R, TUBE, 10, 'rearWheelL');
  wheelL.position.set(-0.322, AXLE_Y, -0.155);

  const wheelR = makeWheel(R, TUBE, 10, 'rearWheelR');
  wheelR.position.set(0.322, AXLE_Y, -0.155);

  /* ——— 前万向轮 ——— */
  const makeCaster = name => {
    const pivot = group(name);

    const fork = group('fork', pivot);
    const stem = new THREE.Mesh(new THREE.CylinderGeometry(0.016, 0.016, 0.12, 10), M.metal);
    stem.position.y = 0.06;
    add(fork, stem, 'stem');

    for (const sx of [-0.036, 0.036]) {
      const plate = new THREE.Mesh(new THREE.BoxGeometry(0.012, 0.1, 0.05), M.metalDark);
      plate.position.set(sx, -0.03, 0);
      add(fork, plate, 'forkPlate');
    }

    const w = group('casterWheel', fork);
    const tire = new THREE.Mesh(new THREE.TorusGeometry(0.062, 0.021, 10, 26), M.tire);
    tire.rotation.y = Math.PI / 2;
    add(w, tire, 'tire');
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, 0.056, 12), M.accent);
    hub.rotation.z = Math.PI / 2;
    add(w, hub, 'hub');
    // 轮心相对转向轴的落差：轴高 0.160 − 胎面外缘半径 0.083
    w.position.y = -0.077;

    pivot.userData.wheel = w; // 兼容旧接口
    return pivot;
  };

  const casterL = makeCaster('casterL');
  casterL.position.set(-0.238, 0.16, 0.315);

  const casterR = makeCaster('casterR');
  casterR.position.set(0.238, 0.16, 0.315);

  /* ——— 车架 ——— */
  const frame = group('frame');
  tube(frame, [-0.30, 0.30, -0.16], [-0.235, 0.145, 0.30], 0.017, M.metal, 'railL');
  tube(frame, [0.30, 0.30, -0.16], [0.235, 0.145, 0.30], 0.017, M.metal, 'railR');
  tube(frame, [-0.30, 0.30, -0.16], [-0.30, 0.46, -0.16], 0.017, M.metal, 'riserL');
  tube(frame, [0.30, 0.30, -0.16], [0.30, 0.46, -0.16], 0.017, M.metal, 'riserR');

  // 座椅底框 + 坐垫
  const seat = group('seat');
  box(seat, 0.54, 0.045, 0.48, M.metalDark, 0, 0.455, 0.02, 'seatFrame');
  box(seat, 0.50, 0.075, 0.44, M.seat, 0, 0.512, 0.03, 'cushion');
  box(seat, 0.50, 0.05, 0.05, M.seat, 0, 0.498, 0.245, 'cushionFrontEdge');

  // 靠背（后倾 9.2°）
  const backrest = group('backrest');
  box(backrest, 0.50, 0.50, 0.04, M.metal, 0, 0.78, -0.235, 'backPlate', LEAN, 0, 0);
  box(backrest, 0.45, 0.44, 0.055, M.seat, 0, 0.79, -0.205, 'backPad', LEAN, 0, 0);
  tube(backrest, [-0.26, 1.005, -0.275], [0.26, 1.005, -0.275], 0.015, M.metal, 'topRail');
  tube(backrest, [-0.26, 0.50, -0.225], [-0.26, 1.005, -0.275], 0.016, M.metal, 'postL');
  tube(backrest, [0.26, 0.50, -0.225], [0.26, 1.005, -0.275], 0.016, M.metal, 'postR');

  // 扶手
  for (const [sx, side] of [[-1, 'L'], [1, 'R']]) {
    const arm = group('armrest' + side);
    box(arm, 0.065, 0.04, 0.36, M.metalDark, sx * 0.285, 0.715, 0.015, 'pad');
    tube(arm, [sx * 0.285, 0.70, -0.155], [sx * 0.285, 0.52, -0.16], 0.013, M.metal, 'stayRear');
    tube(arm, [sx * 0.285, 0.70, 0.18], [sx * 0.27, 0.50, 0.21], 0.013, M.metal, 'stayFront');
  }

  // 脚踏
  for (const [sx, side] of [[-1, 'L'], [1, 'R']]) {
    const fr = group('footrest' + side);
    tube(fr, [sx * 0.235, 0.46, 0.24], [sx * 0.15, 0.155, 0.40], 0.014, M.metal, 'hanger');
    box(fr, 0.15, 0.022, 0.24, M.metalDark, sx * 0.145, 0.145, 0.415, 'plate');
    box(fr, 0.13, 0.16, 0.02, M.seat, sx * 0.145, 0.24, 0.315, 'calfPad', -0.12, 0, 0);
  }

  // 推手
  for (const [sx, side] of [[-1, 'L'], [1, 'R']]) {
    const h = group('pushHandle' + side);
    tube(h, [sx * 0.235, 0.99, -0.275], [sx * 0.235, 1.065, -0.40], 0.014, M.metal, 'arm');
    tube(h, [sx * 0.235, 1.065, -0.40], [sx * 0.235, 1.055, -0.50], 0.019, M.tire, 'grip');
  }

  // 控制器（右扶手）
  const ctrl = group('controller');
  box(ctrl, 0.105, 0.035, 0.155, M.dark, 0.285, 0.752, 0.10, 'housing');
  const stick = new THREE.Mesh(new THREE.CylinderGeometry(0.007, 0.009, 0.07, 8), M.metal);
  stick.position.set(0.285, 0.80, 0.10);
  add(ctrl, stick, 'stick');
  const knob = new THREE.Mesh(new THREE.SphereGeometry(0.021, 14, 12), M.accent);
  knob.position.set(0.285, 0.838, 0.10);
  add(ctrl, knob, 'knob');
  const led = new THREE.Mesh(new THREE.CylinderGeometry(0.008, 0.008, 0.004, 10), M.accent);
  led.position.set(0.285, 0.771, 0.16);
  add(ctrl, led, 'led');

  // 电池包
  const battery = group('battery');
  box(battery, 0.34, 0.09, 0.22, M.dark, 0, 0.345, -0.06, 'pack');
  const glowStrip = new THREE.Mesh(new THREE.BoxGeometry(0.30, 0.012, 0.006), M.accent);
  glowStrip.position.set(0, 0.345, 0.047);
  add(battery, glowStrip, 'glow');

  // 侧面青色饰条
  for (const sx of [-1, 1]) {
    const strip = new THREE.Mesh(new THREE.BoxGeometry(0.006, 0.016, 0.30), M.accent);
    strip.position.set(sx * 0.305, 0.40, 0.06);
    add(chair, strip, 'sideStrip');
  }

  chair.traverse(o => {
    if (o.isMesh) {
      if (shadows) o.castShadow = true;
      o.receiveShadow = shadows;
    }
  });

  // 实测包围盒，供文档 / 其它引擎核对
  const bbox = new THREE.Box3().setFromObject(chair);
  const size = new THREE.Vector3();
  bbox.getSize(size);

  Object.assign(parts, {
    rearWheelL: wheelL,
    rearWheelR: wheelR,
    casterL,
    casterR,
    dims: { width: size.x, height: size.y, length: size.z },
  });

  chair.userData.parts = parts;
  chair.userData.spec = WHEELCHAIR_SPEC;

  return { group: chair, parts, spec: WHEELCHAIR_SPEC };
}

export default buildWheelchair;
