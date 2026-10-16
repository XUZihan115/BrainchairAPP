# 脑控轮椅 · Unity 仿真工程

对应课题：**15 脑控轮椅 APP / 上位机开发**（翔宇医疗）第一阶段 —— 先做"虚拟轮椅 / 仿真环境"。

本工程实现：**接收外部指令（蓝牙 / 串口 / TCP / 本地键盘），驱动一辆虚拟轮椅完成
前进、后退、转向、原地旋转、停止、急停，并回传状态**。

并且区分两种指令语义：

* **步进式**（默认，键盘和远程链路都是）：一条指令 = **走 1 m / 转 30° 后自动停**。
  这是给 **SSVEP 脑电**准备的——脑电指令天生离散，而且用户只能盯着闪烁目标、
  没法一边盯一边看车。步进把「一次误判」的代价从「车一直往前开」压成「多走 1 米就停」。
  细节见 `docs/unity-wheelchair-sim-guide.md` 第 3.6 节。
* **状态保持式**：按一下 `W` 就一直前进，直到停车——手动连续控制用。
  键盘上**按住 `Shift` 再按键**，那一下就走状态保持式（松开恢复一步一停）；
  左下角的运动按钮也一直是状态保持式。

```text
按键            一步一停（默认）              按住 Shift 再按
W / S           走 1 m / 退 1 m               一直前进 / 一直后退
A / D          原地左转 30° / 右转 30°         边走边转（弧线）
Q / E          原地左转 30° / 右转 30°         一直原地旋转
```

---

## 一、3 步跑起来

1. 用 Unity Hub 打开本目录（或者新建一个 3D 工程，把本目录的 `Assets/` 内容复制进去）。
2. 双击打开场景 `Assets/Scenes/Main.unity`（里面已经挂好了 `WheelchairBootstrap`）。
3. 按 **Play**。

地面、灯光、相机、轮椅、**坡道**、障碍物、HUD 面板、指令来源都会自动创建。

| 按键 | 一步一停（默认） | 按住 `Shift` 再按 |
|---|---|---|
| `W` / `S` | 前进 1 m / 后退 1 m | 一直前进 / 一直后退 |
| `A` / `D` | 原地左转 30° / 右转 30° | 行进中左转 / 右转（弧线） |
| `Q` / `E` | 原地左旋 30° / 右旋 30° | 一直原地左旋 / 右旋 |
| `空格` | 停车 | 同左 |
| `X` / `Z` | 急停（锁存）/ 解除急停 | 同左 |
| `F` | 编辑器专属：收起多余窗口（专注模式） | 同左 |

按一下 `W` 车往前走 1 米就自动停（**步进式**，和脑电指令一致）。
想要连续驾驶就**按住 `Shift` 再按 `W`**，松开 `Shift` 又回到一步一停。
正前方就是一条约 9.5° 的坡道，`Shift` + `W` 直行即可测试爬坡。

> 键盘默认一步一停，是为了让你在编辑器里就能直接用键盘模拟脑电指令
> （脑电用户一次只能给一条离散指令）。这个默认值在 `WheelchairHub` 的
> **StepModeForLocalInput** 上，运行时改也立刻生效。

> 左下角还有一栏 **「步进」** 四个按钮（步进前进 1 m / 步进后退 1 m / 步转左 30° / 步转右 30°），
> 点一下就能看到「走完就自动停」的效果——和键盘默认行为、远程链路（蓝牙 / 串口 / TCP）默认行为一致。
> 上位机想连续控制，发 `MODE:LATCH` 切回状态保持式即可；发 `MODE:STEP` 切回步进式。

> 场景被弄乱了？点菜单 **Tools > Wheelchair > 重新生成仿真场景** 一键重建。
> 也可以把 `WheelchairBootstrap` 挂到任意空物体上，效果一样。

> **真实模型的贴图**：编辑器里 Play 会自动加载
> `Assets/Models/Wheelchair/Wheelchair_LOW_POLY.fbx` 并自动缩放摆正，
> 但**贴图要点一次菜单** **Tools > Wheelchair > 自动挂贴图** 才会挂上。
> 详见 `docs/unity-wheelchair-sim-guide.md` 第 12 节。

