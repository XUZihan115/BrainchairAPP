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
- **两种指令语义**：**步进式**（默认：键盘、蓝牙 / 串口 / TCP 都是一条指令 = 走 1 m / 转 30° 后自动停，见 3.6）
  与状态保持式（按一下就一直走，键盘按住 `Shift`、HUD「手动控制」按钮、或发 `MODE:LATCH` 触发）
- 参数：**设定速度、设定限速、设定角速度、设定步长/步转角**
- 回传：**电量、线速度、角速度、航向、急停/故障标志、步进执行中标志**，定时广播

---

## 1. 你需要准备什么

| 项目 | 最低要求 | 说明 |
|---|---|---|
| Unity | **6000.6.6f1**（工程按此版本锁定；2022.3 LTS 以上亦可） | 用 Unity Hub 装，勾选 `Windows Build Support (IL2CPP)` |
| 编辑器代码 | 无 | 全部脚本零第三方依赖，不装任何插件 |
| Python（可选） | 3.8 以上 | 只用来跑 PC 端测试客户端，方便不发硬件就验证协议 |
| 串口/蓝牙（可选） | HC-05 / HC-06 之类 | 验证真链路时才需要 |

> **注意**：工程里只有一个**极简**场景文件 `Assets/Scenes/Main.unity`，
> 里面只有**一个空物体 + 一个 `WheelchairBootstrap` 组件**，别的什么都没有。
> 地面、灯光、相机、轮椅、坡道、障碍物、HUD 全部由 `WheelchairBootstrap.cs`
> 在运行时自动搭出来——这样场景文件只有 5KB，不会在 Unity 版本之间损坏，也不会产生合并冲突。

---

## 2. 搭建（照着做，大约 10 分钟）

### 2.1 打开工程

1. 打开 Unity Hub → `Add` → 选择 `unity-wheelchair-sim` 文件夹。
2. 如果 Hub 提示版本不匹配，选 `6000.6.6f1` 打开即可（脚本兼容范围很宽）。
3. 第一次打开会编译脚本，等右下角进度条跑完。

### 2.2 打开场景（推荐做法）

在 Project 窗口里双击 `Assets/Scenes/Main.unity` 打开即可。
场景里已经放好了一个空物体、并挂上了 `WheelchairBootstrap` 组件，什么都不用建。

> 找不到场景、或者被弄乱了？点菜单 **Tools > Wheelchair > 重新生成仿真场景** 一键重建。

<details>
<summary>备选做法：从空场景手工搭（不想用现成场景时看这里）</summary>

1. `File → New Scene`，选 `Basic (Built-in)` 或空场景都行。
2. 在 Hierarchy 面板空白处右键 → `Create Empty`，改名叫 `WheelchairSim`。
3. 把这个脚本拖到 `WheelchairSim` 上：
   `Assets/Scripts/WheelchairSim/WheelchairBootstrap.cs`
4. `Ctrl + S` 保存。

</details>

### 2.3 按 Play

场景里缺的东西会**全部自动创建**，你不需要手动建任何对象：

| 自动创建 | 说明 |
|---|---|
| 地面 | 80m × 80m 平面，够跑很久 |
| **坡道** | 正前方：上坡（约 9.5°）→ 平台 → 下坡，用来测爬坡和下坡 |
| 障碍物 | 3 面墙 + 2 个锥桶，用来测障碍物急停 |
| 轮椅 | 真实模型（找不到时退化成几何体拼的） |
| 跟随相机、灯光 | 相机自动跟车 |
| HUD 面板 | 右下角，显示速度、航向、电量、链路状态 |
| 各路指令来源 | 键盘、模拟蓝牙、（可选）TCP、串口、Android 蓝牙 |

---

## 3. 验证轮椅能动能停

### 3.1 用键盘（零依赖，最快）

焦点在游戏窗口里，按：

| 按键 | 一步一停（默认） | 按住 `Shift` 再按 |
|---|---|---|
| `W` 或 `↑` | 前进 **1 m** | 一直前进 |
| `S` 或 `↓` | 后退 **1 m** | 一直后退 |
| `A` 或 `←` | 原地左转 **30°** | 行进中左转（弧线） |
| `D` 或 `→` | 原地右转 **30°** | 行进中右转（弧线） |
| `Q` | 原地左旋 **30°**（俯视**逆时针**） | 一直原地左旋 |
| `E` | 原地右旋 **30°**（俯视**顺时针**） | 一直原地右旋 |
| `空格` | 停车 | 停车 |
| `X` | 急停（锁存） | 急停（锁存） |
| `Z` | 解除急停 | 解除急停 |

