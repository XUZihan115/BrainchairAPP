# 脑控轮椅仿真工程 · 搭建与使用指南

> 配套课题：**15 脑控轮椅 APP / 上位机开发**（翔宇医疗）
> 本工程 = 第一阶段交付物：**虚拟轮椅 / 仿真环境**
> 要打通的闭环：`EEG → 意图识别 → APP / 上位机 → 控制协议 → 轮椅执行 → 状态反馈`

---

## 0. 先搞清楚这个工程解决什么问题

真机调试有两个绕不开的麻烦：

1. **没硬件也能开发**：脑电帽、上位机、轮椅整车都很贵，不可能人手一套。
2. **真机试错危险**：控制协议写错了，真轮椅可能直接冲出去。

所以第一阶段用 Unity 做一辆**虚拟轮椅**，把「上位机 → 协议 → 轮椅」这条链路先跑通、跑对、跑稳。
等协议和安全逻辑都验证过了，第二阶段再换成真机通信，只改传输层，上层代码不用动。

工程已实现的能力：

- 接收指令来源：**本地键盘 / 屏幕按钮、TCP、串口、模拟蓝牙、Android 经典蓝牙 SPP**
- 动作：**前进、后退、行进中转向、原地旋转、停车、急停、解除急停**
- 参数：**设定速度、设定限速、设定角速度**
- 回传：**电量、线速度、角速度、航向、急停/故障标志**，定时广播

---

## 1. 你需要准备什么

| 项目 | 最低要求 | 说明 |
|---|---|---|
| Unity | **6000.6.6f1**（工程按此版本锁定；2022.3 LTS 以上亦可） | 用 Unity Hub 装，勾选 `Windows Build Support (IL2CPP)` |
| 编辑器代码 | 无 | 全部脚本零第三方依赖，不装任何插件 |
| Python（可选） | 3.8 以上 | 只用来跑 PC 端测试客户端，方便不发硬件就验证协议 |
| 串口/蓝牙（可选） | HC-05 / HC-06 之类 | 验证真链路时才需要 |

> **注意**：工程里没有 `.unity` 场景文件。这是故意的——场景由 `WheelchairBootstrap.cs`
> 在运行时自动搭出来，避免场景文件在 Unity 版本之间损坏导致合并冲突。

---

## 2. 搭建（照着做，大约 10 分钟）

### 2.1 打开工程

1. 打开 Unity Hub → `Add` → 选择 `unity-wheelchair-sim` 文件夹。
2. 如果 Hub 提示版本不匹配，选 `6000.6.6f1` 打开即可（脚本兼容范围很宽）。
3. 第一次打开会编译脚本，等右下角进度条跑完。

### 2.2 挂上引导脚本

1. `File → New Scene`，选 `Basic (Built-in)` 或空场景都行。
2. 在 Hierarchy 面板空白处右键 → `Create Empty`，改名叫 `Bootstrap`。
3. 把这个脚本拖到 `Bootstrap` 上：
   `Assets/Scripts/WheelchairSim/WheelchairBootstrap.cs`

### 2.3 按 Play

地面、灯光、跟随相机、轮椅、HUD 面板、障碍物、各路指令来源都会**自动创建**。
不需要手动建任何对象。

### 2.4 保存场景（可选但建议）

`Ctrl + S` 保存到 `Assets/Scenes/Main.unity`，下次直接打开就能跑。

---

## 3. 验证轮椅能动能停

### 3.1 用键盘（零依赖，最快）

焦点在游戏窗口里，按：

| 按键 | 动作 | 按键 | 动作 |
|---|---|---|---|
| `W` 或 `↑` | 前进 | `Q` | 原地左旋 |
| `S` 或 `↓` | 后退 | `E` | 原地右旋 |
| `A` 或 `←` | 行进中左转 | `空格` | 停车 |
| `D` 或 `→` | 行进中右转 | `X` | 急停（锁存） |
| `Z` | 解除急停 | | |

> 指令是**状态保持式**的：按一下 `W` 轮椅会一直前进，必须按 `空格`/`X` 才停。
> 这是故意设计的，和真轮椅的行为一致。

### 3.2 用屏幕按钮

Play 之后左下角会出现 HUD 面板，直接点按钮即可，效果和键盘一样。
面板上还会实时显示速度、航向、电量、链路状态、当前指令。

### 3.3 用 Python 客户端（验证协议层）