---

## 二、目录说明

```
Assets/Scripts/WheelchairSim/
├─ WheelchairCommand.cs      指令枚举 + 指令/状态数据结构
├─ WheelchairProtocol.cs     通信协议：二进制帧 + ASCII 文本行 + CRC8 + 流解析
├─ CommandSources.cs         指令源接口 + 本地键盘/按钮源
├─ TcpCommandSource.cs       TCP 指令源（内置服务器，默认 8888）
├─ SerialCommandSource.cs    串口指令源（PC 上配对蓝牙后的虚拟 COM 口）
├─ BluetoothCommandSource.cs 蓝牙源抽象 + 模拟蓝牙链路
├─ AndroidSppLink.cs         Android 经典蓝牙 SPP 实现（真机）
├─ WheelchairController.cs   差速运动模型
├─ StepCommander.cs          步进执行器（脑电链路：一条指令 = 一段有界动作）
├─ SafetyGuard.cs            心跳看门狗 / 障碍急停 / 急停锁存
├─ WheelchairHub.cs          中枢：按链路分流 → 步进/安全校验 → 驱动 → 回传状态
├─ WheelchairModelFactory.cs 用基本几何体拼一辆轮椅
├─ HudOverlay.cs             屏幕调试面板（OnGUI，零依赖）
└─ WheelchairBootstrap.cs    一键搭建场景

Assets/Editor/                     ← 编辑器专用，打包后不参与编译
├─ WheelchairTextureImporter.cs  PBR 贴图导入设置自动化
├─ WheelchairMaterialSetup.cs    一键自动挂 PBR 贴图（菜单 Tools > Wheelchair）
├─ WheelchairSceneBuilder.cs     一键重新生成仿真场景（菜单 Tools > Wheelchair）
└─ WheelchairFocusMode.cs        专注模式：按 F 收起多余窗口

Assets/Scenes/
└─ Main.unity                    唯一的场景：一个空物体 + WheelchairBootstrap 组件

Assets/Models/Wheelchair/
├─ Wheelchair_LOW_POLY.fbx   真实轮椅模型（合并网格，轮子不独立）
└─ Textures/                  5 张 PBR 贴图（BaseColor/Normal/Metallic/Roughness/AO）

Tools/
└─ host_test_client.py       PC 端测试客户端（发指令 + 收状态）
```

---

## 三、通信协议速查

### 二进制帧（真机推荐）

```
+------+------+-----+-----+-----+---------+------+
| 0xAA | 0x55 | LEN | SEQ | CMD | ARG x2  | CRC8 |
+------+------+-----+-----+-----+---------+------+
  LEN  = 4（SEQ + CMD + ARG 共 4 字节）
  ARG  = int16 小端，物理量 = ARG / 100.0
  CRC8 = 多项式 0x07、初值 0x00，覆盖 [LEN .. ARG] 共 5 字节
```

状态回传帧 `CMD = 0x80`，`LEN = 10`：
`SEQ(1) CMD(1) 电量(1) 线速度(2) 角速度(2) 航向(2) 标志(1)`

标志位：`bit0` 急停锁存 · `bit1` 故障 · **`bit2` 步进执行中（busy）**。
脑电上位机的节奏是：发一步指令 → 等 `bit2` 由 1 跳回 0 → 再判下一次意图。

### ASCII 行（手工调试最方便）

| 输入 | 含义 | 输入 | 含义 |
|---|---|---|---|
| `F` | 前进 | `RL` | 原地左旋 |
| `B` | 后退 | `RR` | 原地右旋 |
| `L` | 左转（行进中） | `S` | 停车 |
| `R` | 右转（行进中） | `X` | 急停 |
| `REL` | 解除急停 | `HB` / `PING` | 心跳 |
| `SPD:0.6` | 设定速度 m/s | `LIMIT:1.0` | 设定限速 m/s |
| `TURN:0.9` | 设定角速度 rad/s | `Q` | 请求回传状态 |
| `MODE:STEP` | 本链路改**步进式** | `MODE:LATCH` | 本链路改**状态保持式** |
| `SF` | 前进一步 | `SB` | 后退一步 |
| `SL` | 左转一步 | `SR` | 右转一步 |
| `STEP:1.0` | 设定步长 m | `SETSTEPANGLE:30` | 设定步转角 ° |
| `STEPMOVE:-0.5` | 走一段带符号位移 m | `STEPTURN:45` | 转一段带符号角度 ° |