> 键盘默认是**步进式**：按一下 `W` 只走 1 m 就自动停，按一下 `Q` 只转 30° 就自动停。
> 这是**故意设计的**——让你在编辑器里就能用键盘直接模拟脑电指令
> （脑电用户一次只能给一条离散指令）。想要真轮椅那种连续控制，
> **按住 `Shift` 再按键**，那一下就走状态保持式（按一下就一直走），松开恢复一步一停。
> 也可以在 `WheelchairHub` 上把 **`StepModeForLocalInput`** 去勾（运行时改也立刻生效）。

> **`Q` / `E` 的左右是按「从上往下俯视」定义的**，和方向盘的直觉一致：
> `Q` = 车头向左偏（逆时针），`E` = 车头向右偏（顺时针）。
> 步进模式下 `A` / `D` 和 `Q` / `E` 等价，都是**原地**转 30°（弧线需要连续微调，
> 脑电用户做不到，所以步进模式不做弧线）；按住 `Shift` 时 `A` / `D` 才恢复成行进中转（弧线）。

### 3.2 用屏幕按钮

Play 之后左下角会出现 HUD 面板，直接点按钮即可。
面板上还会实时显示速度、航向、电量、链路状态、**键盘语义**、当前指令。
「手动控制」区的运动按钮**始终是状态保持式**（按住就一直走，配 `空格` 停），
「步进」区的四个按钮则是显式一步指令（`StepForward` 等），不受链路语义影响。

### 3.3 用 Python 客户端（验证协议层）

1. 选中 Hierarchy 里的 `Bootstrap`，在 Inspector 里把 **`enableTcp`** 打勾。
2. 按 Play（控制台会打印监听端口，默认 `8888`）。
3. 打开命令行，进到工程根目录执行：

```bash
python Tools/host_test_client.py --host 127.0.0.1 --port 8888 --demo
```

`--demo` 会按脚本自动演一遍：限速 → 加速 → 前进 → 左转 → 前进 → 停止 → 原地右旋 → 原地左旋 → 停车 → 后退 → 停车 → 急停 → 解除 → 停车
（前半段先发一次 `MODE:LATCH` 声明状态保持式，否则远程链路默认一步一停、演不出连续动作），
后半段再切到 `MODE:STEP` 演脑电节奏：一步 → 等状态帧 `bit2` 由 1 跳回 0 → 再走下一步。

想手动一条条发就去掉 `--demo`，然后输入 `F`、`SPD:0.6`、`X` 这类指令；
想验证二进制帧就加 `--binary`；想验证「心跳超时自动停车」就加 `--no-heartbeat`。

### 3.4 爬坡测试

Play 之后**车头正前方就是一条坡道**（`Ramp_Up` → `Ramp_Platform` → `Ramp_Down`），
按一下 `W` 直行开过去即可。坡道参数：

| 参数 | 值 |
|---|---|
| 坡度 | 约 **9.5°**（升 0.5m / 水平 3.0m，即约 1:6，比无障碍坡道标准略缓） |
| 半宽 | 1.0m（左右各 1m） |
| 平台高度 | 0.5m |

轮椅会**自动贴着坡面起伏**（`WheelchairController.followGround`，默认开启），
上下坡时车不会陷进坡里、也不会悬在半空。

两个关键设计：

1. **坡面不会被误判成障碍物**。安全模块默认「比 `MaxClimbableSlopeAngle`（30°）更陡的面
   才算障碍物」，所以 9.5° 的坡能正常爬上去。否则车一碰坡就被急停拦住了。
2. **爬不上去的陡面仍然挡停**。如果前面是一堵墙（90°）或超过 30° 的陡坡，
   仍然会被判定为障碍物锁停——这正是安全机制要的效果。

### 3.5 专注模式（一键收起多余窗口）

演示／录屏的时候，Unity 编辑器里一堆面板很挡视线。按 **`F`** 即可：