1. 选中 Hierarchy 里的 `Bootstrap`，在 Inspector 里把 **`enableTcp`** 打勾。
2. 按 Play（控制台会打印监听端口，默认 `8888`）。
3. 打开命令行，进到工程根目录执行：

```bash
python Tools/host_test_client.py --host 127.0.0.1 --port 8888 --demo
```

`--demo` 会按脚本自动演一遍：限速 → 加速 → 前进 → 左转 → 前进 → 停止 → 原地右旋 → 原地左旋 → 停车 → 后退 → 停车 → 急停 → 解除 → 停车。

想手动一条条发就去掉 `--demo`，然后输入 `F`、`SPD:0.6`、`X` 这类指令；
想验证二进制帧就加 `--binary`；想验证「心跳超时自动停车」就加 `--no-heartbeat`。

---

## 4. 接入真实上位机 / 手机 App

所有开关都在 `Bootstrap` 的 Inspector 上。

| 开关 | 用途 | 需要填什么 |
|---|---|---|
| `enableLocalInput` | 键盘 + 屏幕按钮 | 默认开，不用管 |
| `enableMockBluetooth` | 模拟蓝牙，验证协议全链路 | 默认开，不用管 |
| `enableTcp` | 网络调试助手 / Python / 手机 App | `tcpPort`（默认 8888） |
| `enableSerial` | **PC 上跑蓝牙最省事的方式** | `serialPortName`、`serialBaudRate` |
| `enableAndroidBluetooth` | Android 真机 + 经典蓝牙 SPP | `bluetoothDeviceName`、`bluetoothDeviceAddress` |

### 4.1 TCP

勾 `enableTcp`，用「网络调试助手」连 `127.0.0.1:8888`，
发 `F` 回车（注意勾选发送新行）就能看到轮椅前进。

### 4.2 串口（在 PC 上模拟蓝牙链路）

PC 上其实**不需要蓝牙插件**：蓝牙模块和电脑配对成功后，系统会生成一个虚拟 COM 口
（例如 `COM5`）。这时：

1. 勾 `enableSerial`，`serialPortName` 填那个 COM 口，`serialBaudRate` 填 `115200`。
2. 用串口助手打开**同一个** COM 口发指令即可。

> **串口功能需要额外设置**：把 `Player Settings → Other Settings → Api Compatibility Level` 改成 `.NET Framework`。
> Unity 6 默认是 `.NET Standard 2.1`，这个级别下 `System.IO.Ports` 不在运行时代码集里，
> 本工程会自动降级成空实现——**工程照常编译运行**（不弹报错），只是串口通道不可用，HUD 会显示「串口未启用」。

### 4.3 Android 真机蓝牙

1. 勾 `enableAndroidBluetooth`，填蓝牙模块的名字和 MAC 地址。
2. `Api Compatibility Level` 保持默认的 `.NET Standard 2.1` 即可——
   Android 蓝牙走的是 JNI 直连，不需要 `System.IO.Ports`。
3. 打包安装到手机，手机先和模块配对，再运行 App。

> 还需要在 Android 清单里声明蓝牙权限（`BLUETOOTH`、`BLUETOOTH_ADMIN`，
> Android 12+ 还要 `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`）。
> 直接在编辑器里跑时，`AndroidSppLink` 会自动空转，不会报错。

---

## 5. 通信协议完整规范

### 5.1 二进制指令帧（真机推荐）

```
+------+------+-----+-----+-----+---------+------+
| 0xAA | 0x55 | LEN | SEQ | CMD | ARG x2  | CRC8 |
+------+------+-----+-----+-----+---------+------+
```

| 字段 | 长度 | 说明 |
|---|---|---|
| 帧头 | 2 | 固定 `0xAA 0x55` |
| `LEN` | 1 | `SEQ + CMD + ARG` 的总字节数，指令帧固定 `4` |
| `SEQ` | 1 | 序号，`0-255` 循环，可用于丢包统计 |
| `CMD` | 1 | 指令码，见 5.4 |
| `ARG` | 2 | `int16` **小端**，物理量 = `ARG / 100.0` |
| `CRC8` | 1 | 多项式 `0x07`、初值 `0x00`，覆盖 `[LEN .. ARG]` 共 5 字节 |

指令帧整帧长度 = `2 + 1 + 4 + 1 = 8` 字节。

### 5.2 状态回传帧

`CMD = 0x80`，`LEN = 10`，整帧 `14` 字节：

```
SEQ(1) CMD(1) 电量(1) 线速度(2) 角速度(2) 航向(2) 标志(1)
```

