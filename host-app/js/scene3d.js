/**
 * 3D 场景与轮椅模型（Three.js r160，全部程序化建模，无外部资源）。
 *
 * 坐标系：+Z 为前进方向，+Y 向上，+X 为驾驶员左手侧。
 * 场景按 FR-03 铺成一条 93 m 测试跑道：平地 → 坑地 → 平地 → 爬坡 → 坡顶 → 下坡 → 平地 → 陡坡。
 */
import * as THREE from 'three';

const TRACK_W = 8.0;

export const TRACK_LEN = 93;

export const SEGMENTS = [
  { id: 'flat',  z0: 0,  z1: 25, h0: 0,    h1: 0,    name: '平地',        tint: 0x38e1c8 },
  { id: 'pit',   z0: 25, z1: 35, h0: 0,    h1: 0,    name: '坑地',        tint: 0xf0b429,
    pit: { z0: 27.6, z1: 31.6, x0: -1.9, x1: 1.9, depth: 0.18 } },
  { id: 'flat2', z0: 35, z1: 45, h0: 0,    h1: 0,    name: '平地',        tint: 0x38e1c8 },
  { id: 'up',    z0: 45, z1: 58, h0: 0,    h1: 2.34, name: '爬坡 10.2°',  tint: 0x6ea8ff },
  { id: 'top',   z0: 58, z1: 64, h0: 2.34, h1: 2.34, name: '坡顶平台',    tint: 0x6ea8ff },
  { id: 'down',  z0: 64, z1: 77, h0: 2.34, h1: 0,    name: '下坡 −10.2°', tint: 0x9c7bff },
  { id: 'flat3', z0: 77, z1: 86, h0: 0,    h1: 0,    name: '终点平地',    tint: 0x38e1c8 },
  { id: 'steep', z0: 86, z1: 93, h0: 0,    h1: 2.27, name: '陡坡 18.0°',  tint: 0xff6b68 },
];

export function zoneAt(z) {
  for (const s of SEGMENTS) if (z <= s.z1) return s;
  return SEGMENTS[SEGMENTS.length - 1];
}

export function heightAt(z) {
  const zc = Math.max(0, Math.min(TRACK_LEN, z));
  const s = zoneAt(zc);
  const t = (zc - s.z0) / (s.z1 - s.z0);
  return s.h0 + (s.h1 - s.h0) * Math.max(0, Math.min(1, t));
}

export function slopeRadAt(z) {
  const zc = Math.max(0, Math.min(TRACK_LEN, z));
  const s = zoneAt(zc);
  return Math.atan2(s.h1 - s.h0, s.z1 - s.z0);
}

export function pitAt(z) {
  for (const s of SEGMENTS) {
    if (s.pit && z >= s.pit.z0 - 0.001 && z <= s.pit.z1 + 0.001) return s.pit;
  }
  return null;
}

/* ══════════════════════════ 纹理 ══════════════════════════ */

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

function makeLabel(text, color = '#9fe9dd', px = 56) {
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
  const hh = 0.36;
  sp.scale.set(hh * w / h, hh, 1);
  return sp;
}

/* ══════════════════════════ 世界 ══════════════════════════ */