- 收起 `Hierarchy`、`Project`、`Inspector`、`Console`、`Profiler`、`Animation` 等面板
- 只留下 **Scene 和 Game** 两个窗口
- 再按一次 `F` 恢复原样

`F` 只对**编辑器**生效（打包成 exe / APK 后没有这个功能，也不影响运行）。
也可以在菜单里点：**Tools > Wheelchair > 专注模式（隐藏多余窗口）**。

> 小细节：如果你正要在 Scene 窗口里按 `F`（Unity 自带「聚焦选中物体」），
> 界面会自动让路给 Unity 原本的功能；在输入框里打字时按 `F` 也不会被抢走。

### 3.6 步进模式：把一条脑电指令变成「走 1 米就停」

这一节是给**脑电链路**准备的，也是整个课题最要紧的一处设计。

**问题**：`WheelchairController` 是**状态保持式**的——收到 `F` 就一直前进，
直到收到 `S` 或 `X`。而脑电指令天生是**离散**的：SSVEP 判定一次意图要盯着目标看 1~4 秒，
判完才输出一条指令。把这两种东西直接对接，后果是
**一次误判 = 轮椅自己一直往前开，直到撞上东西或用户反应过来再判一次「停止」**。
用户还只能盯着闪烁目标，根本没法一边盯着一边观察轮椅。

**方案**：`StepCommander` 串在 `Hub` 和 `SafetyGuard` 之间。一条指令只代表
**一段确定的位移或转角**，走完自动平滑停车，然后安静等下一次指令。
于是「一次误判」的代价被限定成「多走了 1 米，停住等你下一条」。

一条指令的完整生命周期：

```
到达 → 不应期检查 → 障碍物检查（可能缩短步长，甚至整步拒绝）
     → 立刻执行，或进"预输入"队列
     → 走满距离 / 角度 → 平滑停车 → 恢复原来的速度参数
```

#### 默认参数（都在 `StepCommander` 组件的 Inspector 上）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `StepDistance` | **1.0 m** | 前进一步 / 后退一步的距离 |
| `StepTurnAngle` | **30°** | 左转一步 / 右转一步的角度（正 = 逆时针 = 左转） |
| `StepSpeed` | 0.30 m/s | 步进时的线速度（1 m 约 3.3 s 走完，含起步约 3.9 s） |
| `StepTurnDegreesPerSecond` | 34 °/s | 步进时的转速（30° 约 0.9 s 转完） |
| `StepAcceleration` | 0.5 m/s² | 步进时的加速度，比常态的 1.2 更柔和，起步不顿车 |
| `QueueDepth` | 2 | **预输入**队列上限（0 = 关闭预输入） |
| `CommandTtl` | **5.0 s** | 排队指令最长等待多久，超时丢弃 |
| `RefractoryPeriod` | 1.0 s | **不应期**：间隔内重复来的指令一律丢弃 |
| `IdleStopTimeout` | 1.5 s | 一步走完且队列已空，再过这么久补发一次停车 |
| `ObstacleMargin` | 0.25 m | 本步方向上离障碍物不足这个余量就缩短步长 |
| `MinStepDistance` | 0.10 m | 缩短后小于这个值就**整步拒绝**（宁可不走也不贴上去） |
| `ProbeDistance` | 6.0 m | 障碍物探测的最远距离 |
| `EnableAudioCues` | 开 | 合成提示音（不需要任何音频素材） |

三个设计点值得单独说：

1. **不应期挡在「接受」而不是「执行」这一步**。
   如果只在开始执行时才挡，第二条会被当成预输入排队，最后照样走出来，等于没挡。
   SSVEP 误报的主要形态就是「同一次注视被判成两条」，所以必须在入口挡。
2. **`CommandTtl` 是 5.0 s，不是常见建议的 2.0 s**。
   1 m ÷ 0.3 m/s ≈ 3.3 s（算上加减速约 3.9 s）。TTL 若只有 2 s，
   排队的那条指令还没轮到执行就已经过期，「预输入」形同不存在。
   ⚠️ 这是本工程**唯一一处按实测耗时调过的默认值**，你随时可以在 Inspector 里改回去。
