# Technical Design: BrainchairAPP（脑控轮椅系统）

**版本：** v0.2 · **日期：** 2026-10-18 · **状态：** Draft（开发前草图，首次原型后修订）
**对齐指南：** Project Documentation Guidelines §6（Brief Technical Design）
**配套文档：** `docs/Proposal-BrainchairAPP.md`（证据）· `docs/PRD-BrainchairAPP-MVP.md`（FR-01~FR-04 需求与验收）· `docs/signal-simulation-design-BrainchairAPP.md`（WiFi 协议 v0.2）· `docs/bluetooth-protocol-BrainchairAPP.md`（蓝牙协议 v0.2，实体预留）· `docs/simulation-safety-design-BrainchairAPP.pdf`（安全设计）· `docs/tasks-*-BrainchairAPP.md`（三端任务书）

---

## 1. 系统结构（System Structure）

三端架构：A 手机模拟脑电信号源 → B 手机上位机（决策中枢）→ C 执行端（MVP 用 Unity 虚拟轮椅验证；后续可替换为真实轮椅电机）。全链路零硬件，WiFi 局域网 UDP。

```
┌──────────────┐  UDP 9000   ┌──────────────┐  UDP 9001   ┌──────────────┐
│ A 脑机信号源  │ ──────────► │ B 上位机      │ ──────────► │ C 执行端      │
│ SSVEP 模拟    │ 脑机帧 10Hz │ 校验→判定→指令│  指令帧       │ 运动执行/回传  │
└──────────────┘             └──────┬───────┘              └──────┬───────┘
                                    │  UDP 9002 状态帧 5Hz（可选）    │
                                    └───────────────────────────────┘
```

**组件 → 需求映射与四问**（对每个主要组件：支持哪个需求？为何必要？更简替代？失败后果？）

| 组件 | 支持需求 | 必要性 | 更简替代 | 失败后果 |
|---|---|---|---|---|
| A 信号源（SSVEP 刺激界面+意图管理+发送器） | FR-01 | 无真实脑电帽，必须模拟解码输出才能驱动链路 | 手动输入框发帧（更简陋，无法演示「注视选择」） | 不发帧 → B 看门狗提示断链 |
| B 上位机（校验/置信度判定/双模式/急停） | FR-02 | 决策中枢，演示核心 | A 直连 C（跳过决策层，则不演示脑控判定逻辑） | 判定错误 → 误动作/拒绝执行 |
| C 执行端 Unity（指令解析/运动控制/看门狗/场景） | FR-03 | 展示「执行→回显」闭环 | 无执行端（只有 App，评委看不到运动） | 不收帧 1s → 看门狗自动停车 |
| 现场组网（热点+手动兜底） | FR-04 | 演示必须可回退 | 无兜底（断线即翻车） | 断线 → 提示+停车+手动接管 |

## 2. 技术选型（Technology Choices）

| 端 | 选择 | 理由（技能/环境/约束） | 更简替代讨论 |
|---|---|---|---|
| A/B | Android · Kotlin · Android Studio | 官方免费模板，零基础可跟教程；UDP 用 `java.net.DatagramSocket` 约 30 行 | Java 同等可行（中文资料更多）；若组员 Java 上手更快可换，接口不变 |
| C（MVP） | Unity 2021.3 LTS+ · C# | 免费；3D 效果好；组内有场景建模分工；`UdpClient` 简单 | 网页 Three.js 3D：浏览器 UDP 受限需中转服务，反而复杂；Python VPython：效果弱、演示观感差 |
| 网络 | UDP 原始 socket（9000/9001/9002） | 无连接、10Hz 小帧、丢帧可接受 | TCP：连接管理+粘包处理更复杂；MQTT：需 broker，属过度工程 |
| C（实体预留） | Arduino/Pico + L298N/DRV8825 | 后续实装阶段再定，此处仅预留接口 | — |

**未添加**（指南：需求不证明则不加）：认证、数据库、支付、微服务、公共托管、产品内 AI 功能。

## 3. 数据模型（Data Model）

**跨端消息实体（三类帧，协议 v0.2）——唯一的数据实体，无持久化。**

