/* =====================================================================
   轮椅 3D 模拟器 —— 主程序

   技术栈：Three.js r147（UMD 版，本地 lib/three.min.js）+ 原生 DOM/CSS
   运行方式：直接双击 index.html 即可。全程走 file://，没有构建步骤，
             因此刻意不使用 ES Module（file:// 下会被 CORS 拦住）。

   代码结构（按出现顺序）
     1.  CFG         可调参数：最高速度、加速度、轮距、碰撞半径、场地大小
     2.  渲染器/场景  色彩管理、阴影、雾、天空球、灯光
     3.  地面        程序生成的混凝土板贴图（Canvas 绘制 + 重复平铺）
     4.  材质库      铝件 / 轮胎 / 布料 / 木头 / 石材等共用材质
     5.  轮椅建模    两个大轮 + 车架管件 + 坐垫靠背 + 脚踏 + 万向小轮 + 乘客
     6.  场地陈设    边界矮墙、中央花坛与树、长椅、花箱、锥桶、木箱、路人
     7.  光环        5 个收集目标（含地面光晕底座）
     8.  状态与输入  S 状态、键盘映射、屏幕按钮、鼠标拖拽绕看
     9.  物理        差速运动学 + 圆形/矩形碰撞 + 场地边界钳制
    10.  相机        第三人称跟随，可绕看与伸缩
    11.  HUD         速度 / 里程 / 光环 / 当前状态
    12.  主循环      requestAnimationFrame
    13.  测试接口    供自动化回归脚本读取状态（window.__DBG / __SET）
   ===================================================================== */