3. **听觉反馈**。用户要一直盯着闪烁目标，不能靠看屏幕确认指令有没有被接受。
   所以三种结果给三种声音（全部程序化合成，零素材）：

   | 声音 | 含义 |
   |---|---|
   | 单声高音（880 Hz / 0.06 s） | 指令已接受 |
   | 两声下行（660 → 440 Hz） | 这一步走完了 |
   | 低声长音（220 Hz / 0.14 s） | 指令被丢弃（不应期 / 堆队 / 有障碍） |

#### 两种语义按链路区分

| 链路 | 默认语义 | 为什么 |
|---|---|---|
| 蓝牙 / 串口 / TCP（远程） | **步进式** | 脑电指令是离散的 |
| 本地键盘 | **步进式** | 让你在编辑器里就能用键盘模拟脑电指令（一次一条） |
| HUD「手动控制」按钮 | 状态保持式 | 手动调试要连续，点一下就连续走 |
| HUD「步进」按钮 | 显式一步指令 | 直接走 `StepForward` 等，不受链路语义影响 |

键盘想要连续控制有两条路：

- **按住 `Shift`** 再按 `W` / `S` / `A` / `D` / `Q` / `E`——只有这一下按状态保持式执行
  （实现上是给这条指令打了 `CommandModeOverride.Latch` 标记，链路设置不变）。
- 在 `WheelchairHub` 上把 **`StepModeForLocalInput`** 去勾，键盘就整体回到状态保持式。
  这个开关是**动态**的：运行时改也立刻生效（没被 `MODE:` 显式改过的链路每帧都会回落到默认值）。

上位机连上后可以随时改**自己那条链路**的语义：

- `MODE:STEP` → 本链路改步进式（等价 `MODE:S` / `MODE:1`）
- `MODE:LATCH` → 本链路改状态保持式（等价 `MODE:HOLD` / `MODE:L` / `MODE:0`）
- 二进制：`SetCommandMode`（`0x0F`，`ARG` = 1 步进 / 0 保持）

改了只影响**发这条指令的那条链路**，不会把别的链路一起改掉。

另外，**显式步进指令不受链路模式限制**，任何时候都能用：
`SF` / `SB` / `SL` / `SR`（对应 `StepForward` … `StepRight`）。
「出门看一眼就走 1 米」这类场景直接用它们最省事。

#### 上位机怎么知道「这一步走完了」

状态回传帧的 `flags` 有 **`bit2` = busy**（见 5.2）：

- `bit2 = 1`：正在走这一步
- `bit2 = 0`：走完了，可以看下一眼了

脑电上位机的推荐节奏就是：发指令 → 等 `bit2` 由 1 跳回 0 → 再判下一次意图。
这一条比「等固定秒数」稳得多。

#### 落点为什么准 / 卡住了怎么办

- **落点准**：刹车时车还会滑行 `v² / (2a)` 米，代码把这个滑行距离提前换算成
  「还差这么多就下令停车」，所以停下来的位置才对得上 1 米。
- **单步超时**：单步最长执行时间 = 预计耗时 × 2 + 1.5 s。
  万一被贴墙、托空之类卡住，会自己放弃并停车，不会无限往前走。

#### 怎么亲手试一遍

1. **用键盘（最快）**：直接在游戏窗口按 `Q` / `E` / `A` / `D`，每按一下转 30° 后自动停；
   按 `W` / `S` 走 1 米后自动停。这就是步进式，和脑电一条指令的效果完全一致。
2. **点按钮**：Play 之后，屏幕左下角多了一栏「步进（脑电语义：走完自动停）」，
   四个按钮 —— `⇧ 步进前进`（1 m）、`⇩ 步进后退`（1 m）、`⟲ 步转左`（30°）、`⟳ 步转右`（30°）。
   点一下，看着它自己走完 1 米然后停住。
3. **走 TCP**：连上之后先发 `MODE:STEP`，再发 `F` —— 效果和 `SF` 一样，只走 1 米。
   再发 `MODE:LATCH`，`F` 就恢复成「一直前进直到 `S`」。