| 字段 | 说明 |
|---|---|
| 电量 | `0-100`，无符号，实际百分比 |
| 线速度 | `int16` 小端，`/100` 得 m/s，正 = 前进 |
| 角速度 | `int16` 小端，`/100` 得 rad/s，正 = 左转 |
| 航向 | `uint16` 小端，`/10` 得度，`0-360` |
| 标志 | `bit0` = 急停锁存，`bit1` = 故障 |

> 解析器收到 `CMD >= 0x80` 的帧会**整帧丢弃**，不会当成指令执行。
> 这是防止回传帧被回环回来误触发动作的关键保护。

### 5.3 ASCII 行（手工调试最方便）

以 `\n` 或 `\r\n` 结尾，大小写不敏感。

| 输入 | 含义 | 输入 | 含义 |
|---|---|---|---|
| `F` | 前进 | `RL` | 原地左旋 |
| `B` | 后退 | `RR` | 原地右旋 |
| `L` | 左转（行进中） | `S` | 停车 |
| `R` | 右转（行进中） | `X` | 急停 |
| `REL` | 解除急停 | `HB` / `PING` | 心跳 |
| `SPD:0.6` | 设定速度 m/s | `LIMIT:1.0` | 设定限速 m/s |
| `TURN:0.9` | 设定角速度 rad/s | `Q` / `?` | 请求回传状态 |

`ProtocolMode.Auto`（默认）会按**第一个有效字节**自动判定走二进制还是 ASCII：
首字节 `0xAA` → 二进制；首字节 `0x21-0x7E` → ASCII。判定后**锁定**，不会中途切换。

### 5.4 指令码表

| 名称 | 值 | 名称 | 值 |
|---|---|---|---|
| `None` | 0x00 | `Stop` | 0x07 |
| `Forward` | 0x01 | `EmergencyStop` | 0x08 |
| `Backward` | 0x02 | `ReleaseEmergency` | 0x09 |
| `SteerLeft` | 0x03 | `SetSpeed` | 0x0A |
| `SteerRight` | 0x04 | `SetSpeedLimit` | 0x0B |
| `RotateLeft` | 0x05 | `SetTurnRate` | 0x0C |
| `RotateRight` | 0x06 | `Heartbeat` | 0x0D |
| | | `RequestStatus` | 0x0E |

### 5.5 跨语言对齐向量（写 App 时拿这几个数对表）

C# 端和 Python 端已经互相验证过，**字节完全一致**。你写 Android / 上位机时
应该产出同样的字节，对不上就说明协议实现有问题。

| 用例 | 期望字节（十六进制） |
|---|---|
| `CRC8("123456789")` | `0xF4` |
| `Forward, arg=0.50, seq=1` | `AA 55 04 01 01 32 00 21` |
| `RotateRight, arg=0, seq=2` | `AA 55 04 02 06 00 00 DE` |
| `SetSpeedLimit, arg=1.0, seq=3` | `AA 55 04 03 0B 64 00 F8` |
| `Heartbeat, arg=0, seq=0` | `AA 55 04 00 0D 00 00 1E` |
| 状态帧（电量87 / 0.62 / -0.25 / 123.4 / 急停） | `AA 55 0A 07 80 57 3E 00 E7 FF D2 04 01 42` |

---

## 6. 安全机制（验收重点）

| 机制 | 默认值 | 行为 |
|---|---|---|
| 心跳看门狗 | `heartbeatTimeout = 2.0s` | 远程链路**连上之后**超时没收到任何指令 → 锁死停车 |
| 障碍物急停 | `obstacleStopDistance = 0.40m` | 前方射线检测到障碍 → 锁死停车 |
| 急停锁存 | — | 一旦锁死，必须显式发 `REL`/`Z` 才能恢复 |
| 限速 | `speedLimit = 1.0 m/s` | 运行时可通过 `LIMIT:` / `SetSpeedLimit` 下调 |
| 角速度限幅 | `AngularLimit = 1.5 rad/s` | `TURN:` 会被夹到 `±1.5`，防止原地疯转 |
| 加减速平滑 | `linearAcceleration = 1.2` | 不会瞬间跳到目标速度，更接近真车 |

**为什么需要看门狗**：如果连接断了、上位机崩了、蓝牙掉线了，
真轮椅不能保持上一个速度一直冲。看门狗就是「没人管我，我就停」。

**为什么急停要锁存**：如果只做瞬时急停，那么通信抖动一下、
急停信号晚到几帧，车就可能「停了又自己跑起来」。
锁存后必须人工显式解除，安全得多。

