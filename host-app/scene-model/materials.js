/**
 * 材质与贴图工厂。
 *
 * 这里只做「颜色 + 纹理」，不依赖渲染器 —— 渲染器相关的环境贴图在 world.js 里。
 * 传 { textures: false } 可以完全跳过 canvas，方便在 Node 里导出 GLB。
 */
import * as THREE from 'three';

/** 与 A 端 Android 的 colors.xml 对齐的调色板 */
export const PALETTE = {
  accent: 0x38e1c8, // 主色 青
  accentWarm: 0xf0b429, // 警示 黄
  accentRed: 0xff6b68, // 危险 红
  blue: 0x6ea8ff,
  violet: 0x9c7bff,
  bg: 0x070d16,
  floor: 0x0e1a29,
  metal: 0x3d4c60,
  metalDark: 0x22303f,
  tire: 0x0e1218,
  seat: 0x2b4562,
  dark: 0x121a26,
  curb: 0x162436,
};

function floorTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 256;
  const g = c.getContext('2d');
  g.fillStyle = '#0e1a29';
  g.fillRect(0, 0, 256, 256);

  // 细噪点，避免大面积纯色
  for (let i = 0; i < 900; i++) {
    g.fillStyle = `rgba(140,180,220,${Math.random() * 0.035})`;
    g.fillRect(Math.random() * 256, Math.random() * 256, 1.4, 1.4);
  }

  // 主网格 2 m
  g.strokeStyle = 'rgba(56,225,200,0.16)';
  g.lineWidth = 2;
  g.strokeRect(0, 0, 256, 256);
  // 次网格 1 m
  g.strokeStyle = 'rgba(140,170,210,0.07)';
  g.lineWidth = 1;
  g.beginPath();
  g.moveTo(128, 0); g.lineTo(128, 256);
  g.moveTo(0, 128); g.lineTo(256, 128);
  g.stroke();

  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.anisotropy = 8;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

function hazardTexture() {
  const c = document.createElement('canvas');
  c.width = 128; c.height = 128;
  const g = c.getContext('2d');
  g.fillStyle = '#151b26';
  g.fillRect(0, 0, 128, 128);
  g.strokeStyle = '#f0b429';
  g.lineWidth = 16;
  for (let i = -128; i < 256; i += 48) {
    g.beginPath();
    g.moveTo(i, 0);
    g.lineTo(i + 128, 128);
    g.stroke();
  }
  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/**
 * @param {{textures?: boolean}} [opts] textures=false 时不生成 canvas 贴图（Node / 无 DOM 环境）
 */
export function makeMaterials(opts = {}) {
  const useTex = opts.textures !== false && typeof document !== 'undefined';

  const floor = new THREE.MeshStandardMaterial({
    roughness: 0.86, metalness: 0.16, color: useTex ? 0xffffff : PALETTE.floor,
    emissive: 0x0a1826, emissiveIntensity: 0.9,
  });
  if (useTex) floor.map = floorTexture();

  const hazard = new THREE.MeshStandardMaterial({
    roughness: 0.8, metalness: 0.1, color: useTex ? 0xffffff : PALETTE.accentWarm,
  });
  if (useTex) hazard.map = hazardTexture();

  return {
    floor,
    pit: new THREE.MeshStandardMaterial({ color: 0x05080d, roughness: 1, metalness: 0.0 }),
    curb: new THREE.MeshStandardMaterial({ color: PALETTE.curb, roughness: 0.7, metalness: 0.35 }),
    hazard,
    metal: new THREE.MeshStandardMaterial({ color: PALETTE.metal, metalness: 0.9, roughness: 0.32 }),
    metalDark: new THREE.MeshStandardMaterial({ color: PALETTE.metalDark, metalness: 0.8, roughness: 0.45 }),
    tire: new THREE.MeshStandardMaterial({ color: PALETTE.tire, metalness: 0.05, roughness: 0.96 }),
    seat: new THREE.MeshStandardMaterial({ color: PALETTE.seat, metalness: 0.08, roughness: 0.86 }),
    accent: new THREE.MeshStandardMaterial({
      color: PALETTE.accent, emissive: PALETTE.accent, emissiveIntensity: 1.1,
      metalness: 0.4, roughness: 0.35,
    }),
    accentSoft: new THREE.MeshBasicMaterial({ color: PALETTE.accent, transparent: true, opacity: 0.55 }),
    dark: new THREE.MeshStandardMaterial({ color: PALETTE.dark, metalness: 0.6, roughness: 0.6 }),
  };
}

/**
 * 文字精灵（场景分区标签 / 里程桩）。必须传 canvas 上下文才能量宽度。
 */
export function makeLabel(text, color = '#9fe9dd', px = 56, height = 0.36) {
  const meas = document.createElement('canvas').getContext('2d');
  const font = `600 ${px}px "Microsoft YaHei","PingFang SC",sans-serif`;
  meas.font = font;
  const w = Math.ceil(meas.measureText(text).width) + 48;
  const h = Math.ceil(px * 1.7);

  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const g = c.getContext('2d');
  g.font = font;
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.shadowColor = 'rgba(0,0,0,.85)';
  g.shadowBlur = 14;
  g.fillStyle = color;
  g.fillText(text, w / 2, h / 2);

  const tex = new THREE.CanvasTexture(c);
  tex.anisotropy = 4;
  tex.colorSpace = THREE.SRGBColorSpace;
  const mat = new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false });
  const sp = new THREE.Sprite(mat);
  sp.scale.set(height * w / h, height, 1);
  return sp;
}