4. **看面板**：右上角多了一段「脑电步进」，实时显示键盘语义、执行中／剩余量、
   预输入队列 `n / 上限`、不应期剩余秒数、完成／丢弃计数、最近一次丢弃原因。

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
发 `F` 回车（注意勾选发送新行）就能看到轮椅**前进 1 米后自动停**——
远程链路默认就是步进式。想让它一直前进，先发一条 `MODE:LATCH` 再发 `F`。

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
| 标志 | `bit0` = 急停锁存，`bit1` = 故障，`bit2` = 步进执行中（busy，见 3.6） |

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
| `MODE:STEP` | 本链路改步进式 | `MODE:LATCH` | 本链路改状态保持式 |
| `SF` | 前进一步（1 m） | `SB` | 后退一步（1 m） |
| `SL` | 左转一步（30°） | `SR` | 右转一步（30°） |
| `STEP:1.0` | 设定默认步长 m | `SETSTEPANGLE:30` | 设定默认步转角 ° |
| `STEPMOVE:-0.5` | 走一段带符号位移 m | `STEPTURN:45` | 转一段带符号角度 ° |

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
| `RequestStatus` | 0x0E | — | — |

步进相关（脑电链路，见 3.6）：

| 名称 | 值 | 说明 |
|---|---|---|
| `SetCommandMode` | 0x0F | `ARG` 1 = 本链路步进式，0 = 状态保持式 |
| `StepMove` | 0x10 | 走一段带符号位移 m（正 = 前进） |
| `StepTurn` | 0x11 | 转一段带符号角度 °（正 = 左转） |
| `SetStepDistance` | 0x12 | 设定默认步长 m |
| `SetStepAngle` | 0x13 | 设定默认步转角 ° |
| `StepForward` | 0x14 | 前进一步（= `StepMove` + 当前步长） |
| `StepBackward` | 0x15 | 后退一步 |
| `StepLeft` | 0x16 | 左转一步（= `StepTurn` + 当前步转角） |
| `StepRight` | 0x17 | 右转一步 |

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
| `StepForward, arg=0, seq=4` | `AA 55 04 04 14 00 00 DE` |
| `SetCommandMode(步进), arg=1.0, seq=5` | `AA 55 04 05 0F 64 00 27` |
| `StepMove, arg=+1.00m, seq=6` | `AA 55 04 06 10 64 00 F8` |
| `StepTurn, arg=+30°, seq=7`（`ARG = 30×100`） | `AA 55 04 07 11 B8 0B F2` |
| 状态帧（电量87 / 0.62 / -0.25 / 123.4 / 急停） | `AA 55 0A 07 80 57 3E 00 E7 FF D2 04 01 42` |
| 状态帧（同上，但**步进执行中**，flags = 0x05） | `AA 55 0A 08 80 57 3E 00 E7 FF D2 04 05 08` |

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

### 6.1 步进模式额外带来的三道护栏（脑电验收重点）

步进模式本身就是**最大的一道安全改进**：它把「一次误判」的后果从
「轮椅一直往前开」压成「多走 1 米就停」。在此之上还有三道：

| 护栏 | 参数 | 行为 |
|---|---|---|
| 不应期（防误报） | `RefractoryPeriod = 1.0s` | 同一次注视被判成两条指令时，第二条在**入口**就被丢弃 |
| 障碍物联动 | `ObstacleMargin = 0.25m`<br>`MinStepDistance = 0.10m` | 本步方向上余量不足就**缩短步长**；缩短后不足 0.1 m 就**整步拒绝**。前后方向各自独立探测（倒退时车头传感器看不到车尾） |
| 单步超时（防卡死） | 预计耗时 × 2 + 1.5s | 被贴墙、托空等卡住时自动放弃并停车，不会无限走下去 |