> 本地键盘源**不**参与心跳看门狗（`RequiresHeartbeat = false`），
> 否则你自己坐电脑前不按键，车就自己锁死了。

---

## 7. 打包到 Android

1. `File → Build Settings` → 选 `Android` → `Switch Platform`。
2. `Player Settings`：
   - `Other Settings → Api Compatibility Level` = `.NET Standard 2.1`（Android 蓝牙走 JNI，不需要 `System.IO.Ports`）
   - `Other Settings → Scripting Backend` = `IL2CPP`
   - `Other Settings → Target Architectures` 勾 `ARM64`
   - `Active Input Handling` = `Both`（键盘功能才会编译进去）
3. `Build` 出 APK，装到手机。

---

## 8. 常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| 键盘按了没反应 | 工程只启用了新版 Input System | `Active Input Handling` 改成 `Both`；或先用屏幕按钮 |
| 画面全是粉红色 | 材质 shader 和渲染管线不匹配 | 确认项目是 Built-in 还是 URP，`MakeMaterial` 会按管线顺序找 shader；找不到会退化成默认材质而不是报错 |
| HUD 里串口显示「串口未启用」 | API 兼容层级是 `.NET Standard 2.1`，`System.IO.Ports` 不在运行时代码集 | 不用串口就不用管；要用就改成 `.NET Framework` 再按 Ctrl+R |
| 轮椅动了一下就停 | 触发了心跳看门狗 | 远程源要持续发 `HB` 或任意指令；或去掉 `--no-heartbeat` |
| 轮椅不动，HUD 显示「急停」 | 之前按过 `X` 或撞到障碍 | 按 `Z`（或发 `REL`）解除，并把障碍物移开 |
| 轮子转轴方向不对 | 模型朝向不同 | 调 `WheelchairController` 的 `wheelSpinAxis`（默认 Y）、`wheelSpinSign`（默认 -1） |
| 想换真实轮椅模型 | — | 把预制体拖到 `Bootstrap` 的 `wheelchairPrefab` 上（留空才自动拼几何体） |

---

## 9. 材料清单（采购建议）

| 类别 | 建议型号 / 规格 | 数量 | 用途 |
|---|---|---|---|
| 蓝牙模块 | HC-05（主从一体）或 HC-06 | 2 | 一个做轮椅端，一个做调试；先跑透 SPP |
| USB 转串口 | CH340 / CP2102 | 2 | 没有蓝牙时先验证串口链路 |
| 开发板 | 树莓派 4B / ESP32 / STM32 板 | 1 | 第二阶段接真轮椅的电机控制器 |
| 电机驱动 | 双路 H 桥（如 BTS7960 / TB6612） | 1 | 驱动轮椅左右两个电机 |
| 电池 | 24V 锂电池组 + 保护板 | 1 | 真机供电 |
| 急停开关 | 常闭型蘑菇头急停 | 1 | **真机必备**，硬件层面的最后一道保护 |
| 超声波/激光雷达 | HC-SR04 / RPLIDAR A1 | 1 | 前方障碍检测，对应软件里的 `obstacleStopDistance` |
| 手机 | Android 8.0 以上 | 2 | 1 台跑 App（上位机），1 台录屏验收 |

> 第二阶段真正上车之前，**务必**先把硬件急停开关装好并验证。
> 软件急停不能代替硬件急停。

---

## 10. 本工程已完成的验证（质量证据）

| 验证项 | 方法 | 结果 |
|---|---|---|
| 编辑器编译 | Unity 自带 .NET SDK 8 编译全部脚本 | **0 错误** |
| 旧版输入兼容 | 关掉新版 Input System 条件重新编译 | **0 错误** |
| Android 分支 | 用 `UNITY_ANDROID` 条件编译 `AndroidSppLink` | **0 错误** |
| 协议单元测试 | C# 回环测试（CRC / 编解码 / 分片粘包 / 抗干扰 / ASCII / 状态帧） | **60+ 断言全部通过** |
| 跨语言一致性 | Python 客户端复现 C# 全部协议向量 | **字节完全一致** |
| 端到端链路 | Python 客户端真实 TCP 连接仿真端，二进制模式心跳 | **全部为合法帧，无脏数据** |

---

## 11. 下一步（怎么接到课题第二阶段）

1. **换模型**：把 `WheelchairModelFactory` 换成企业给的真实轮椅模型
   （拖到 `wheelchairPrefab`）。
