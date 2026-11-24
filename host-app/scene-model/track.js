/**
 * 测试跑道：地形数据 + 纯数学查询函数 + 网格构建。
 *
 * 坐标系：+Z 为前进方向，+Y 向上，+X 为驾驶员左手侧。单位：米。
 * 分段按 PRD 的 FR-03 铺成一条 93 m 跑道，坡度用高度差反推，保证视觉与物理同一套数。
 */
import * as THREE from 'three';
import { PALETTE, makeLabel } from './materials.js';

export const TRACK_W = 8.0;
export const TRACK_LEN = 93;

/** 每一段跑道的起止 z、起止高度、名称与配色 */
export const SEGMENTS = [
  { id: 'flat',  z0: 0,  z1: 25, h0: 0,    h1: 0,    name: '平地',        tint: PALETTE.accent },
  { id: 'pit',   z0: 25, z1: 35, h0: 0,    h1: 0,    name: '坑地',        tint: PALETTE.accentWarm,
    pit: { z0: 27.6, z1: 31.6, x0: -1.9, x1: 1.9, depth: 0.18 } },
  { id: 'flat2', z0: 35, z1: 45, h0: 0,    h1: 0,    name: '平地',        tint: PALETTE.accent },
  { id: 'up',    z0: 45, z1: 58, h0: 0,    h1: 2.34, name: '爬坡 10.2°',  tint: PALETTE.blue },
  { id: 'top',   z0: 58, z1: 64, h0: 2.34, h1: 2.34, name: '坡顶平台',    tint: PALETTE.blue },
  { id: 'down',  z0: 64, z1: 77, h0: 2.34, h1: 0,    name: '下坡 −10.2°', tint: PALETTE.violet },
  { id: 'flat3', z0: 77, z1: 86, h0: 0,    h1: 0,    name: '终点平地',    tint: PALETTE.accent },
  { id: 'steep', z0: 86, z1: 93, h0: 0,    h1: 2.27, name: '陡坡 18.0°',  tint: PALETTE.accentRed },
];

/** z 所在的分段 */
export function zoneAt(z) {
  for (const s of SEGMENTS) if (z <= s.z1) return s;
  return SEGMENTS[SEGMENTS.length - 1];
}

/** 地面高度（线性插值，界限外夹紧） */
export function heightAt(z) {
  const zc = Math.max(0, Math.min(TRACK_LEN, z));
  const s = zoneAt(zc);
  const t = (zc - s.z0) / (s.z1 - s.z0);
  return s.h0 + (s.h1 - s.h0) * Math.max(0, Math.min(1, t));
}

/** 坡度（弧度，上坡为正） */
export function slopeRadAt(z) {
  const zc = Math.max(0, Math.min(TRACK_LEN, z));
  const s = zoneAt(zc);
  return Math.atan2(s.h1 - s.h0, s.z1 - s.z0);
}

/** z 落在坑里则返回坑的尺寸参数，否则 null */
export function pitAt(z) {
  for (const s of SEGMENTS) {
    if (s.pit && z >= s.pit.z0 - 0.001 && z <= s.pit.z1 + 0.001) return s.pit;
  }
  return null;
}

/* ══════════════════════ 网格构建 ══════════════════════ */

/**
 * 一块贴合地形高度的四边形路面。
 * UV 按世界坐标 / 2 计算，让网格贴图在整条跑道上连续，不随分段重启。
 */
function quad(scene, x0, x1, z0, z1, yFn, mat) {
  const w = Math.abs(x1 - x0);
  const l = Math.abs(z1 - z0);
  if (l < 0.001) return null;
  const geo = new THREE.PlaneGeometry(w, l, 1, Math.max(1, Math.ceil(l / 2)));
  geo.rotateX(-Math.PI / 2);
  geo.translate((x0 + x1) / 2, 0, (z0 + z1) / 2);

  const pos = geo.attributes.position;
  const uv = geo.attributes.uv;
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const z = pos.getZ(i);
    pos.setY(i, yFn(z));
    uv.setXY(i, x / 2, z / 2);
  }
  pos.needsUpdate = true;
  uv.needsUpdate = true;
  geo.computeVertexNormals();

  const mesh = new THREE.Mesh(geo, mat);
  mesh.receiveShadow = true;
  scene.add(mesh);
  return mesh;
}