这三道护栏都能在 HUD 的「脑电步进」区实时看到状态和丢弃原因，
验收时把「制造误报 / 制造障碍 / 制造卡住」三种场景各跑一遍、留张截图即可。

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
| 想换真实轮椅模型 | — | 把预制体拖到 `Bootstrap` 的 `wheelchairPrefab` 上（留空时编辑器里会自动加载工程自带的那台） |
| 直行上坡时被「撞墙」拦停 | 坡面法线被当成了障碍物 | 已修正：`SafetyGuard.MaxClimbableSlopeAngle`（默认 30°），比这更缓的坡面不再算障碍 |
| 轮椅上下坡时陷进坡里或浮在半空 | 贴地探测没生效 | `WheelchairController.followGround` 默认开；坡太高超过 `maxStepUp`（0.25m）也会被挡住 |
| 轮椅看起来像玩具、或原地转圈时绕到车身外面 | 模型自身比例／原点不在车身上 | 已由 `autoFitModel` + `modelTargetHeight` 自动修正，Console 会打印适配日志 |
| 按 `Q` / `E` 转向和直觉相反 | 历史版本角速度正负号与左右手坐标系不一致 | 已修正（`Q` 逆时针、`E` 顺时针）。若仍相反，先确认没误按 `A` / `D` |
| 按 `F` 没反应 | `F` 只在**编辑器**里有效 | 打包后没有这个功能；编辑器里也可以走菜单 `Tools > Wheelchair > 专注模式（隐藏多余窗口）` |
| 发 `F` 只走 1 米就停 | 远程链路**默认就是步进式** | 这是脑电链路的正常行为。想连续走就发 `MODE:LATCH`，或改用 HUD「手动控制」按钮 |
| 按 `Q` / `E` / `A` / `D` 只转 30° 就停，不是一直转 | **键盘默认也是步进式**（和脑电一致） | **按住 `Shift` 再按**这一下就走状态保持式；或把 `WheelchairHub.StepModeForLocalInput` 去勾 |
| 按 `W` 只走 1 米 | 同上，一步一停 | 连续前进按住 `Shift` 再按 `W`，或点 HUD「手动控制」里的前进按钮 |
| 连发两条「前进」只走了一步 | 第二条落进了不应期（1.0 s）被丢弃 | 这是防 SSVEP 误报的设计。确实需要连发就调小 `StepCommander.RefractoryPeriod` |
| 步进按了完全不动，HUD 显示「整步拒绝」 | 本步方向上障碍物余量不足 0.1 m | 移开障碍物，或调小 `ObstacleMargin` / `MinStepDistance` |
| 排队的指令一直不执行、最后消失 | 等待超过了 `CommandTtl`（5.0 s） | 调大 `CommandTtl`；HUD 会显示「排队超时丢弃」 |
| 听不到步进提示音 | 场景没有 `AudioListener`，或 `EnableAudioCues` 关了 | `StepCommander` 会自动补一个 `AudioListener`；确认编辑器没静音 |

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
| Unity 本体编译 | 直接读 Unity 自己的 `Logs/Editor.log` | **无 `error CS` / `warning CS`** |
| 本轮 4 项需求改动 | 复用 Unity 生成的 `.rsp` 重新编译运行时 + 编辑器两个程序集 | **0 错误 0 警告** |
| 本轮步进模式改动 | 同上（新增 `StepCommander.cs`，共 442 行源文件的运行时代码集） | **0 错误 0 警告**，`A.dll` 44,544 → 60,416 字节 |
| 旧版输入兼容 | 关掉新版 Input System 条件重新编译 | **0 错误** |
| Android 分支 | 用 `UNITY_ANDROID` 条件编译 `AndroidSppLink` | **0 错误** |
| 协议单元测试 | C# 回环测试（CRC / 编解码 / 分片粘包 / 抗干扰 / ASCII / 状态帧） | **60+ 断言全部通过** |
| 跨语言一致性 | Python 客户端复现 C# 全部协议向量 | **字节完全一致** |
| 端到端链路 | Python 客户端真实 TCP 连接仿真端，二进制模式心跳 | **全部为合法帧，无脏数据** |

---

## 11. 下一步（怎么接到课题第二阶段）

1. **换模型**：企业给了新的轮椅模型就把它拖到 `wheelchairPrefab` 上
   （现有的 FBX 已按第 12 节接好，留空时编辑器里会自动加载它）。
2. **换协议**：拿到企业的协议文档后，改 `WheelchairProtocol.cs`
   （帧头、指令码、CRC 算法、字节序都在这一个文件里）。
3. **接脑电**：把 EEG 意图识别的输出，调
   `WheelchairHub.SubmitLocalCommand(...)` 灌进来，
   就完成了 `EEG → 意图识别 → APP → 协议 → 轮椅` 的闭环。
   建议按第 3.6 节的脑电链路来：先发 `MODE:STEP` 声明成步进式，
   每判出一次意图发一条 `SF` / `SL` 这种一步指令，
   然后等状态帧的 `bit2`（busy）由 1 跳回 0，再开始判下一次意图——
   这样「判一次、走一步、停稳、再判」就是一条干净的闭环节奏，
   单次误判的最坏后果也只有一步。
4. **补验收记录**：按第 6 节的安全机制逐条测，留截图/录像当验收材料。