行尾用 `\n` 或 `\r\n`，大小写不敏感。

> 远程链路**默认就是步进式**，所以用调试助手发 `F` 只会走 1 米。
> 这是有意为之（脑电链路），想连续控制先发 `MODE:LATCH`。

### 指令码表

| 名称 | 值 | 名称 | 值 |
|---|---|---|---|
| Forward | 0x01 | Stop | 0x07 |
| Backward | 0x02 | EmergencyStop | 0x08 |
| SteerLeft | 0x03 | ReleaseEmergency | 0x09 |
| SteerRight | 0x04 | SetSpeed | 0x0A |
| RotateLeft | 0x05 | SetSpeedLimit | 0x0B |
| RotateRight | 0x06 | SetTurnRate | 0x0C |
| Heartbeat | 0x0D | RequestStatus | 0x0E |

步进相关（脑电链路）：

| 名称 | 值 | 名称 | 值 |
|---|---|---|---|
| SetCommandMode | 0x0F | StepForward | 0x14 |
| StepMove | 0x10 | StepBackward | 0x15 |
| StepTurn | 0x11 | StepLeft | 0x16 |
| SetStepDistance | 0x12 | StepRight | 0x17 |
| SetStepAngle | 0x13 | | |

---

## 四、三种接入方式怎么开启

在 `WheelchairBootstrap` 的 Inspector 上打勾：

| 开关 | 用途 | 需要准备 |
|---|---|---|
| `enableLocalInput` | 键盘 + 屏幕按钮，默认开 | 无 |
| `enableMockBluetooth` | 模拟蓝牙，验证协议全链路，默认开 | 无 |
| `enableTcp` | 网络调试助手 / Python 客户端 / 手机 App | 设置 `tcpPort`（默认 8888） |
| `enableSerial` | **PC 上跑蓝牙最省事的方式**：蓝牙模块配对后生成虚拟 COM 口 | 填 `serialPortName`（如 COM5） |
| `enableAndroidBluetooth` | Android 真机 + 经典蓝牙 SPP | 填设备名与 MAC 地址 + 打包到真机 |

### 用 Python 客户端验证（不需要任何硬件）

1. Unity 里勾上 `enableTcp`，按 Play。
2. 命令行执行：

```bash
python Tools/host_test_client.py --host 127.0.0.1 --port 8888 --demo
```

3. 想手动一条条发，去掉 `--demo`；想测二进制帧加 `--binary`；
   想验证"心跳超时自动停车"加 `--no-heartbeat`。

---

## 五、安全机制（验收重点）

* **心跳看门狗**：远程链路连接后，超过 `heartbeatTimeout`（默认 2 s）没收到任何指令 →
  自动锁死停车。断线、程序卡死都不会让轮椅继续跑。
* **障碍物急停**：前方 `obstacleStopDistance`（默认 0.40 m）内有障碍 → 自动锁死停车。
* **急停锁存**：一旦锁死，必须显式发 `REL`（解除急停）才能恢复，
  避免指令抖动导致"停了又自己跑"。
* **限速**：`SetSpeedLimit` 可在运行时下调；速度 / 角速度都有硬上限与加速度平滑。
* **步进模式的三道护栏**（脑电链路）：
  * **不应期 1.0 s**：同一次注视被判成两条指令时，第二条在入口就被丢弃（防 SSVEP 误报）。
  * **障碍物联动**：本步方向上余量不足就缩短步长；缩短后不足 0.1 m 就整步拒绝。
  * **单步超时**：预计耗时 × 2 + 1.5 s，被卡住会自己放弃并停车。

---

## 六、常见问题