| 实体 | 关键字段 | 来源→去向 | 频率 |
|---|---|---|---|
| 脑机帧 0x01 | 意图码(1B)：0x01前进/0x02后退/0x03左转/0x04右转/0x05停止 · 置信度(1B 0~100) · 预留(1B) · 时间戳(4B 秒级) | A→B，UDP 9000 | 10Hz |
| 指令帧 0x02 | 指令码(1B)：0x01~0x06（0x06 急停仅 B 触发）· 速度(1B 0~100) · 转向强度(1B) · 序列号(1B 0~255 自增) | B→C，UDP 9001 | 事件驱动 |
| 状态帧 0x03 | 速度 · 位置(x,z) · 运行状态 · 时间戳 | C→B，UDP 9002（可选） | 5Hz |

**关系：** A 每 100ms 产出一条脑机帧 → B 校验后（脑控模式）按置信度判定产出一条指令帧 → C 执行并把运动状态回传为状态帧。序列号用于丢帧检测。

**持久化：** **Not applicable** —— 纯局域网实时演示，无存储需求；状态页只读内存态。后续实装如需调试日志，用本地文件即可，不建数据库。

**访问规则：** 帧头 `0xAA 0x55`/帧尾 `0x0D 0x0A`/长度/校验不符即丢弃；端口各自隔离；急停指令优先级最高（急停 > 手动 > 脑控）。

## 4. 关键实现方法（Key Implementation Approaches）

**需求 → 模块映射：**

| 需求 | 模块/实现要点 |
|---|---|
| FR-01 | A 端意图管理器 + 置信度模拟器 + 帧封装发送；**4 秒单指令周期**：按一次连发该意图帧 4s（依据 arXiv:2106.11008 单指令 4.015s），同键 4s 内防抖、异键立即切换；**4s 结束自动改发停止帧 0x05**（否则 B 看门狗 1s 误报断链） |
| FR-02 | B 端置信度判定器**集中一处**：<30 拒绝发 0x05；≥30 执行，速度=(置信度-30)/70×100（仅前进/后退）；意图切换连续 3 帧确认；手动瞬间接管、30s 恢复；急停 0x06 连发 3 次锁止；1s 看门狗 |
| FR-03 | Unity `UdpClient` 收 9001 → 运动控制器（位移/转向/轮旋转动画）→ 1s 看门狗停车 → 坑地停车/超坡停车/下坡限速（见 safety PDF）→ 状态帧回传 |
| FR-04 | 热点组网 ≤3 步（写入演示脚本）；断线时 B 提示 + C 自动停车；全程可切手动 |

**关键接口（重要，非穷举）：**

```
InputSource { start(ip,port); stop(); onFrame(cb) }   // B 端输入抽象
├── WifiUdpSource    // MVP 默认
└── BleSerialSource  // 预留：SPP/BLE UART → 统一脑机帧（bluetooth-protocol v0.2）

Executor { execute(指令帧); stop(); watchdogReset() } // C 端执行抽象
├── UnityVirtualExecutor   // MVP
└── WheelchairAdapter      // 实体预留（§8）
```

## 5. 失败处理（Failure Handling）

| 场景 | 行为 |
|---|---|
| 错帧/坏帧（校验、长度不符） | B/C 端丢弃并计数，不计入 3 帧确认 |
| B 1s 收不到脑机帧 | 提示「信号丢失」+ 自动发停止指令 |
| C 1s 收不到指令帧 | 看门狗自动停车（与 B 端双保险） |
| 急停触发 | 0x06 连发 3 次 + 锁止，手动复位后恢复 |
| 未连接时空操作 | 控件置灰 + 提示「未连接」 |
| 蓝牙断连（预留通道） | 视同信号丢失，走断链流程 |
| 必需资源缺失（热点无网/手机未装 App） | 演示脚本含「3 步组网」与手动兜底，A/B 可换设备重连 |

## 6. 运行与验证（Run and Verification Instructions）

**先决条件：** 2 台 Android 手机 + 1 台 Windows；Android Studio；Unity Hub + Unity 2021.3+；手机开启热点。

**运行步骤（演示现场）：**
1. Windows 开 Unity → 打开工程 → Play → 轮椅场景就位（端口 9001）
2. 手机 1 跑 A 端 App → 配置 B 端 IP → 点「开始模拟」（UDP 9000，10Hz）
3. 手机 2 跑 B 端 App → 连接页设本机端口 → 连热点 → 选「脑控」→ 进入控制页

**验证检查（开发期）：**
- 单测：帧封装/校验/置信度判定边界（29/30/100）
- 联调：A→B 日志对上帧计数；B→C 指令生效；C→B 状态回显
- 演示指标：全链路连续 ≥3 次无中断；端到端时延 ≤300ms（秒表/录屏）
- 安全演示：急停、断链停车、坑地停车均现场可演示