(function () {
  "use strict";

  // ---------------------------------------------------------------- 基本参数
  const CFG = {
    wheelR: 0.30,        // 后轮半径
    track: 0.62,         // 轮距（左右轮间距）
    vmax: 1.9,           // 最高速度 m/s
    boost: 1.55,         // 加速档倍率
    accel: 2.6,          // 加速度 m/s^2
    brakeDec: 7.0,       // 刹车减速度
    turnRate: 1.9,       // 最大横摆角速度 rad/s
    turnAcc: 9.0,        // 角加速度
    radius: 0.46,        // 碰撞半径
    arena: 11.0,         // 活动范围半径（方形半边长）
    ringCount: 5,
  };

  // ---------------------------------------------------------------- 渲染器
  // 关掉 legacy 色彩模式：十六进制色按 sRGB 解释再转线性，否则深色材质会被"漂白"
  if (THREE.ColorManagement) THREE.ColorManagement.legacyMode = false;

  const canvas = document.getElementById("scene");
  const renderer = new THREE.WebGLRenderer({
    canvas, antialias: true, powerPreference: "high-performance",
  });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.outputEncoding = THREE.sRGBEncoding;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 0.96;

  const scene = new THREE.Scene();
  scene.fog = new THREE.Fog(0xd7e2ee, 42, 110);
  const camera = new THREE.PerspectiveCamera(50, window.innerWidth / window.innerHeight, 0.1, 300);
  camera.position.set(-4, 2.4, 4);

  // ---------------------------------------------------------------- 天空
  const skyMat = new THREE.ShaderMaterial({
    uniforms: {
      top: { value: new THREE.Color(0x6698cb) },
      mid: { value: new THREE.Color(0xc3d7ea) },
      bot: { value: new THREE.Color(0xeef1f3) },
    },
    vertexShader: `
      varying vec3 vP;
      void main() {
        vP = position;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }`,
    fragmentShader: `
      uniform vec3 top; uniform vec3 mid; uniform vec3 bot;
      varying vec3 vP;
      void main() {
        float h = normalize(vP).y;
        vec3 c = mix(bot, mid, smoothstep(-0.05, 0.28, h));
        c = mix(c, top, smoothstep(0.25, 0.85, h));
        gl_FragColor = vec4(c, 1.0);
      }`,
    side: THREE.BackSide,
    depthWrite: false,
  });
  const sky = new THREE.Mesh(new THREE.SphereGeometry(140, 32, 20), skyMat);
  scene.add(sky);

  // ---------------------------------------------------------------- 灯光
  scene.add(new THREE.HemisphereLight(0xc3d7ef, 0x666d78, 0.42));

  const sun = new THREE.DirectionalLight(0xfff2dc, 1.95);
  sun.position.set(9, 14, 6);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.left = -16;
  sun.shadow.camera.right = 16;
  sun.shadow.camera.top = 16;
  sun.shadow.camera.bottom = -16;
  sun.shadow.camera.near = 1;
  sun.shadow.camera.far = 48;
  sun.shadow.bias = -0.0008;
  sun.shadow.normalBias = 0.02;
  scene.add(sun);
  scene.add(sun.target);

  const fill = new THREE.DirectionalLight(0xbcd2ea, 0.26);
  fill.position.set(-8, 6, -9);
  scene.add(fill);

  // ---------------------------------------------------------------- 地面
  function floorTexture() {
    const s = 512;
    const c = document.createElement("canvas");
    c.width = c.height = s;
    const g = c.getContext("2d");
    g.fillStyle = "#a7aeb8";
    g.fillRect(0, 0, s, s);
    // 细颗粒
    for (let i = 0; i < 14000; i++) {
      const a = Math.random() * 0.05;
      g.fillStyle = Math.random() > 0.5
        ? `rgba(255,255,255,${a})` : `rgba(60,70,85,${a})`;
      g.fillRect(Math.random() * s, Math.random() * s, 1.6, 1.6);
    }
    // 板缝
    g.strokeStyle = "rgba(60,70,86,0.3)";
    g.lineWidth = 3;
    g.strokeRect(0, 0, s, s);
    g.beginPath();
    g.moveTo(s / 2, 0); g.lineTo(s / 2, s);
    g.moveTo(0, s / 2); g.lineTo(s, s / 2);
    g.stroke();
    const t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(40, 40);
    t.anisotropy = 8;
    t.encoding = THREE.sRGBEncoding;
    return t;
  }

  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(120, 120),
    new THREE.MeshStandardMaterial({ map: floorTexture(), roughness: 0.94, metalness: 0.02 })
  );
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);

  // ---------------------------------------------------------------- 材质库
  const MAT = {
    alu: new THREE.MeshStandardMaterial({ color: 0xc6ced8, metalness: 0.82, roughness: 0.34 }),
    aluDark: new THREE.MeshStandardMaterial({ color: 0x8b94a1, metalness: 0.8, roughness: 0.42 }),
    tire: new THREE.MeshStandardMaterial({ color: 0x1b1e22, roughness: 0.95, metalness: 0.04 }),
    rim: new THREE.MeshStandardMaterial({ color: 0xe07b2c, metalness: 0.55, roughness: 0.42 }),
    fabric: new THREE.MeshStandardMaterial({ color: 0x2e3a4b, roughness: 0.95, metalness: 0.0 }),
    seat: new THREE.MeshStandardMaterial({ color: 0x3a4a5e, roughness: 0.88 }),
    skin: new THREE.MeshStandardMaterial({ color: 0xe8b48f, roughness: 0.72 }),
    shirt: new THREE.MeshStandardMaterial({ color: 0x3f7fd6, roughness: 0.85 }),
    pants: new THREE.MeshStandardMaterial({ color: 0x33405c, roughness: 0.9 }),
    hedge: new THREE.MeshStandardMaterial({ color: 0x4f7a45, roughness: 0.95 }),
    wood: new THREE.MeshStandardMaterial({ color: 0xa8794e, roughness: 0.8 }),
    stone: new THREE.MeshStandardMaterial({ color: 0xa3a9b1, roughness: 0.9 }),
    metal: new THREE.MeshStandardMaterial({ color: 0x6d7683, metalness: 0.7, roughness: 0.45 }),
    cone: new THREE.MeshStandardMaterial({ color: 0xf2762c, roughness: 0.7 }),
  };

  // 小工具：在两点之间放一根圆柱（做管件）
  const UP = new THREE.Vector3(0, 1, 0);
  function tube(a, b, r, mat) {
    const dir = new THREE.Vector3().subVectors(b, a);
    const len = dir.length();
    const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, len, 12), mat || MAT.alu);
    m.position.copy(a).addScaledVector(dir, 0.5);
    m.quaternion.setFromUnitVectors(UP, dir.clone().normalize());
    m.castShadow = true;
    return m;
  }

  function box(w, h, d, mat, pos) {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
    if (pos) m.position.set(pos[0], pos[1], pos[2]);
    m.castShadow = true;
    m.receiveShadow = true;
    return m;
  }

  // ---------------------------------------------------------------- 轮椅
  const chair = new THREE.Group();
  scene.add(chair);

  const wheelGroups = {};   // L / R，绕本地 Z 轴自转

  function buildWheel(side) {
    // side: -1 左(z负) / +1 右(z正)
    const g = new THREE.Group();

    const tire = new THREE.Mesh(new THREE.TorusGeometry(CFG.wheelR, 0.032, 10, 40), MAT.tire);
    tire.castShadow = true;
    g.add(tire);

    // 手推圈
    const rim = new THREE.Mesh(new THREE.TorusGeometry(0.235, 0.011, 8, 36), MAT.aluDark);
    rim.position.z = side * 0.045;
    rim.castShadow = true;
    g.add(rim);

    // 轮圈内环
    const inner = new THREE.Mesh(new THREE.TorusGeometry(0.272, 0.016, 8, 36), MAT.rim);
    inner.castShadow = true;
    g.add(inner);

    // 辐条
    for (let i = 0; i < 8; i++) {
      const a = (i / 8) * Math.PI * 2;
      const spoke = new THREE.Mesh(new THREE.CylinderGeometry(0.006, 0.006, 0.27, 6), MAT.alu);
      spoke.position.set(Math.cos(a) * 0.135, Math.sin(a) * 0.135, 0);
      spoke.rotation.z = a - Math.PI / 2;
      spoke.castShadow = true;
      g.add(spoke);
    }

    // 轮毂
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(0.045, 0.045, 0.085, 16), MAT.aluDark);
    hub.rotation.x = Math.PI / 2;
    hub.castShadow = true;
    g.add(hub);

    g.position.set(-0.02, CFG.wheelR, side * CFG.track / 2);
    return g;
  }

  const wl = buildWheel(-1);
  const wr = buildWheel(1);
  chair.add(wl, wr);
  wheelGroups.L = wl;
  wheelGroups.R = wr;

  // 车架
  const seatY = CFG.wheelR + 0.20;
  for (const s of [-1, 1]) {
    const z = s * (CFG.track / 2 - 0.02);
    chair.add(tube(new THREE.Vector3(-0.16, seatY, z), new THREE.Vector3(0.40, seatY, z), 0.017));
    chair.add(tube(new THREE.Vector3(0.40, seatY, z), new THREE.Vector3(0.40, 0.16, z), 0.015));
    chair.add(tube(new THREE.Vector3(0.40, 0.16, z), new THREE.Vector3(0.47, 0.14, z), 0.014));
    chair.add(tube(new THREE.Vector3(-0.02, CFG.wheelR, z), new THREE.Vector3(-0.14, seatY, z), 0.016));
    chair.add(tube(new THREE.Vector3(-0.14, seatY, z), new THREE.Vector3(-0.22, seatY + 0.52, z), 0.016));
    // 扶手
    chair.add(tube(new THREE.Vector3(-0.18, seatY + 0.20, z), new THREE.Vector3(0.26, seatY + 0.20, z), 0.013));
    chair.add(tube(new THREE.Vector3(0.26, seatY + 0.20, z), new THREE.Vector3(0.26, seatY, z), 0.012));
    // 推手
    chair.add(tube(new THREE.Vector3(-0.22, seatY + 0.52, z), new THREE.Vector3(-0.30, seatY + 0.56, z), 0.014));
  }
  // 横梁
  chair.add(tube(new THREE.Vector3(-0.15, seatY - 0.03, -0.30), new THREE.Vector3(-0.15, seatY - 0.03, 0.30), 0.013));
  chair.add(tube(new THREE.Vector3(0.36, 0.17, -0.30), new THREE.Vector3(0.36, 0.17, 0.30), 0.013));

  // 坐垫 + 靠背
  const cushion = box(0.46, 0.07, 0.48, MAT.seat, [0.06, seatY + 0.035, 0]);
  chair.add(cushion);

  const back = box(0.09, 0.42, 0.44, MAT.fabric, [-0.225, seatY + 0.26, 0]);
  back.rotation.z = -0.12;
  chair.add(back);
  // 靠背顶部横杆，视觉上收口
  const backBar = new THREE.Mesh(new THREE.CylinderGeometry(0.017, 0.017, 0.46, 10), MAT.alu);
  backBar.rotation.x = Math.PI / 2;
  backBar.position.set(-0.255, seatY + 0.47, 0);
  backBar.castShadow = true;
  chair.add(backBar);

  // 脚踏板
  chair.add(box(0.24, 0.028, 0.42, MAT.aluDark, [0.50, 0.11, 0]));

  // 前万向小轮
  for (const s of [-1, 1]) {
    const z = s * 0.24;
    const fork = tube(new THREE.Vector3(0.40, 0.16, z), new THREE.Vector3(0.44, 0.075, z), 0.014);
    chair.add(fork);
    const cw = new THREE.Mesh(new THREE.TorusGeometry(0.075, 0.022, 8, 20), MAT.tire);
    cw.position.set(0.455, 0.075, z);
    cw.castShadow = true;
    chair.add(cw);
  }

  // 乘客
  const rider = new THREE.Group();
  const hips = new THREE.Vector3(0.05, seatY + 0.13, 0);
  const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.135, 0.26, 6, 14), MAT.shirt);
  torso.position.set(hips.x - 0.015, hips.y + 0.23, 0);
  torso.castShadow = true;
  rider.add(torso);

  const head = new THREE.Mesh(new THREE.SphereGeometry(0.105, 20, 16), MAT.skin);
  head.position.set(hips.x + 0.005, hips.y + 0.47, 0);
  head.castShadow = true;
  rider.add(head);

  const hair = new THREE.Mesh(new THREE.SphereGeometry(0.108, 18, 14, 0, Math.PI * 2, 0, Math.PI * 0.55), MAT.pants);
  hair.position.copy(head.position);
  hair.rotation.x = -0.18;
  rider.add(hair);

  for (const s of [-1, 1]) {
    // 手臂：肩 -> 扶手
    const sh = new THREE.Vector3(hips.x, hips.y + 0.36, s * 0.15);
    const hand = new THREE.Vector3(hips.x + 0.24, seatY + 0.235, s * 0.21);
    rider.add(tube(sh, hand, 0.045, MAT.shirt));
    // 腿：髋 -> 脚踏
    const hp = new THREE.Vector3(hips.x + 0.04, hips.y - 0.02, s * 0.11);
    const knee = new THREE.Vector3(hips.x + 0.30, hips.y - 0.06, s * 0.11);
    const foot = new THREE.Vector3(hips.x + 0.42, 0.14, s * 0.11);
    rider.add(tube(hp, knee, 0.062, MAT.pants));
    rider.add(tube(knee, foot, 0.055, MAT.pants));
  }
  chair.add(rider);

  // ---------------------------------------------------------------- 场地陈设
  const colliders = [];   // {type:'box'|'circle', ...}

  function addBoxCollider(x, z, hw, hd) { colliders.push({ type: "box", x, z, hw, hd }); }
  function addCircleCollider(x, z, r) { colliders.push({ type: "circle", x, z, r }); }

  // 边界矮墙 + 绿篱
  const WALL = CFG.arena + 0.65;
  for (const [dx, dz, w, d] of [[0, -WALL, WALL * 2 + 1, 0.7], [0, WALL, WALL * 2 + 1, 0.7],
                                [-WALL, 0, 0.7, WALL * 2 + 1], [WALL, 0, 0.7, WALL * 2 + 1]]) {
    const wall = box(w, 0.55, d, MAT.stone, [dx, 0.275, dz]);
    scene.add(wall);
    const hedge = box(w * 0.985, 0.35, d * 0.985, MAT.hedge, [dx, 0.72, dz]);
    scene.add(hedge);
  }

  // 中央圆形花坛
  const center = new THREE.Mesh(new THREE.CylinderGeometry(2.1, 2.2, 0.45, 40), MAT.stone);
  center.position.y = 0.225;
  center.castShadow = true;
  center.receiveShadow = true;
  scene.add(center);
  addCircleCollider(0, 0, 2.2);
  const tree = new THREE.Mesh(new THREE.CylinderGeometry(0.13, 0.17, 2.2, 10), MAT.wood);
  tree.position.y = 1.5;
  tree.castShadow = true;
  scene.add(tree);
  const crown = new THREE.Mesh(new THREE.IcosahedronGeometry(1.15, 1), MAT.hedge);
  crown.position.y = 3.0;
  crown.castShadow = true;
  scene.add(crown);
  for (let i = 0; i < 10; i++) {
    const a = (i / 10) * Math.PI * 2;
    const bush = new THREE.Mesh(new THREE.SphereGeometry(0.22 + Math.random() * 0.1, 10, 8), MAT.hedge);
    bush.position.set(Math.cos(a) * 1.9, 0.5, Math.sin(a) * 1.9);
    bush.castShadow = true;
    scene.add(bush);
  }

  // 广场铺装：中心环 + 导向条
  const paint = new THREE.MeshStandardMaterial({ color: 0xe4e7ea, roughness: 0.95 });
  const ringLine = new THREE.Mesh(new THREE.RingGeometry(3.0, 3.22, 64), paint);
  ringLine.rotation.x = -Math.PI / 2;
  ringLine.position.y = 0.012;
  ringLine.receiveShadow = true;
  scene.add(ringLine);
  const ringLine2 = new THREE.Mesh(new THREE.RingGeometry(4.6, 4.72, 64), paint);
  ringLine2.rotation.x = -Math.PI / 2;
  ringLine2.position.y = 0.012;
  ringLine2.receiveShadow = true;
  scene.add(ringLine2);

  // 长椅
  function bench(x, z, rot) {
    const g = new THREE.Group();
    g.add(box(2.0, 0.09, 0.46, MAT.wood, [0, 0.45, 0]));
    g.add(box(2.0, 0.40, 0.08, MAT.wood, [0, 0.68, -0.19]));
    for (const s of [-1, 1]) g.add(box(0.1, 0.42, 0.44, MAT.metal, [s * 0.82, 0.21, 0]));
    g.position.set(x, 0, z);
    g.rotation.y = rot;
    g.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
    scene.add(g);
    const hw = Math.abs(Math.cos(rot)) * 1.0 + Math.abs(Math.sin(rot)) * 0.25;
    const hd = Math.abs(Math.cos(rot)) * 0.25 + Math.abs(Math.sin(rot)) * 1.0;
    addBoxCollider(x, z, hw, hd);
  }
  bench(-7.2, -4.4, 0.0);
  bench(6.8, 5.6, Math.PI);
  bench(7.4, -5.2, Math.PI / 2);

  // 花箱
  function planter(x, z, s) {
    scene.add(box(s, 0.5, s, MAT.stone, [x, 0.25, z]));
    const bush = new THREE.Mesh(new THREE.IcosahedronGeometry(s * 0.42, 1), MAT.hedge);
    bush.position.set(x, 0.62, z);
    bush.castShadow = true;
    scene.add(bush);
    addBoxCollider(x, z, s / 2, s / 2);
  }
  planter(-6.0, 5.8, 1.5);
  planter(-8.4, 3.0, 1.2);
  planter(5.4, -6.6, 1.4);
  planter(-3.4, -7.4, 1.3);

  // 锥桶
  const coneGeo = new THREE.ConeGeometry(0.19, 0.55, 14);
  const coneBase = new THREE.BoxGeometry(0.34, 0.04, 0.34);
  function cone(x, z) {
    const g = new THREE.Group();
    const c = new THREE.Mesh(coneGeo, MAT.cone);
    c.position.y = 0.30;
    c.castShadow = true;
    g.add(c);
    const b = new THREE.Mesh(coneBase, MAT.cone);
    b.position.y = 0.02;
    b.receiveShadow = true;
    g.add(b);
    g.position.set(x, 0, z);
    scene.add(g);
    addCircleCollider(x, z, 0.24);
  }
  cone(2.8, -3.2); cone(3.6, -3.9); cone(4.4, -4.6); cone(-3.0, 3.4); cone(-4.2, 4.4);

  // 木箱
  function crate(x, z, s) {
    scene.add(box(s, s, s, MAT.wood, [x, s / 2, z]));
    addBoxCollider(x, z, s / 2, s / 2);
  }
  crate(-8.6, -7.6, 0.8);
  crate(-8.6, -6.6, 0.6);
  crate(9.0, 2.0, 0.9);

  // 远处路人（纯装饰，不参与碰撞）
  function person(x, z, rot, color) {
    const g = new THREE.Group();
    const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.17, 0.55, 5, 12),
      new THREE.MeshStandardMaterial({ color, roughness: 0.9 }));
    body.position.y = 0.85;
    body.castShadow = true;
    g.add(body);
    const h = new THREE.Mesh(new THREE.SphereGeometry(0.13, 16, 12), MAT.skin);
    h.position.y = 1.33;
    h.castShadow = true;
    g.add(h);
    g.position.set(x, 0, z);
    g.rotation.y = rot;
    scene.add(g);
  }
  person(9.6, -8.6, 0.6, 0x8a6f9e);
  person(-9.8, 8.4, -0.4, 0xd9a05b);

  // ---------------------------------------------------------------- 光环
  const rings = [];
  const ringPts = [
    [4.6, -7.6], [-6.6, -2.2], [8.2, 4.4], [-8.8, 8.4], [6.2, 0.0],
  ];
  const ringMatBase = new THREE.MeshStandardMaterial({
    color: 0xffb060, emissive: 0xff7a18, emissiveIntensity: 1.3,
    metalness: 0.3, roughness: 0.35,
  });
  ringPts.forEach(([x, z], i) => {
    const m = new THREE.Mesh(new THREE.TorusGeometry(0.52, 0.05, 10, 32), ringMatBase.clone());
    m.position.set(x, 0.72, z);
    m.castShadow = true;
    scene.add(m);

    // 地面光晕底座：让光环"落地"
    const halo = new THREE.Mesh(
      new THREE.CircleGeometry(0.62, 28),
      new THREE.MeshBasicMaterial({
        color: 0xffa040, transparent: true, opacity: 0.28,
      })
    );
    halo.rotation.x = -Math.PI / 2;
    halo.position.set(x, 0.02, z);
    scene.add(halo);

    rings.push({ mesh: m, halo, x, z, taken: false, spin: i * 0.7, pop: 0 });
  });

  // ---------------------------------------------------------------- 状态
  const S = {
    x: 0, z: 6.6, yaw: Math.PI * 0.5,   // 面向场地中央（-Z）
    v: 0, omega: 0, angL: 0, angR: 0, dist: 0,
    collected: 0, time: 0, done: false,
  };
  const HOME = { x: S.x, z: S.z, yaw: S.yaw };

  function reset() {
    S.x = HOME.x; S.z = HOME.z; S.yaw = HOME.yaw;
    S.v = S.omega = S.angL = S.angR = 0; S.dist = 0;
    S.collected = 0; S.time = 0; S.done = false;
    rings.forEach((r) => {
      r.taken = false; r.pop = 0;
      r.mesh.visible = true; r.mesh.scale.setScalar(1);
      r.halo.visible = true; r.halo.material.opacity = 0.28;
    });
    updateHud();
    toast("已回到起点");
  }

  // ---------------------------------------------------------------- 输入
  const keys = new Set();
  const KEYMAP = {
    KeyW: "W", ArrowUp: "W", KeyS: "S", ArrowDown: "S",
    KeyA: "A", ArrowLeft: "A", KeyD: "D", ArrowRight: "D",
    ShiftLeft: "BOOST", ShiftRight: "BOOST", Space: "BRAKE",
  };

  window.addEventListener("keydown", (e) => {
    const k = KEYMAP[e.code];
    if (k) { keys.add(k); e.preventDefault(); }
    if (e.code === "KeyR") reset();
    if (e.code === "KeyC") cycleCam();
    if (e.code === "KeyH") togglePad();
    if (e.code === "Escape") togglePad();
  });
  window.addEventListener("keyup", (e) => {
    const k = KEYMAP[e.code];
    if (k) keys.delete(k);
  });
  window.addEventListener("blur", () => keys.clear());

  // 屏幕按钮（按住即等效按下对应按键，松开即释放）
  const padBtns = Array.from(document.querySelectorAll("[data-k]"));
  padBtns.forEach((btn) => {
    const tag = btn.dataset.k;
    const on = (e) => {
      e.preventDefault();
      keys.add(tag);
      btn.setPointerCapture && btn.setPointerCapture(e.pointerId);
    };
    const off = () => { keys.delete(tag); };
    btn.addEventListener("pointerdown", on);
    btn.addEventListener("pointerup", off);
    btn.addEventListener("pointercancel", off);
    btn.addEventListener("pointerleave", off);
    btn.addEventListener("contextmenu", (e) => e.preventDefault());
  });

  // 高亮由按键状态统一驱动：鼠标点、键盘按都能亮（只在状态变化时动 DOM）
  let lastActive = "";
  function syncPad() {
    const active = ["W", "A", "S", "D", "BRAKE"].filter((k) => keys.has(k)).join(",");
    if (active === lastActive) return;
    lastActive = active;
    for (const b of padBtns) b.classList.toggle("on", keys.has(b.dataset.k));
  }

  // 鼠标拖动绕看
  let drag = null;
  let orbitAz = 0, orbitPitch = 0.39, camDist = 4.7;
  let camMode = 0;   // 0 跟拍 1 远景

  canvas.addEventListener("pointerdown", (e) => {
    drag = { x: e.clientX, y: e.clientY };
    canvas.style.cursor = "grabbing";
  });
  window.addEventListener("pointermove", (e) => {
    if (!drag) return;
    orbitAz -= (e.clientX - drag.x) * 0.008;
    orbitPitch = Math.max(0.06, Math.min(1.05, orbitPitch + (e.clientY - drag.y) * 0.005));
    drag = { x: e.clientX, y: e.clientY };
  });
  window.addEventListener("pointerup", () => { drag = null; canvas.style.cursor = "default"; });
  canvas.addEventListener("wheel", (e) => {
    camDist = Math.max(2.4, Math.min(9.5, camDist + e.deltaY * 0.0022));
    e.preventDefault();
  }, { passive: false });

  function cycleCam() {
    camMode = (camMode + 1) % 2;
    orbitPitch = camMode === 0 ? 0.39 : 0.78;
    camDist = camMode === 0 ? 4.7 : 8.4;
    document.getElementById("btnCam").textContent = camMode === 0 ? "跟拍视角 · C" : "远景视角 · C";
  }

  function togglePad() {
    const pad = document.getElementById("pad");
    pad.classList.toggle("hidden");
    document.getElementById("btnHelp").textContent =
      pad.classList.contains("hidden") ? "显示面板 · H" : "隐藏面板 · H";
  }
  document.getElementById("btnCam").addEventListener("click", cycleCam);
  document.getElementById("btnReset").addEventListener("click", reset);
  document.getElementById("btnHelp").addEventListener("click", togglePad);

  // ---------------------------------------------------------------- 提示
  let toastTimer = 0;
  const toastEl = document.getElementById("toast");
  function toast(text) {
    toastEl.textContent = text;
    toastEl.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove("show"), 1600);
  }

  // ---------------------------------------------------------------- 物理
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);

  function collide() {
    let hit = false;
    for (const c of colliders) {
      if (c.type === "circle") {
        const dx = S.x - c.x, dz = S.z - c.z;
        const d = Math.hypot(dx, dz);
        const min = c.r + CFG.radius;
        if (d < min && d > 1e-6) {
          const push = (min - d);
          S.x += (dx / d) * push;
          S.z += (dz / d) * push;
          hit = true;
        }
      } else {
        const nx = clamp(S.x, c.x - c.hw, c.x + c.hw);
        const nz = clamp(S.z, c.z - c.hd, c.z + c.hd);
        const dx = S.x - nx, dz = S.z - nz;
        const d = Math.hypot(dx, dz);
        if (d < CFG.radius) {
          if (d > 1e-6) {
            const push = CFG.radius - d;
            S.x += (dx / d) * push;
            S.z += (dz / d) * push;
          } else {
            S.x += CFG.radius;   // 卡在箱体内部时往一侧挤出
          }
          hit = true;
        }
      }
    }
    // 边界
    const lim = CFG.arena - 0.2;
    if (S.x > lim) { S.x = lim; hit = true; }
    if (S.x < -lim) { S.x = -lim; hit = true; }
    if (S.z > lim) { S.z = lim; hit = true; }
    if (S.z < -lim) { S.z = -lim; hit = true; }
    if (hit) S.v *= 0.25;
    return hit;
  }

  function physics(dt) {
    const boost = keys.has("BOOST");
    const brake = keys.has("BRAKE");
    const throttle = (keys.has("W") ? 1 : 0) - (keys.has("S") ? 1 : 0);
    const steer = (keys.has("A") ? 1 : 0) - (keys.has("D") ? 1 : 0);

    const vmax = CFG.vmax * (boost ? CFG.boost : 1);
    const vTarget = throttle * vmax;
    const dv = vTarget - S.v;
    const stepA = CFG.accel * (boost ? 1.25 : 1) * dt;
    S.v += Math.sign(dv) * Math.min(Math.abs(dv), stepA);

    if (brake) {
      const dec = CFG.brakeDec * dt;
      S.v = Math.abs(S.v) <= dec ? 0 : S.v - Math.sign(S.v) * dec;
    }

    const wTarget = steer * CFG.turnRate;
    const dw = wTarget - S.omega;
    S.omega += Math.sign(dw) * Math.min(Math.abs(dw), CFG.turnAcc * dt);

    const px = S.x, pz = S.z;
    S.yaw += S.omega * dt;
    S.x += Math.cos(S.yaw) * S.v * dt;
    S.z -= Math.sin(S.yaw) * S.v * dt;
    collide();
    S.dist += Math.hypot(S.x - px, S.z - pz);

    // 差速：左右轮线速度不同 -> 视觉转速不同
    const vL = S.v - S.omega * CFG.track / 2;
    const vR = S.v + S.omega * CFG.track / 2;
    S.angL += (vL / CFG.wheelR) * dt;
    S.angR += (vR / CFG.wheelR) * dt;

    chair.position.set(S.x, 0, S.z);
    chair.rotation.y = S.yaw;
    wheelGroups.L.rotation.z = S.angL;
    wheelGroups.R.rotation.z = S.angR;

    // 收集光环
    for (const r of rings) {
      if (r.taken) continue;
      if (Math.hypot(S.x - r.x, S.z - r.z) < 0.78) {
        r.taken = true;
        S.collected++;
        toast("收集光环 " + S.collected + "/" + CFG.ringCount);
      }
    }
  }

  // ---------------------------------------------------------------- 相机
  const camTarget = new THREE.Vector3();
  const camDesired = new THREE.Vector3();
  const tmp = new THREE.Vector3();

  function updateCamera(dt) {
    const fwd = new THREE.Vector3(Math.cos(S.yaw), 0, -Math.sin(S.yaw));
    // 默认站在车后，拖动时绕车旋转
    const baseAngle = Math.atan2(-fwd.z, -fwd.x) + orbitAz;
    const horiz = Math.cos(orbitPitch) * camDist;
    camDesired.set(
      S.x + Math.cos(baseAngle) * horiz,
      Math.sin(orbitPitch) * camDist + 0.55,
      S.z + Math.sin(baseAngle) * horiz
    );
    const k = 1 - Math.exp(-6.5 * dt);
    camera.position.lerp(camDesired, k);
    camTarget.lerp(tmp.set(S.x + fwd.x * 0.9, 0.82, S.z + fwd.z * 0.9), 1 - Math.exp(-8 * dt));
    camera.lookAt(camTarget);

    // 太阳跟着车走，保证阴影始终清晰
    sun.position.set(S.x + 9, 14, S.z + 6);
    sun.target.position.set(S.x, 0, S.z);
    sun.target.updateMatrixWorld();
  }

  // ---------------------------------------------------------------- HUD
  const el = {
    spd: document.getElementById("spd"),
    dist: document.getElementById("dist"),
    ring: document.getElementById("ring"),
    state: document.getElementById("state"),
    hint: document.getElementById("hint"),
  };

  function updateHud() {
    const spd = Math.abs(S.v);
    el.spd.textContent = spd.toFixed(2);
    el.dist.textContent = S.dist.toFixed(1);
    el.ring.textContent = S.collected + "/" + CFG.ringCount;

    let label = "待机";
    if (keys.has("BRAKE")) label = "刹车";
    else if (spd < 0.03 && Math.abs(S.omega) > 0.15) label = S.omega > 0 ? "原地左转" : "原地右转";
    else if (spd < 0.03) label = "待机";
    else label = S.v > 0 ? "前进" : "后退";
    if (el.state.textContent !== label) el.state.textContent = label;
    el.state.classList.toggle("on", label === "刹车" || label.indexOf("原地") === 0);

    if (S.collected === CFG.ringCount && !S.done) {
      S.done = true;
      toast("全部光环收集完成！用时 " + S.time.toFixed(1) + " 秒");
    }
  }

  // ---------------------------------------------------------------- 主循环
  let last = performance.now();
  let hintFaded = false;
  let fpsEma = 60;              // 帧率滑动平均，仅用于调试接口

  function loop(now) {
    let dt = (now - last) / 1000;
    last = now;
    dt = Math.min(dt, 0.05);    // 切标签页回来时不要一步跳太远
    S.time += dt;
    if (dt > 0) fpsEma += (1 / dt - fpsEma) * 0.08;

    physics(dt);
    updateCamera(dt);

    // 光环动画
    for (const r of rings) {
      if (r.taken) {
        r.pop += dt * 3.2;
        const s = Math.max(0, 1 - r.pop);
        r.mesh.scale.setScalar(0.001 + s);
        r.mesh.position.y = 0.72 + r.pop * 1.2;
        r.mesh.rotation.z += dt * 6;
        r.halo.material.opacity = 0.28 * s;
        if (s <= 0) { r.mesh.visible = false; r.halo.visible = false; }
        continue;
      }
      r.mesh.rotation.y += dt * 1.1;
      r.mesh.position.y = 0.72 + Math.sin(now * 0.002 + r.spin) * 0.07;
    }

    updateHud();
    syncPad();

    if (!hintFaded && (S.dist > 1.5)) {
      hintFaded = true;
      el.hint.classList.add("fade");
    }

    renderer.render(scene, camera);
    requestAnimationFrame(loop);
  }

  // ---------------------------------------------------------------- 自适应
  window.addEventListener("resize", () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  // 初始摆位
  chair.position.set(S.x, 0, S.z);
  chair.rotation.y = S.yaw;
  camTarget.set(S.x, 0.95, S.z);
  camera.position.set(S.x + 3.2, 2.2, S.z + 3.6);
  camera.lookAt(camTarget);
  updateHud();
  requestAnimationFrame(loop);

  // ---------------------------------------------------------------- 自动化测试接口
  // 仅供脚本化回归测试读写内部状态，正常游玩时不会用到，也没有任何副作用。
  window.__SET = (x, z) => { S.x = x; S.z = z; };
  window.__DBG = () => ({
    x: +S.x.toFixed(3), z: +S.z.toFixed(3), yaw: +S.yaw.toFixed(3),
    v: +S.v.toFixed(3), omega: +S.omega.toFixed(3),
    dist: +S.dist.toFixed(2), collected: S.collected,
    angL: +S.angL.toFixed(2), angR: +S.angR.toFixed(2),
    time: +S.time.toFixed(1), fps: +fpsEma.toFixed(1),
  });
})();