* **轮子转轴不对**：改 `WheelchairController.wheelSpinAxis`（默认 Y）或 `wheelSpinSign`。
* **键盘没反应**：工程若只启用了新版 Input System，键盘监听会被自动关闭，
  此时用屏幕按钮；或把 `Player Settings -> Active Input Handling` 改成 `Both`。
* **串口显示「未启用」**：串口依赖 `System.IO.Ports`，它不在 .NET Standard 里。
  工程默认会自动降级成空实现（不影响编译运行）；要用串口就把
  `Api Compatibility Level` 改成 `.NET Framework` 再按 Ctrl+R。
* **画面是粉红色**：材质 shader 没匹配到渲染管线，检查项目用的是 Built-in 还是 URP。
* **上坡时被「撞墙」拦停**：坡面法线曾被当成障碍物，已修正
  （`SafetyGuard.MaxClimbableSlopeAngle` 默认 30°，比它更缓的坡面不再算障碍）。
* **轮椅像玩具 / 原地转圈时绕到车身外面**：模型自身比例与原点问题，已由
  `autoFitModel` + `modelTargetHeight` 自动修正，Console 会打印适配日志。
* **`Q` / `E` 转向相反**：历史版本角速度符号与左右手坐标系不一致，已修正
  （`Q` 逆时针、`E` 顺时针）。
* **按 `Q` / `E` / `A` / `D` 只转 30° 就停（不是一直转）**：这是**步进式**的默认语义，
  和脑电指令一致——一条指令只转一步。想一直转就**按住 `Shift` 再按**，
  或者点左下角「手动控制」里的运动按钮（那几个按钮始终是状态保持式）。
* **按 `W` 只走 1 米**：同上，一步一停。连续前进请按住 `Shift` 再按 `W`。
* **连按两下只有一下生效**：不应期 1.0 s 会丢掉这期间的重复指令（防 SSVEP 误报），
  这是设计如此。想连续用 `Shift`。
* **按 `F` 没反应**：`F` 只在编辑器里有效，打包后没有该功能。
* **发 `F` 只走 1 米就停 / 连发两条只动一下**：这是脑电链路的**步进式**默认语义
  （外加 1.0 s 不应期会丢掉重复指令）。想连续控制先发 `MODE:LATCH`。
* **步进按了完全不动**：多半是前方障碍物余量不足，HUD 的「脑电步进」区会显示
  「整步拒绝」及原因；也可以直接看那一栏的队列 / 不应期 / 丢弃计数。
* **找不到步进参数**：都在运行时自动挂上的 `StepCommander` 组件上
  （步长 1.0 m、步转角 30°、步速 0.30 m/s、排队超时 5.0 s、不应期 1.0 s……），
  Play 之后在 Hierarchy 里选中轮椅那个物体即可看到。

---

## 七、下一步

* 真实模型已导入并**自动接入**：编辑器里 `wheelchairPrefab` 留空时会自动加载那个 FBX，
  自动缩放到 0.95 m 高、底部贴地、车心对齐出生点。贴图点一次菜单
  **Tools > Wheelchair > 自动挂贴图** 即可。注意合并网格的模型**轮子不会转**。
  打包 exe / APK 前记得把 FBX 资源本身拖到 `wheelchairPrefab` 字段上
  （编辑器自动加载那套打包后不生效）。详见 `docs/unity-wheelchair-sim-guide.md` 第 12 节。
* 按企业协议文档修改 `WheelchairProtocol`（帧头、指令码、CRC 算法、字节序）。
* 第二阶段：把 EEG 意图识别结果接到 `WheelchairHub.SubmitLocalCommand(...)`，
  即可完成 `EEG → 意图识别 → APP → 协议 → 轮椅` 闭环。
  推荐按 3.6 节的脑电节奏来：先 `MODE:STEP` 声明步进式，
  每判出一次意图发一条 `SF` / `SL` 这种一步指令，
  再等状态帧 `bit2`（busy）由 1 跳回 0 后开始判下一次——
  「判一次、走一步、停稳、再判」，单次误判最坏也只多走一步。