2. **换协议**：拿到企业的协议文档后，改 `WheelchairProtocol.cs`
   （帧头、指令码、CRC 算法、字节序都在这一个文件里）。
3. **接脑电**：把 EEG 意图识别的输出，调
   `WheelchairHub.SubmitLocalCommand(...)` 灌进来，
   就完成了 `EEG → 意图识别 → APP → 协议 → 轮椅` 的闭环。
4. **补验收记录**：按第 6 节的安全机制逐条测，留截图/录像当验收材料。

---

## 12. 导入真实轮椅模型（FBX + PBR 贴图）

工程默认用基本几何体拼一辆轮椅（`WheelchairModelFactory`），只是为了先把逻辑跑通。
拿到真实模型后按下面做。

### 12.1 文件放哪

```
Assets/Models/Wheelchair/
├─ Wheelchair_LOW_POLY.fbx
└─ Textures/
   ├─ wheelchair_Base_Color.png
   ├─ wheelchair_Normal_OpenGL.png
   ├─ wheelchair_Metallic.png
   ├─ wheelchair_Roughness.png
   └─ wheelchair_Mixed_AO.png
```

丢进去后等 Unity 转完（45MB，第一次要等一会儿），再往下操作。

### 12.2 贴图导入设置（已自动处理）

`Assets/Editor/WheelchairTextureImporter.cs` 是编辑器脚本，会在导入时自动套用正确设置：
`Assets/Editor/WheelchairMaterialSetup.cs   一键把 PBR 贴图自动挂到材质上（编辑器专用）

| 贴图 | Texture Type | sRGB | 说明 |
|---|---|---|---|
| `*_Base_Color` | Default | 保持开启 | 颜色贴图 |
| `*_Normal_OpenGL` | **Normal map** | 自动关闭 | Unity 默认就是 OpenGL 约定，不用翻绿通道 |
| `*_Metallic` / `*_Roughness` / `*_Mixed_AO` | Default | 设为关闭 | 这些是「数据」不是「颜色」，开 sRGB 会让金属度/粗糙度失真 |

同时统一压到 2048（Android 单独 1024 + ASTC）。原图五张都是 4096×4096、合计 45MB，
不压的话手机端显存吃不住。

> 改了脚本之后想重新套用：在贴图上右键 → `Reimport`。

### 12.3 建材质

1. 在 `Assets/Models/Wheelchair/` 下右键 → `Create → Material`，命名 `WheelchairMat`。
2. 按渲染管线挂贴图：
   - **URP**（`Universal Render Pipeline/Lit`）：`Base Map` ← Base_Color，`Normal Map` ← Normal_OpenGL，`Occlusion` ← Mixed_AO
   - **Built-in**（`Standard`）：`Albedo` ← Base_Color，`Normal Map` ← Normal_OpenGL，`Occlusion` ← Mixed_AO
3. 金属度/光滑度：素材给的是分开的 `Metallic` 和 `Roughness` 两张灰度图，
   而 Unity 要求「金属度(R) + 光滑度(A)」合在同一张图里。
   做课程 demo 直接用滑条就行：**Metallic ≈ 0.6、Smoothness ≈ 0.4**。
   要还原度更高就把两张合成一张（金属度进 R 通道，`1 - 粗糙度` 进 A 通道）。

### 12.4 让它变成轮椅车身

1. 把 `Wheelchair_LOW_POLY.fbx` 拖进场景看一眼：
   - 朝向：这个 FBX 是 **Y-up、厘米制**（实测 `UnitScaleFactor = 1.0`），
     Unity 默认导入设置就是对的，**不用调**
   - 比例：正常应该约 1.1m 高。差太多就调 `Model` 页的 `Scale Factor`
   - 若模型是趴着的或侧躺的，那说明朝向和实测不符，调 `Model` 页的 `Rotation`
2. 确认没问题后，把 **Project 窗口里的 FBX 资源本身**（不是场景里的实例）
   拖到场景中 `WheelchairBootstrap` 的 `wheelchairPrefab` 字段上。
3. 运行：`WheelchairBootstrap` 会自动 `Instantiate` 这个模型、给它挂上 `WheelchairController`
   和 `Rigidbody`，并递归查找名叫 `WheelL` / `Wheel_L` / `LeftWheel` / `DriveWheelL`
   的子物体作为左驱动轮（右轮同理）。找不到也不会报错，只是轮子不转。

#### 偷懒做法：一键自动挂图

不想手工拖？点菜单 **Tools > Wheelchair > 自动挂贴图** 就行。

它会自动完成上面第 3 步的全部事情：

1. 找到模型**实际在用**的材质（FBX 内嵌的那个），也顺手填好你自己建的 `wheelchair_material.mat`；
2. `wheelchair_Base_Color` 挂到 Albedo，`wheelchair_Normal_OpenGL` 挂到 Normal Map，`wheelchair_Mixed_AO` 挂到 Occlusion；
3. 自动打开法线图必需的 `_NORMALMAP` 关键字——这一步**很关键**：手工拖拽时是 Unity 编辑器帮你偷偷打开的，如果只挂图不开关键字，法线图不会参与光照计算，等于白挂；
4. 把 Metallic 设成 0.6、Smoothness 设成 0.4。

点完直接按播放按钮看效果。
**前提：`wheelchair_Base_Color.png` 必须在 `Assets/Models/Wheelchair/Textures/` 里**，缺了脚本会弹窗提示你。

`wheelchair_Metallic.png` 与 `wheelchair_Roughness.png` 脚本故意不挂，原因见 12.3。

### 12.5 注意：这份 FBX 的轮子不会转

实测 `Wheelchair_LOW_POLY.fbx` 里只有 **1 个 Model / 1 个 Mesh**，
整台轮椅是**一坨合并的网格**，没有任何子物体。Unity **没法让网格的一部分单独旋转**，
所以两个轮子不会跟着滚。

不会报错（`WheelchairController` 对空的轮子引用有保护，直接跳过旋转逻辑），
车身照样正常平移和转向，只是轮子静止。三种处理：

| 方案 | 成本 | 效果 |
|---|---|---|
| **A. 先不管**（建议先用这个） | 0 | 车身照常动，轮子不滚 |
| **B. Blender 拆轮子** | 约 10 分钟 | 轮子真滚，效果最好 |
| C. 换一个带独立轮子的模型 | 看素材 | — |

**方案 B 的做法**（Blender 免费）：

1. Blender 新建工程 → `File → Import → FBX`，选这个 fbx
2. 选中轮椅，`Tab` 进编辑模式
3. 鼠标悬停在一个轮子上按 `L` 选中该轮全部相连面 → `P` → `Selection`
   （两个轮子各做一次）
4. 把拆出来的两个物体改名成 `WheelL` 和 `WheelR`（工程按这个名字自动识别）
5. 确认每个轮子的**原点在轮毂中心**（`Object → Set Origin → Origin to Geometry`），
   否则轮子会绕错轴甩出去
6. `File → Export → FBX` 覆盖导出，回 Unity 等它重新导入
7. 运行，轮子就会转了

> 轮子转轴默认是 `Y`（`WheelchairController.wheelSpinAxis`）。
> 转反了就改 `wheelSpinSign`（默认 `-1`），或换轴。
---

## 附：文件职责一览

```
Assets/Scripts/WheelchairSim/
├─ WheelchairCommand.cs        指令枚举 + 指令/状态数据结构
├─ WheelchairProtocol.cs       二进制帧 + ASCII 行 + CRC8 + 流解析与自动识别
├─ CommandSources.cs           指令源接口 + 本地键盘/按钮源
├─ TcpCommandSource.cs         TCP 指令源（内置服务器）
├─ SerialCommandSource.cs      串口指令源（PC 上蓝牙的虚拟 COM 口）
├─ BluetoothCommandSource.cs   蓝牙源抽象 + 模拟链路
├─ AndroidSppLink.cs           Android 经典蓝牙 SPP（真机）
├─ WheelchairController.cs     差速运动模型
├─ SafetyGuard.cs              心跳看门狗 / 障碍急停 / 急停锁存 / 限速限幅
├─ WheelchairHub.cs            中枢：汇总 → 安全校验 → 驱动 → 回传
├─ WheelchairModelFactory.cs   用基本几何体拼一辆轮椅
├─ HudOverlay.cs               屏幕调试面板（OnGUI，零依赖）
└─ WheelchairBootstrap.cs      一键搭建场景

Assets/Editor/
└─ WheelchairTextureImporter.cs  PBR 贴图导入设置自动化（编辑器专用）

Assets/Models/Wheelchair/
├─ Wheelchair_LOW_POLY.fbx     真实轮椅模型（合并网格，轮子不独立）
└─ Textures/                    5 张 PBR 贴图（BaseColor/Normal/Metallic/Roughness/AO）

Tools/
└─ host_test_client.py         PC 端测试客户端（发指令 + 收状态）
```