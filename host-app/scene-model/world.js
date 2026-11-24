/**
 * 场景装配 + 跟随相机（Three.js r160）。
 *
 * 只负责「渲染器 / 光照 / 环境贴图 / 相机」，模型本身来自 wheelchair.js 与 track.js，
 * 所以这一层可以整体替换成你自己的引擎封装，模型代码照样能用。
 */
import * as THREE from 'three';
import { makeMaterials } from './materials.js';
import { buildTrack, buildProps, heightAt, slopeRadAt, TRACK_LEN, TRACK_W, SEGMENTS, zoneAt, pitAt } from './track.js';
import { buildWheelchair, WHEELCHAIR_SPEC } from './wheelchair.js';

export class World {
  constructor(canvas, opts = {}) {
    this.canvas = canvas;
    this.camMode = 'follow';
    this.orbit = { az: 0, el: 0.25, dist: 3.75 };
    this.time = 0;
    this.opts = opts;

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

    this.mat = makeMaterials();
    this._initEnv();
    this._initLights();
    this._buildTrack();
    this._buildProps();
    this._buildChair();
    this._bindOrbit();

    this.resize();
    if (typeof ResizeObserver !== 'undefined' && canvas.parentElement) {
      this.resizeObserver = new ResizeObserver(() => this.resize());
      this.resizeObserver.observe(canvas.parentElement);
    }
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

  _buildTrack() {
    this.track = buildTrack(this.scene, this.mat);
  }

  _buildProps() {
    this.props = buildProps(this.scene, this.mat);
    this.dust = this.props.dust;
  }

  _buildChair() {
    const built = buildWheelchair(this.mat, { shadows: true });
    this.chair = built.group;
    this.parts = built.parts;
    this.wheelL = built.parts.rearWheelL;
    this.wheelR = built.parts.rearWheelR;
    this.casterL = built.parts.casterL;
    this.casterR = built.parts.casterR;
    this.scene.add(this.chair);

    // 地面光晕
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(0.55, 0.72, 48),
      new THREE.MeshBasicMaterial({
        color: 0x38e1c8, transparent: true, opacity: 0.28,
        side: THREE.DoubleSide, depthWrite: false,
      })
    );
    ring.rotation.x = -Math.PI / 2;
    ring.name = 'groundGlow';
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

  /**
   * @param {number} dt 秒
   * @param {{z:number,x:number,yaw:number,speed:number,steer:number,yawRate:number,estop?:boolean}} s
   */
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
    const spin = (s.speed * dt) / WHEELCHAIR_SPEC.rearWheel.radius;
    this.wheelL.rotation.x += spin;
    this.wheelR.rotation.x += spin;
    this.casterL.userData.wheel.rotation.x += spin * 3.4;
    this.casterR.userData.wheel.rotation.x += spin * 3.4;
    this.casterL.rotation.y = s.steer;
    this.casterR.rotation.y = s.steer;

    // 转向时车身轻微侧倾
    const lean = -s.yawRate * 0.028;
    this.chair.rotation.z += (lean - this.chair.rotation.z) * Math.min(1, dt * 6);

    // 地面光晕（跟着坡度一起倾斜，否则上坡时会被路面切掉一半）
    this.glowRing.position.set(s.x, y + 0.016, s.z);
    this.glowRing.rotation.x = -Math.PI / 2 - this.pitchSmooth;
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
    if (this.dust) this.dust.rotation.y = Math.sin(this.time * 0.05) * 0.04;

    this._updateCamera(dt, s);
  }

  _updateCamera(dt, s) {
    const p = this.chair.position;
    const target = new THREE.Vector3();
    const look = new THREE.Vector3();

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

// 便捷再导出：只想要模型、不要渲染器的调用方从这里拿
export {
  SEGMENTS, TRACK_W, TRACK_LEN, zoneAt, heightAt, slopeRadAt, pitAt,
  buildTrack, buildProps,
  buildWheelchair, WHEELCHAIR_SPEC, makeMaterials,
};
