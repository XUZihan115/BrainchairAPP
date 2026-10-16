# 脑控轮椅 · Unity 仿真工程

对应课题：**15 脑控轮椅 APP / 上位机开发**（翔宇医疗）第一阶段 —— 先做"虚拟轮椅 / 仿真环境"。

本工程实现：**接收外部指令（蓝牙 / 串口 / TCP / 本地键盘），驱动一辆虚拟轮椅完成
前进、后退、转向、原地旋转、停止、急停，并回传状态**。

---

## 一、3 步跑起来

1. 用 Unity Hub 打开本目录（或者新建一个 3D 工程，把本目录的 `Assets/` 内容复制进去）。
2. 新建一个空场景，在 Hierarchy 里 `Create Empty`，把这个脚本挂上去：
   `Assets/Scripts/WheelchairSim/WheelchairBootstrap.cs`
3. 按 **Play**。

地面、灯光、相机、轮椅、HUD 面板、指令来源都会自动创建。
用键盘 `W/S/A/D/Q/E/空格/X/Z`，或点屏幕左下角的面板按钮，就能看到轮椅动起来。

> 如果要换成本工程自己的场景：把 `WheelchairBootstrap` 挂到任意空物体上即可，
> 不需要手动建任何其它对象。

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
├─ SafetyGuard.cs            心跳看门狗 / 障碍急停 / 急停锁存
├─ WheelchairHub.cs          中枢：汇总指令 → 安全校验 → 驱动 → 回传状态
├─ WheelchairModelFactory.cs 用基本几何体拼一辆轮椅
├─ HudOverlay.cs             屏幕调试面板（OnGUI，零依赖）
└─ WheelchairBootstrap.cs    一键搭建场景

Assets/Editor/
└─ WheelchairTextureImporter.cs  PBR 贴图导入设置自动化（编辑器专用）
└─ WheelchairMaterialSetup.cs    一键自动挂 PBR 贴图（菜单 Tools > Wheelchair）

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

行尾用 `\n` 或 `\r\n`，大小写不敏感。

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

---

## 六、常见问题

* **轮子转轴不对**：改 `WheelchairController.wheelSpinAxis`（默认 Y）或 `wheelSpinSign`。
* **键盘没反应**：工程若只启用了新版 Input System，键盘监听会被自动关闭，
  此时用屏幕按钮；或把 `Player Settings -> Active Input Handling` 改成 `Both`。
* **串口显示「未启用」**：串口依赖 `System.IO.Ports`，它不在 .NET Standard 里。
  工程默认会自动降级成空实现（不影响编译运行）；要用串口就把
  `Api Compatibility Level` 改成 `.NET Framework` 再按 Ctrl+R。
* **画面是粉红色**：材质 shader 没匹配到渲染管线，检查项目用的是 Built-in 还是 URP。

---

## 七、下一步

* 真实模型已导入。把它挂到 wheelchairPrefab 字段，然后点菜单 **Tools > Wheelchair > 自动挂贴图** 一键挂好三张 PBR 贴图。详见 docs/unity-wheelchair-sim-guide.md 第 12 节（注意：合并网格的模型轮子不会转）。
* 按企业协议文档修改 `WheelchairProtocol`（帧头、指令码、CRC 算法、字节序）。
* 第二阶段：把 EEG 意图识别结果接到 `WheelchairHub.SubmitLocalCommand(...)`，
  即可完成 `EEG → 意图识别 → APP → 协议 → 轮椅` 闭环。