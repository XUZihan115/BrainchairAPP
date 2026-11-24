#!/usr/bin/env node
/**
 * 导出 wheelchair.glb + dimensions.json（只在模型改了之后才需要跑）。
 *
 * 用法（在 scene-model 目录里）：
 *   npm init -y && npm i three@0.160.0     # 一次性，node_modules 不入库
 *   node tools/export-glb.mjs
 *
 * 为什么不用浏览器导出：这里走 Node + GLTFExporter，无 DOM、无 canvas，
 * 所以材质一律走纯色（makeMaterials({ textures:false })），GLB 里不带贴图。
 */

// Node 没有 FileReader；GLTFExporter 用它把 Blob 读成 ArrayBuffer（二进制导出必需）
if (typeof globalThis.FileReader === 'undefined') {
  globalThis.FileReader = class FileReaderPolyfill {
    readAsArrayBuffer(blob) {
      blob.arrayBuffer().then(buf => {
        this.result = buf;
        if (this.onload) this.onload({ target: this });
        if (this.onloadend) this.onloadend({ target: this });
      });
    }
    readAsDataURL() { throw new Error('readAsDataURL 未实现（本包不导出贴图）'); }
  };
}

import * as THREE from 'three';
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeMaterials } from '../materials.js';
import { buildWheelchair, WHEELCHAIR_SPEC } from '../wheelchair.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(here, '..');

const round = n => Math.round(n * 1e6) / 1e6;

const { group } = buildWheelchair(makeMaterials({ textures: false }), { shadows: false });
group.position.set(0, 0, 0);
group.updateMatrixWorld(true);

const box = new THREE.Box3().setFromObject(group);
const size = new THREE.Vector3();
box.getSize(size);

let tri = 0;
group.traverse(o => {
  if (!o.isMesh || !o.geometry) return;
  const g = o.geometry;
  tri += (g.index ? g.index.count : g.attributes.position.count) / 3;
});

const exporter = new GLTFExporter();
const glb = await exporter.parseAsync(group, { binary: true, onlyVisible: true });

const glbPath = path.join(outDir, 'wheelchair.glb');
fs.writeFileSync(glbPath, Buffer.from(glb));

// 把浮点噪声修掉，尺寸表要能直接抄
const cleanSpec = JSON.parse(JSON.stringify(WHEELCHAIR_SPEC, (k, v) => (typeof v === 'number' ? round(v) : v)));

const dims = {
  source: 'scene-model/wheelchair.js · WHEELCHAIR_SPEC',
  license: '与仓库一致；Three.js 为 MIT',
  unit: 'm',
  axes: WHEELCHAIR_SPEC.axes,
  measured: {
    length_z: round(size.z),
    width_x: round(size.x),
    height_y: round(size.y),
    min_y: round(box.min.y),
  },
  triangles: Math.round(tri),
  spec: cleanSpec,
  parts: {
    rearWheelL: '左后轮（轮胎 / 轮圈 / 轮毂 / 10 根辐条 / 手推圈）',
    rearWheelR: '右后轮',
    casterL: '左前万向轮（转向轴 + 叉架 + 轮）',
    casterR: '右前万向轮',
    frame: '车架管件（4 根）',
    seat: '座框 + 坐垫 + 前缘',
    backrest: '靠背（后倾 9.2°）+ 顶部横管 + 立柱',
    armrestL: '左扶手（垫 + 两根支撑）',
    armrestR: '右扶手',
    footrestL: '左脚踏（吊架 + 踏板 + 小腿挡板）',
    footrestR: '右脚踏',
    pushHandleL: '左推手',
    pushHandleR: '右推手',
    controller: '控制器外壳 + 摇杆 + 摇杆球 + 指示灯',
    battery: '电池包 + 发光条',
  },
  animation: {
    rearWheelSpin: 'rearWheelL / rearWheelR 绕自身 X 轴，角速度 = v / 0.281 rad/s',
    casterSteer: 'casterL / casterR 绕 Y 轴，角度 = steer（rad）',
    casterSpin: 'casterL/R 内部 casterWheel 绕 X 轴，转速 = 后轮 × 3.4',
    pitch: '整车绕 X 轴，角度 = −坡道倾角；rotation.order = YXZ（先偏航后俯仰）',
    note: '轮胎半径 0.281 是胎面中线，外缘 0.310；轴心高度按外缘算，所以轮子刚好落地',
  },
};

fs.writeFileSync(path.join(outDir, 'dimensions.json'), JSON.stringify(dims, null, 2) + '\n');

console.log(`glb   ${glbPath}  ${(fs.statSync(glbPath).size / 1024).toFixed(1)} KB`);
console.log(`dims  L ${size.z.toFixed(3)}  W ${size.x.toFixed(3)}  H ${size.y.toFixed(3)}  ·  ${Math.round(tri)} 三角面`);