export class World {
  constructor(canvas) {
    this.canvas = canvas;
    this.camMode = 'follow';
    this.orbit = { az: 0, el: 0.25, dist: 3.75 };
    this.time = 0;

    this.renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: true,
      powerPreference: 'high-performance',
      preserveDrawingBuffer: true, // 方便答辩截图 / 录屏抓帧
    });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.08;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x070d16);
    this.scene.fog = new THREE.Fog(0x070d16, 26, 92);

    this.camera = new THREE.PerspectiveCamera(46, 1, 0.05, 400);
    this.camera.position.set(0, 2.6, -6);
    this.camLook = new THREE.Vector3(0, 0.8, 0);

    this._initMaterials();
    this._initEnv();
    this._initLights();
    this._buildTrack();
    this._buildProps();
    this._buildChair();
    this._bindOrbit();

    this.resize();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(canvas.parentElement);
  }

  _initMaterials() {
    const floorTex = floorTexture();
    this.mat = {
      floor: new THREE.MeshStandardMaterial({
        map: floorTex, roughness: 0.86, metalness: 0.16, color: 0xffffff,
        emissive: 0x0a1826, emissiveIntensity: 0.9,
      }),
      pit: new THREE.MeshStandardMaterial({ color: 0x05080d, roughness: 1, metalness: 0.0 }),
      curb: new THREE.MeshStandardMaterial({ color: 0x162436, roughness: 0.7, metalness: 0.35 }),
      hazard: new THREE.MeshStandardMaterial({ map: hazardTexture(), roughness: 0.8, metalness: 0.1 }),
      metal: new THREE.MeshStandardMaterial({ color: 0x3d4c60, metalness: 0.9, roughness: 0.32 }),
      metalDark: new THREE.MeshStandardMaterial({ color: 0x22303f, metalness: 0.8, roughness: 0.45 }),
      tire: new THREE.MeshStandardMaterial({ color: 0x0e1218, metalness: 0.05, roughness: 0.96 }),
      seat: new THREE.MeshStandardMaterial({ color: 0x2b4562, metalness: 0.08, roughness: 0.86 }),
      accent: new THREE.MeshStandardMaterial({ color: 0x38e1c8, emissive: 0x38e1c8, emissiveIntensity: 1.1, metalness: 0.4, roughness: 0.35 }),
      accentSoft: new THREE.MeshBasicMaterial({ color: 0x38e1c8, transparent: true, opacity: 0.55 }),
      dark: new THREE.MeshStandardMaterial({ color: 0x121a26, metalness: 0.6, roughness: 0.6 }),
    };
  }

  /**
   * 程序化环境贴图（等距柱状 → PMREM）。
   * 高 metalness 的材质没有环境反射会渲染成黑块，这里用一条竖向渐变代替 HDR 环境。
   */
  _initEnv() {
    const w = 64, h = 32;
    const data = new Uint8Array(w * h * 4);
    const stops = [
      [0.00, 0x0a1420],
      [0.42, 0x24405c],
      [0.52, 0x8fb4d6],
      [0.60, 0x33506b],
      [1.00, 0x05080d],
    ];
    const lerpC = (a, b, t) => [
      ((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t,
      ((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t,
      (a & 255) + ((b & 255) - (a & 255)) * t,
    ];
    for (let y = 0; y < h; y++) {
      const t = y / (h - 1);
      let i = 0;
      while (i < stops.length - 2 && t > stops[i + 1][0]) i++;
      const [t0, c0] = stops[i];
      const [t1, c1] = stops[i + 1];
      const k = (t - t0) / Math.max(0.0001, t1 - t0);
      const [r, g, b] = lerpC(c0, c1, Math.min(1, Math.max(0, k)));
      for (let x = 0; x < w; x++) {
        const o = (y * w + x) * 4;
        data[o] = r; data[o + 1] = g; data[o + 2] = b; data[o + 3] = 255;
      }
    }
    const tex = new THREE.DataTexture(data, w, h, THREE.RGBAFormat);
    tex.mapping = THREE.EquirectangularReflectionMapping;
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;

    const pmrem = new THREE.PMREMGenerator(this.renderer);
    pmrem.compileEquirectangularShader();
    this.scene.environment = pmrem.fromEquirectangular(tex).texture;
    this.scene.environmentIntensity = 0.85;
    pmrem.dispose();
    tex.dispose();
  }

  _initLights() {
    this.scene.add(new THREE.HemisphereLight(0xc6e2ff, 0x0d1622, 0.55));
    this.scene.add(new THREE.AmbientLight(0x7fa8cf, 0.14));

    const dir = new THREE.DirectionalLight(0xffffff, 2.3);
    dir.position.set(8, 16, 7);
    dir.castShadow = true;
    dir.shadow.mapSize.set(2048, 2048);
    const cam = dir.shadow.camera;
    cam.left = -11; cam.right = 11; cam.top = 13; cam.bottom = -13;
    cam.near = 1; cam.far = 60;
    dir.shadow.bias = -0.0007;
    dir.shadow.normalBias = 0.02;
    this.scene.add(dir, dir.target);
    this.sun = dir;
    this.sunOffset = new THREE.Vector3(7, 15, 8);

    const rim = new THREE.PointLight(0xbdf0e6, 4.5, 7.5, 2);
    rim.position.set(0, 3, 0);
    this.scene.add(rim);
    this.rimLight = rim;

    // 补光：从摄像机一侧打亮靠背，避免跟随视角下轮椅变成黑块
    const back = new THREE.PointLight(0xf2f8ff, 19, 9, 2);
    this.scene.add(back);
    this.backLight = back;

    const fill = new THREE.PointLight(0x4f7dff, 7, 30, 2);
    fill.position.set(0, 4, 46);
    this.scene.add(fill);
  }

  _quad(x0, x1, z0, z1, yFn, mat) {
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
    this.scene.add(mesh);
    return mesh;
  }

  _buildTrack() {
    for (const s of SEGMENTS) {
      if (s.pit) {
        const p = s.pit;
        this._quad(-TRACK_W / 2, TRACK_W / 2, s.z0, p.z0, heightAt, this.mat.floor);
        this._quad(-TRACK_W / 2, TRACK_W / 2, p.z1, s.z1, heightAt, this.mat.floor);
        this._quad(-TRACK_W / 2, p.x0, p.z0, p.z1, heightAt, this.mat.floor);
        this._quad(p.x1, TRACK_W / 2, p.z0, p.z1, heightAt, this.mat.floor);

        // 坑底 + 四壁
        const bottom = this._quad(p.x0, p.x1, p.z0, p.z1, () => -p.depth, this.mat.pit);
        if (bottom) bottom.material = this.mat.pit;

        const wallMat = new THREE.MeshStandardMaterial({ color: 0x0a1018, roughness: 1 });
        const mkWall = (w, d, x, z) => {
          const m = new THREE.Mesh(new THREE.BoxGeometry(w, p.depth, d), wallMat);
          m.position.set(x, -p.depth / 2, z);
          m.receiveShadow = true;
          this.scene.add(m);
        };
        mkWall(0.08, p.z1 - p.z0, p.x0, (p.z0 + p.z1) / 2);
        mkWall(0.08, p.z1 - p.z0, p.x1, (p.z0 + p.z1) / 2);
        mkWall(p.x1 - p.x0, 0.08, 0, p.z0);
        mkWall(p.x1 - p.x0, 0.08, 0, p.z1);

        // 坑沿警示带
        const band = this.mat.hazard.clone();
        band.map = this.mat.hazard.map.clone();
        band.map.repeat.set(3, 0.35);
        band.map.needsUpdate = true;
        const mkBand = (w, d, x, z) => {
          const m = new THREE.Mesh(new THREE.PlaneGeometry(w, d), band);
          m.rotation.x = -Math.PI / 2;
          m.position.set(x, 0.012, z);
          this.scene.add(m);
        };
        mkBand(p.x1 - p.x0 + 0.5, 0.34, 0, p.z0 - 0.17);
        mkBand(p.x1 - p.x0 + 0.5, 0.34, 0, p.z1 + 0.17);
        mkBand(0.34, p.z1 - p.z0, p.x0 - 0.17, (p.z0 + p.z1) / 2);
        mkBand(0.34, p.z1 - p.z0, p.x1 + 0.17, (p.z0 + p.z1) / 2);
      } else {
        this._quad(-TRACK_W / 2, TRACK_W / 2, s.z0, s.z1, heightAt, this.mat.floor);
      }

      // 两侧路缘 + 发光条
      const len = Math.hypot(s.z1 - s.z0, s.h1 - s.h0) + 0.02;
      const ang = Math.atan2(s.h1 - s.h0, s.z1 - s.z0);

      // 有高差的段补一块侧裙，让坡道从侧面看得出是"抬起来的"
      if (Math.abs(s.h1 - s.h0) > 0.1) {
        const skirtMat = new THREE.MeshStandardMaterial({ color: 0x0b131e, roughness: 0.95, metalness: 0.1 });
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
          m.material.side = THREE.DoubleSide;
          m.receiveShadow = true;
          this.scene.add(m);
        }
      }

      for (const side of [-1, 1]) {
        const curb = new THREE.Mesh(new THREE.BoxGeometry(0.18, 0.14, len), this.mat.curb);
        curb.position.set(side * (TRACK_W / 2 - 0.09), (s.h0 + s.h1) / 2 + 0.07, (s.z0 + s.z1) / 2);
        curb.rotation.x = -ang;
        curb.castShadow = true;
        curb.receiveShadow = true;
        this.scene.add(curb);

        const glowMat = new THREE.MeshBasicMaterial({
          color: s.tint,
          transparent: true,
          opacity: 0.55,
        });
        const glow = new THREE.Mesh(new THREE.BoxGeometry(0.035, 0.02, len * 0.995), glowMat);
        glow.position.set(side * (TRACK_W / 2 - 0.09), (s.h0 + s.h1) / 2 + 0.145, (s.z0 + s.z1) / 2);
        glow.rotation.x = -ang;
        this.scene.add(glow);
      }

      // 分区色带
      if (s.z0 > 0.5) {
        const line = new THREE.Mesh(
          new THREE.PlaneGeometry(TRACK_W - 0.4, 0.16),
          new THREE.MeshBasicMaterial({ color: s.tint, transparent: true, opacity: 0.5 })
        );
        line.rotation.x = -Math.PI / 2;
        line.position.set(0, heightAt(s.z0 + 0.2) + 0.014, s.z0 + 0.2);
        this.scene.add(line);
      }

      // 场景标签
      const mid = (s.z0 + s.z1) / 2;
      const label = makeLabel(
        s.pit ? '⚠ 坑地' : s.name,
        '#' + s.tint.toString(16).padStart(6, '0')
      );
      label.position.set(TRACK_W / 2 - 0.85, heightAt(mid) + 2.0, mid);
      this.scene.add(label);

      // 里程桩
      for (let z = Math.ceil(s.z0 / 10) * 10; z < s.z1; z += 10) {
        if (z === 0) continue;
        const py = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.82, 0.1), this.mat.metalDark);
        py.position.set(TRACK_W / 2 - 0.55, heightAt(z) + 0.41, z);
        py.castShadow = true;
        this.scene.add(py);
        const cap = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.06, 0.12), this.mat.accent);
        cap.position.set(TRACK_W / 2 - 0.55, heightAt(z) + 0.84, z);
        this.scene.add(cap);
        const tag = makeLabel(`${z} m`, '#6f8bab', 44);
        tag.scale.multiplyScalar(0.62);
        tag.position.set(TRACK_W / 2 - 1.15, heightAt(z) + 0.86, z);
        this.scene.add(tag);
      }
    }
  }

  _buildProps() {
    // 空气中的浮尘，增强纵深
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
    this.dust = new THREE.Points(geo, pmat);
    this.scene.add(this.dust);

    // 起点门架
    const gate = new THREE.Group();
    for (const x of [-TRACK_W / 2 + 0.4, TRACK_W / 2 - 0.4]) {
      const post = new THREE.Mesh(new THREE.BoxGeometry(0.16, 2.6, 0.16), this.mat.metalDark);
      post.position.set(x, 1.3, 2);
      post.castShadow = true;
      gate.add(post);
    }
    const beam = new THREE.Mesh(new THREE.BoxGeometry(TRACK_W - 0.5, 0.12, 0.16), this.mat.metal);
    beam.position.set(0, 2.58, 2);
    gate.add(beam);
    const strip = new THREE.Mesh(new THREE.BoxGeometry(TRACK_W - 1.2, 0.02, 0.02), this.mat.accent);
    strip.position.set(0, 2.5, 2);
    gate.add(strip);
    this.scene.add(gate);

    const startLabel = makeLabel('起点 · 测试跑道 93 m', '#8fe9dc', 46);
    startLabel.scale.multiplyScalar(0.85);
    startLabel.position.set(0, 2.95, 2);
    this.scene.add(startLabel);
  }

  /* ══════════════════ 轮椅模型 ══════════════════ */

  _buildChair() {
    const M = this.mat;
    const R = 0.281;      // 后轮半径（含胎面）
    const TUBE = 0.029;

    const chair = new THREE.Group();
    chair.rotation.order = 'YXZ';
    this.chair = chair;

    const add = (mesh, cast = true) => {
      if (cast) { mesh.castShadow = true; }
      mesh.receiveShadow = true;
      chair.add(mesh);
      return mesh;
    };

    const box = (w, h, d, mat, x, y, z, rx = 0, ry = 0, rz = 0) => {
      const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
      m.position.set(x, y, z);
      m.rotation.set(rx, ry, rz);
      return add(m);
    };

    const tube = (p1, p2, r, mat) => {
      const a = new THREE.Vector3(...p1);
      const b = new THREE.Vector3(...p2);
      const dir = new THREE.Vector3().subVectors(b, a);
      const len = dir.length();
      const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, len, 12), mat);
      m.position.copy(a).addScaledVector(dir, 0.5);
      m.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.normalize());
      return add(m);
    };

    /* ——— 车轮 ——— */
    const makeWheel = (radius, tubeR, spokes) => {
      const g = new THREE.Group();

      const tire = new THREE.Mesh(new THREE.TorusGeometry(radius, tubeR, 12, 44), M.tire);
      tire.rotation.y = Math.PI / 2;
      tire.castShadow = true;
      g.add(tire);

      const rim = new THREE.Mesh(new THREE.TorusGeometry(radius - tubeR * 2.1, 0.011, 8, 44), M.metal);
      rim.rotation.y = Math.PI / 2;
      g.add(rim);

      const hub = new THREE.Mesh(new THREE.CylinderGeometry(radius * 0.21, radius * 0.21, tubeR * 3.2, 18), M.metal);
      hub.rotation.z = Math.PI / 2;
      g.add(hub);

      const cap = new THREE.Mesh(new THREE.CylinderGeometry(radius * 0.1, radius * 0.1, tubeR * 3.7, 16), M.accent);
      cap.rotation.z = Math.PI / 2;
      g.add(cap);

      for (let i = 0; i < spokes; i++) {
        const holder = new THREE.Group();
        holder.rotation.x = (i / spokes) * Math.PI * 2;
        const sp = new THREE.Mesh(new THREE.CylinderGeometry(0.0052, 0.0052, radius * 0.94, 6), M.metal);
        sp.position.y = radius * 0.47;
        holder.add(sp);
        g.add(holder);
      }

      // 手推圈 + 连接块
      const handrim = new THREE.Mesh(new THREE.TorusGeometry(radius + 0.043, 0.012, 8, 44), M.metalDark);
      handrim.rotation.y = Math.PI / 2;
      g.add(handrim);
      for (let i = 0; i < 6; i++) {
        const a = (i / 6) * Math.PI * 2;
        const rod = new THREE.Mesh(new THREE.CylinderGeometry(0.006, 0.006, 0.045, 6), M.metalDark);
        rod.position.set(0, Math.cos(a) * (radius + 0.021), Math.sin(a) * (radius + 0.021));
        rod.rotation.x = a;
        g.add(rod);
      }

      return g;
    };

    const wheelL = makeWheel(R, TUBE, 10);
    wheelL.position.set(-0.322, R, -0.155);
    chair.add(wheelL);
    this.wheelL = wheelL;

    const wheelR = makeWheel(R, TUBE, 10);
    wheelR.position.set(0.322, R, -0.155);
    chair.add(wheelR);
    this.wheelR = wheelR;

    /* ——— 前万向轮 ——— */
    const makeCaster = () => {
      const pivot = new THREE.Group();
      const fork = new THREE.Group();

      const stem = new THREE.Mesh(new THREE.CylinderGeometry(0.016, 0.016, 0.12, 10), M.metal);
      stem.position.y = 0.06;
      fork.add(stem);

      for (const sx of [-0.036, 0.036]) {
        const plate = new THREE.Mesh(new THREE.BoxGeometry(0.012, 0.1, 0.05), M.metalDark);
        plate.position.set(sx, -0.03, 0);
        plate.castShadow = true;
        fork.add(plate);
      }

      const w = new THREE.Group();
      const tire = new THREE.Mesh(new THREE.TorusGeometry(0.062, 0.021, 10, 26), M.tire);
      tire.rotation.y = Math.PI / 2;
      tire.castShadow = true;
      w.add(tire);
      const hub = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, 0.056, 12), M.accent);
      hub.rotation.z = Math.PI / 2;
      w.add(hub);
      w.position.y = -0.058;
      fork.add(w);

      pivot.add(fork);
      pivot.userData.wheel = w;
      return pivot;
    };

    const casterL = makeCaster();
    casterL.position.set(-0.238, 0.16, 0.315);
    chair.add(casterL);
    this.casterL = casterL;

    const casterR = makeCaster();
    casterR.position.set(0.238, 0.16, 0.315);
    chair.add(casterR);
    this.casterR = casterR;

    /* ——— 车架 ——— */
    tube([-0.30, 0.30, -0.16], [-0.235, 0.145, 0.30], 0.017, M.metal);
    tube([0.30, 0.30, -0.16], [0.235, 0.145, 0.30], 0.017, M.metal);
    tube([-0.30, 0.30, -0.16], [-0.30, 0.46, -0.16], 0.017, M.metal);
    tube([0.30, 0.30, -0.16], [0.30, 0.46, -0.16], 0.017, M.metal);

    // 座椅底框
    box(0.54, 0.045, 0.48, M.metalDark, 0, 0.455, 0.02);
    // 坐垫
    box(0.50, 0.075, 0.44, M.seat, 0, 0.512, 0.03);
    // 坐垫前缘倒角观感
    box(0.50, 0.05, 0.05, M.seat, 0, 0.498, 0.245);

    // 靠背（后倾 9°）
    const lean = -0.16;
    box(0.50, 0.50, 0.04, M.metal, 0, 0.78, -0.235, lean, 0, 0);
    box(0.45, 0.44, 0.055, M.seat, 0, 0.79, -0.205, lean, 0, 0);
    // 靠背顶部横管
    tube([-0.26, 1.005, -0.275], [0.26, 1.005, -0.275], 0.015, M.metal);
    tube([-0.26, 0.50, -0.225], [-0.26, 1.005, -0.275], 0.016, M.metal);
    tube([0.26, 0.50, -0.225], [0.26, 1.005, -0.275], 0.016, M.metal);

    // 扶手
    for (const sx of [-1, 1]) {
      box(0.065, 0.04, 0.36, M.metalDark, sx * 0.285, 0.715, 0.015);
      tube([sx * 0.285, 0.70, -0.155], [sx * 0.285, 0.52, -0.16], 0.013, M.metal);
      tube([sx * 0.285, 0.70, 0.18], [sx * 0.27, 0.50, 0.21], 0.013, M.metal);
    }

    // 脚踏
    for (const sx of [-1, 1]) {
      tube([sx * 0.235, 0.46, 0.24], [sx * 0.15, 0.155, 0.40], 0.014, M.metal);
      box(0.15, 0.022, 0.24, M.metalDark, sx * 0.145, 0.145, 0.415);
      // 小腿挡板
      box(0.13, 0.16, 0.02, M.seat, sx * 0.145, 0.24, 0.315, -0.12, 0, 0);
    }

    // 推手
    for (const sx of [-1, 1]) {
      tube([sx * 0.235, 0.99, -0.275], [sx * 0.235, 1.065, -0.40], 0.014, M.metal);
      tube([sx * 0.235, 1.065, -0.40], [sx * 0.235, 1.055, -0.50], 0.019, M.tire);
    }

    // 控制器（右扶手）
    box(0.105, 0.035, 0.155, M.dark, 0.285, 0.752, 0.10);
    const stick = new THREE.Mesh(new THREE.CylinderGeometry(0.007, 0.009, 0.07, 8), M.metal);
    stick.position.set(0.285, 0.80, 0.10);
    add(stick);
    const knob = new THREE.Mesh(new THREE.SphereGeometry(0.021, 14, 12), M.accent);
    knob.position.set(0.285, 0.838, 0.10);
    add(knob);
    const led = new THREE.Mesh(new THREE.CylinderGeometry(0.008, 0.008, 0.004, 10), M.accent);
    led.position.set(0.285, 0.771, 0.16);
    chair.add(led);

    // 电池包
    box(0.34, 0.09, 0.22, M.dark, 0, 0.345, -0.06);
    const glowStrip = new THREE.Mesh(new THREE.BoxGeometry(0.30, 0.012, 0.006), M.accent);
    glowStrip.position.set(0, 0.345, 0.047);
    chair.add(glowStrip);

    // 侧面青色饰条
    for (const sx of [-1, 1]) {
      const strip = new THREE.Mesh(new THREE.BoxGeometry(0.006, 0.016, 0.30), M.accent);
      strip.position.set(sx * 0.305, 0.40, 0.06);
      chair.add(strip);
    }

    chair.traverse(o => {
      if (o.isMesh) {
        o.castShadow = o.castShadow || true;
        o.receiveShadow = true;
      }
    });

    this.scene.add(chair);

    // 地面光晕
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(0.55, 0.72, 48),
      new THREE.MeshBasicMaterial({
        color: 0x38e1c8, transparent: true, opacity: 0.28,
        side: THREE.DoubleSide, depthWrite: false,
      })
    );
    ring.rotation.x = -Math.PI / 2;
    this.glowRing = ring;
    this.scene.add(ring);

    this.pitchSmooth = 0;
  }

  /* ══════════════════ 交互 ══════════════════ */

  _bindOrbit() {
    const el = this.canvas;
    let dragging = false;
    let lx = 0, ly = 0;

    el.addEventListener('pointerdown', e => {
      dragging = true;
      lx = e.clientX; ly = e.clientY;
      el.setPointerCapture(e.pointerId);
      el.style.cursor = 'grabbing';
    });
    el.addEventListener('pointermove', e => {
      if (!dragging) return;
      const dx = e.clientX - lx;
      const dy = e.clientY - ly;
      lx = e.clientX; ly = e.clientY;
      this.orbit.az -= dx * 0.006;
      this.orbit.el = Math.max(-0.08, Math.min(1.05, this.orbit.el + dy * 0.004));
    });
    const up = e => {
      dragging = false;
      el.style.cursor = 'grab';
      try { el.releasePointerCapture(e.pointerId); } catch (_) {}
    };
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
    el.addEventListener('wheel', e => {
      e.preventDefault();
      this.orbit.dist = Math.max(2.4, Math.min(11, this.orbit.dist + e.deltaY * 0.0032));
    }, { passive: false });
    el.style.cursor = 'grab';
  }

  setCamera(mode) {
    this.camMode = mode;
    this.orbit.az = 0;
    this.orbit.el = mode === 'top' ? 0.9 : 0.30;
  }

  resize() {
    const p = this.canvas.parentElement;
    if (!p) return;
    const w = p.clientWidth || 1;
    const h = p.clientHeight || 1;
    if (w === this._w && h === this._h) return;
    this._w = w;
    this._h = h;

    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    // 竖屏/窄幅时拉远一点，避免轮椅被裁切
    this.camera.fov = w / h < 1.1 ? 54 : 46;
    this.camera.updateProjectionMatrix();

    // setSize 会清空画布；立刻补一帧，避免缩放瞬间出现空白
    if (this.chair) this.render();
  }

  /* ══════════════════ 每帧 ══════════════════ */

  update(dt, s) {
    this.time += dt;
    const y = heightAt(s.z);

    this.chair.position.set(s.x, y, s.z);
    this.chair.rotation.y = s.yaw;

    // 坡度平滑，避免坡口处姿态突跳
    const targetPitch = slopeRadAt(s.z);
    this.pitchSmooth += (targetPitch - this.pitchSmooth) * Math.min(1, dt * 9);
    this.chair.rotation.x = -this.pitchSmooth;

    // 车轮滚动
    const spin = (s.speed * dt) / 0.281;
    this.wheelL.rotation.x += spin;
    this.wheelR.rotation.x += spin;
    this.casterL.userData.wheel.rotation.x += spin * 3.4;
    this.casterR.userData.wheel.rotation.x += spin * 3.4;
    this.casterL.rotation.y = s.steer;
    this.casterR.rotation.y = s.steer;

    // 转向时车身轻微侧倾
    const lean = -s.yawRate * 0.028;
    this.chair.rotation.z += (lean - this.chair.rotation.z) * Math.min(1, dt * 6);

    // 地面光晕
    this.glowRing.position.set(s.x, y + 0.016, s.z);
    const pulse = 0.22 + 0.08 * Math.sin(this.time * 2.2);
    this.glowRing.material.opacity = s.estop ? 0.5 : pulse + Math.min(0.2, Math.abs(s.speed) * 0.14);
    this.glowRing.material.color.setHex(s.estop ? 0xff6b68 : 0x38e1c8);

    // 跟随光照
    this.sun.position.copy(this.chair.position).add(this.sunOffset);
    this.sun.target.position.copy(this.chair.position);
    this.sun.target.updateMatrixWorld();
    this.rimLight.position.set(s.x, y + 1.7, s.z);
    this.backLight.position.set(
      s.x - Math.sin(s.yaw) * 2.4,
      y + 2.35,
      s.z - Math.cos(s.yaw) * 2.4
    );

    // 浮尘缓慢漂移
    this.dust.rotation.y = Math.sin(this.time * 0.05) * 0.04;

    this._updateCamera(dt, s);
  }

  _updateCamera(dt, s) {
    const p = this.chair.position;
    let target = new THREE.Vector3();
    let look = new THREE.Vector3();

    if (this.camMode === 'follow') {
      const az = s.yaw + Math.PI + this.orbit.az;
      const r = this.orbit.dist * Math.cos(this.orbit.el);
      target.set(
        p.x + Math.sin(az) * r,
        p.y + 0.72 + this.orbit.dist * Math.sin(this.orbit.el),
        p.z + Math.cos(az) * r
      );
      look.set(p.x, p.y + 0.62, p.z);
      // 稍微看向车前方，路面信息更多
      look.x += Math.sin(s.yaw) * 1.1;
      look.z += Math.cos(s.yaw) * 1.1;
    } else if (this.camMode === 'top') {
      target.set(p.x, p.y + 6.4 + this.orbit.dist * 0.7, p.z - 3.0);
      look.set(p.x, p.y, p.z + 3.2);
    } else {
      const right = new THREE.Vector3(-Math.cos(s.yaw), 0, Math.sin(s.yaw));
      target.copy(p).addScaledVector(right, 4.6).add(new THREE.Vector3(0, 1.9, 0));
      look.set(p.x, p.y + 0.55, p.z);
    }

    const k = 1 - Math.exp(-dt * 5.2);
    if (!this._camReady) {
      this._camReady = true;
      this.camera.position.copy(target);
      this.camLook.copy(look);
      this.camera.lookAt(look);
      return;
    }
    this.camera.position.lerp(target, k);
    this.camLook.lerp(look, k);
    this.camera.lookAt(this.camLook);
  }

  render() {
    this.renderer.render(this.scene, this.camera);
  }
}