**命令（未实机执行，标注未验证）：**
- `adb install -r app-debug.apk`（A/B 端装手机；*unverified until executed*）
- Unity 命令行批量出包 `Unity -batchmode -quit -projectPath … -buildTarget Android`（*unverified*，MVP 期 Play 模式演示即可）

## 7. 部署与成本（Deployment and Costs）

- **运行位置：** 课程演示现场，手机热点局域网，**本地执行**（无云端、无公共托管）
- **控制方：** 项目组 5 人；配置项仅 B 端 IP/端口（连接页可改）
- **成本：** 0 元（Android Studio/Unity Personal/GitHub 免费）；可选蓝牙小车 ≤100 元加分项
- **限制：** 依赖热点覆盖范围（约 10m 演示区）；实体化阶段硬件成本另议

## 8. 实体化演进（未来方向，非默认交付物，用户明确需求）

C 端替换真实轮椅时，A/B 端**零改动**（协议与执行载体解耦）：

1. `WheelchairAdapter`：指令帧 → 电机命令（前进/后退双电机同向 PWM，左右转差速，停止 PWM 归零，急停禁用输出）
2. 传感器补充：编码器测速、陀螺仪坡度（对应仿真坑地/爬坡/下坡）、超声避障
3. 实体安全层级：物理急停（继电器断电）> 软件急停 0x06 > 双端看门狗 > 限速限幅
4. 真实脑电设备经蓝牙输入源接入（§4 InputSource 预留）
5. 阶段：台架验证 → 半实物 → 整车，禁止直接整车测试

---

**Not applicable 汇总：** 持久化数据库（§3）、认证/支付/云托管/产品内 AI（§2）——本项目为局域网演示原型，需求未涉及。

*Document updated: 2026-10-18（v0.2：按课程指南 §6 重写——新增数据模型、运行与验证、Not applicable 说明、组件四问表）· 开发前草图，首次原型后按实际修订并记录版本日期*

---
## Handoff Context
<!-- Machine-readable summary for the next workflow step. Do not delete; the next prompt in the workflow reads this block. -->
- Stage: technical-design
- App name: BrainchairAPP
- User level: A (vibe coder — 零基础大一学生，依赖 AI 辅助构建)
- Target platform: mobile（Android 上位机/信号源）+ desktop（Unity 3D 执行端，MVP）；真实轮椅（后续实体化）
- Budget: 0 元（可选蓝牙小车 ≤100 元加分项；实体化阶段另议）
- Timeline: 20 天至演示日；课程文档提交 2026-10-18
- AI in product scope: no（仅 AI 辅助开发；产品内 AI 未引入）
- Constraints: 5 人零基础；2 台 Android + 1 台 Windows；手机热点局域网；禁止虚构证据；AI 内容须人工审查；协议先行
- Decisions: 三端链路 A→B→C；UDP 9000/9001/9002；统一帧格式（协议 v0.2）；脑机帧 10Hz、状态帧 5Hz 可选；A 端 4 秒单指令周期（结束自动发停止帧防看门狗误报）；置信度 <30 拒绝 / ≥30 执行 (c-30)/70*100；3 帧确认；双模式 + 急停 0x06 连发 3 次；双端 1s 看门狗；手动接管 30s 恢复；输入源抽象（WiFi/蓝牙预留）；执行端抽象（Unity MVP → WheelchairAdapter 实体化）；实体安全层级 物理急停>软件急停>看门狗>限幅；技术选型 Kotlin/Unity 2021.3/UDP socket
- Open questions: WiFi 草案 §8 六项 + 蓝牙协议 §7 五项待评审锁定（M1 第 1 天）；Unity 学习曲线；热点 UDP 互通性待验证；SSVEP 置信度映射待校准；实体化硬件选型后续阶段再定
- Source files: docs/Proposal-BrainchairAPP.md → docs/PRD-BrainchairAPP-MVP.md → docs/technical-design-BrainchairAPP.md（本文档，对齐指南 §6）· 协议：docs/signal-simulation-design-BrainchairAPP.md（WiFi v0.2）· docs/bluetooth-protocol-BrainchairAPP.md（蓝牙 v0.2）· 安全：docs/simulation-safety-design-BrainchairAPP.pdf · 任务书：docs/tasks-A-end-research-BrainchairAPP.md / tasks-B-end-BrainchairAPP.md / tasks-Unity-BrainchairAPP.md