---

## 12. 导入真实轮椅模型（FBX + PBR 贴图）

工程里已经放好了一台真实轮椅模型 `Wheelchair_LOW_POLY.fbx` + 5 张 PBR 贴图，
在编辑器里按 Play 会自动加载并使用它（找不到时才退化成 `WheelchairModelFactory`
用几何体拼的那台）。下面说明文件怎么放、贴图怎么挂、以及这个模型的几个坑。

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

`Assets/Editor/WheelchairTextureImporter.cs` 是编辑器脚本，会在导入时自动套用正确设置；
`Assets/Editor/WheelchairMaterialSetup.cs` 是「一键挂贴图」菜单，见 12.4。

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

> **这个模型工程已经自动接好了**：`wheelchairPrefab` 留空时，
> `WheelchairBootstrap`（`useRealModelInEditor` 默认开启）会在编辑器里自动加载
> `Assets/Models/Wheelchair/Wheelchair_LOW_POLY.fbx`，并自动摆正尺寸和位置。
> 你**什么都不用拖**，直接按 Play 就是真实模型。

实测这个 FBX 的三个坑，以及工程的处理方式：

| 实测结果 | 直接拖进场景会怎样 | 工程怎么处理 |
|---|---|---|
| **朝向**：`UpAxis=1`（Y 向上）、`FrontAxis=2` 正向（车头朝 +Z） | 无问题，和 Unity 约定一致 | 不用处理。以后换模型若车头朝后，把 `modelYawOffset` 填 `180` |
| **尺寸**：顶点包围盒 `30.0 × 37.6 × 41.1`，`UnitScaleFactor = 1.0`（厘米制） | Unity 里只有约 **0.38m 高**，像台玩具 | `autoFitModel`（默认开）自动缩放到 `modelTargetHeight`（默认 0.95m，真轮椅高度） |
| **原点**：不在车身上（水平方向偏约 12cm） | 原地旋转时会绕车身**外面**甩 | `autoFitModel` 把**水平中心对齐出生点、底部贴地** |

运行时会打印一行确认日志：

```
[Bootstrap] 模型自动适配：原始高度 0.376 m -> 0.95 m（缩放 2.52 倍），底部已对齐地面
```

觉得太大／太小就改 `modelTargetHeight`（例如改成 `1.05`）；想自己手工调就把
`autoFitModel` 的勾去掉，再去改模型 `Model` 页的 `Scale Factor`。

**打包 exe 时必须手动拖一次**（编辑器自动加载那套在打包后不生效）：

1. 把 **Project 窗口里的 FBX 资源本身**（不是场景里的实例）
   拖到场景中 `WheelchairBootstrap` 的 `wheelchairPrefab` 字段上。
2. 运行：`WheelchairBootstrap` 会自动 `Instantiate` 这个模型、给它挂上 `WheelchairController`
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
├─ StepCommander.cs            步进执行器（脑电链路：一条指令 = 一段有界动作）
├─ SafetyGuard.cs              心跳看门狗 / 障碍急停 / 急停锁存 / 限速限幅
├─ WheelchairHub.cs            中枢：按链路分流 → 步进/安全校验 → 驱动 → 回传
├─ WheelchairModelFactory.cs   用基本几何体拼一辆轮椅
├─ HudOverlay.cs               屏幕调试面板（OnGUI，零依赖）
└─ WheelchairBootstrap.cs      一键搭建场景

Assets/Editor/                     （都是编辑器专用，打包后不参与编译）
├─ WheelchairTextureImporter.cs  PBR 贴图导入设置自动化
├─ WheelchairMaterialSetup.cs    一键把 PBR 贴图挂到材质上
├─ WheelchairSceneBuilder.cs     一键重新生成仿真场景
└─ WheelchairFocusMode.cs        专注模式：按 F 收起多余窗口

Assets/Scenes/
└─ Main.unity                    唯一的场景：一个空物体 + WheelchairBootstrap 组件

Assets/Models/Wheelchair/
├─ Wheelchair_LOW_POLY.fbx     真实轮椅模型（合并网格，轮子不独立）
└─ Textures/                    5 张 PBR 贴图（BaseColor/Normal/Metallic/Roughness/AO）

Tools/
└─ host_test_client.py         PC 端测试客户端（发指令 + 收状态）
```