/**
 * 铺整条跑道：路面、坑、路缘、发光条、分区色带、标签、里程桩。
 * @returns {{segments: THREE.Group}}
 */
export function buildTrack(scene, mat) {
  const root = new THREE.Group();
  root.name = 'track';
  scene.add(root);

  const add = o => { root.add(o); return o; };

  for (const s of SEGMENTS) {
    if (s.pit) {
      const p = s.pit;
      // 坑四周的地面（把矩形挖空）
      quad(root, -TRACK_W / 2, TRACK_W / 2, s.z0, p.z0, heightAt, mat.floor);
      quad(root, -TRACK_W / 2, TRACK_W / 2, p.z1, s.z1, heightAt, mat.floor);
      quad(root, -TRACK_W / 2, p.x0, p.z0, p.z1, heightAt, mat.floor);
      quad(root, p.x1, TRACK_W / 2, p.z0, p.z1, heightAt, mat.floor);

      // 坑底 + 四壁
      quad(root, p.x0, p.x1, p.z0, p.z1, () => -p.depth, mat.pit);

      const wallMat = new THREE.MeshStandardMaterial({ color: 0x0a1018, roughness: 1 });
      const mkWall = (w, d, x, z) => {
        const m = new THREE.Mesh(new THREE.BoxGeometry(w, p.depth, d), wallMat);
        m.position.set(x, -p.depth / 2, z);
        m.receiveShadow = true;
        add(m);
      };
      mkWall(0.08, p.z1 - p.z0, p.x0, (p.z0 + p.z1) / 2);
      mkWall(0.08, p.z1 - p.z0, p.x1, (p.z0 + p.z1) / 2);
      mkWall(p.x1 - p.x0, 0.08, 0, p.z0);
      mkWall(p.x1 - p.x0, 0.08, 0, p.z1);

      // 坑沿警示带
      const band = mat.hazard.clone();
      if (mat.hazard.map) {
        band.map = mat.hazard.map.clone();
        band.map.repeat.set(3, 0.35);
        band.map.needsUpdate = true;
      }
      const mkBand = (w, d, x, z) => {
        const m = new THREE.Mesh(new THREE.PlaneGeometry(w, d), band);
        m.rotation.x = -Math.PI / 2;
        m.position.set(x, 0.012, z);
        add(m);
      };
      mkBand(p.x1 - p.x0 + 0.5, 0.34, 0, p.z0 - 0.17);
      mkBand(p.x1 - p.x0 + 0.5, 0.34, 0, p.z1 + 0.17);
      mkBand(0.34, p.z1 - p.z0, p.x0 - 0.17, (p.z0 + p.z1) / 2);
      mkBand(0.34, p.z1 - p.z0, p.x1 + 0.17, (p.z0 + p.z1) / 2);
    } else {
      quad(root, -TRACK_W / 2, TRACK_W / 2, s.z0, s.z1, heightAt, mat.floor);
    }

    // 两侧路缘 + 发光条
    const len = Math.hypot(s.z1 - s.z0, s.h1 - s.h0) + 0.02;
    const ang = Math.atan2(s.h1 - s.h0, s.z1 - s.z0);

    // 有高差的段补一块侧裙，让坡道从侧面看得出是「抬起来的」
    if (Math.abs(s.h1 - s.h0) > 0.1) {
      const skirtMat = new THREE.MeshStandardMaterial({ color: 0x0b131e, roughness: 0.95, metalness: 0.1, side: THREE.DoubleSide });
      for (const side of [-1, 1]) {
        const geo = new THREE.BufferGeometry();
        const x = side * (TRACK_W / 2);
        const verts = new Float32Array([
          x, s.h0, s.z0,
          x, s.h0 - 0.7, s.z0,
          x, s.h1 - 0.7, s.z1,
          x, s.h0, s.z0,
          x, s.h1 - 0.7, s.z1,
          x, s.h1, s.z1,
        ]);
        geo.setAttribute('position', new THREE.BufferAttribute(verts, 3));
        geo.computeVertexNormals();
        const m = new THREE.Mesh(geo, skirtMat);
        m.receiveShadow = true;
        add(m);
      }
    }

    for (const side of [-1, 1]) {
      const curb = new THREE.Mesh(new THREE.BoxGeometry(0.18, 0.14, len), mat.curb);
      curb.position.set(side * (TRACK_W / 2 - 0.09), (s.h0 + s.h1) / 2 + 0.07, (s.z0 + s.z1) / 2);
      curb.rotation.x = -ang;
      curb.castShadow = true;
      curb.receiveShadow = true;
      add(curb);

      const glowMat = new THREE.MeshBasicMaterial({ color: s.tint, transparent: true, opacity: 0.55 });
      const glow = new THREE.Mesh(new THREE.BoxGeometry(0.035, 0.02, len * 0.995), glowMat);
      glow.position.set(side * (TRACK_W / 2 - 0.09), (s.h0 + s.h1) / 2 + 0.145, (s.z0 + s.z1) / 2);
      glow.rotation.x = -ang;
      add(glow);
    }

    // 分区色带
    if (s.z0 > 0.5) {
      const line = new THREE.Mesh(
        new THREE.PlaneGeometry(TRACK_W - 0.4, 0.16),
        new THREE.MeshBasicMaterial({ color: s.tint, transparent: true, opacity: 0.5 })
      );
      line.rotation.x = -Math.PI / 2;
      line.position.set(0, heightAt(s.z0 + 0.2) + 0.014, s.z0 + 0.2);
      add(line);
    }

    // 场景标签
    const mid = (s.z0 + s.z1) / 2;
    const label = makeLabel(s.pit ? '⚠ 坑地' : s.name, '#' + s.tint.toString(16).padStart(6, '0'));
    label.position.set(TRACK_W / 2 - 0.85, heightAt(mid) + 2.0, mid);
    add(label);

    // 里程桩
    for (let z = Math.ceil(s.z0 / 10) * 10; z < s.z1; z += 10) {
      if (z === 0) continue;
      const py = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.82, 0.1), mat.metalDark);
      py.position.set(TRACK_W / 2 - 0.55, heightAt(z) + 0.41, z);
      py.castShadow = true;
      add(py);
      const cap = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.06, 0.12), mat.accent);
      cap.position.set(TRACK_W / 2 - 0.55, heightAt(z) + 0.84, z);
      add(cap);
      const tag = makeLabel(`${z} m`, '#6f8bab', 44);
      tag.scale.multiplyScalar(0.62);
      tag.position.set(TRACK_W / 2 - 1.15, heightAt(z) + 0.86, z);
      add(tag);
    }
  }

  return { group: root };
}

/** 空气中的浮尘 + 起点门架 */
export function buildProps(scene, mat) {
  const root = new THREE.Group();
  root.name = 'props';
  scene.add(root);

  // 浮尘，增强纵深
  const N = 340;
  const pos = new Float32Array(N * 3);
  for (let i = 0; i < N; i++) {
    pos[i * 3] = (Math.random() - 0.5) * 26;
    pos[i * 3 + 1] = Math.random() * 7 + 0.4;
    pos[i * 3 + 2] = Math.random() * TRACK_LEN;
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const pmat = new THREE.PointsMaterial({
    color: 0x63c9bd, size: 0.035, transparent: true, opacity: 0.45,
    depthWrite: false, blending: THREE.AdditiveBlending,
  });
  const dust = new THREE.Points(geo, pmat);
  dust.name = 'dust';
  root.add(dust);

  // 起点门架
  for (const x of [-TRACK_W / 2 + 0.4, TRACK_W / 2 - 0.4]) {
    const post = new THREE.Mesh(new THREE.BoxGeometry(0.16, 2.6, 0.16), mat.metalDark);
    post.position.set(x, 1.3, 2);
    post.castShadow = true;
    root.add(post);
  }
  const beam = new THREE.Mesh(new THREE.BoxGeometry(TRACK_W - 0.5, 0.12, 0.16), mat.metal);
  beam.position.set(0, 2.58, 2);
  root.add(beam);
  const strip = new THREE.Mesh(new THREE.BoxGeometry(TRACK_W - 1.2, 0.02, 0.02), mat.accent);
  strip.position.set(0, 2.5, 2);
  root.add(strip);

  const startLabel = makeLabel('起点 · 测试跑道 93 m', '#8fe9dc', 46);
  startLabel.scale.multiplyScalar(0.85);
  startLabel.position.set(0, 2.95, 2);
  root.add(startLabel);

  return { group: root, dust };
}
