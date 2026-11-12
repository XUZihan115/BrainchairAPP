# Product Requirements Document: BrainchairAPP MVP

## Product Overview

**App Name:** BrainchairAPP（脑控轮椅安卓上位机）
**Tagline:** 用「念头」开轮椅——零硬件的脑控全链路演示
**Launch Goal:** 课程答辩演示通过：A→B→Unity 全链路 3 分钟演示 + 协议/文档完整，答辩评委可现场操作
**Target Launch:** 演示日（2026-11 初，启动后 20 天内）；课程文档提交 2026-10-18
**证据链接：** 需求/市场/技术证据见 `docs/Proposal-BrainchairAPP.md`（含来源链接与访问日期）与 `docs/research-BrainchairAPP.md`（2026-10-18 逐项复核属实）

## Who It's For

### Primary User: 演示讲解员（答辩学生）
大一零基础学生，负责现场演示脑控轮椅闭环。他们面对评委演示，需要稳定、可解释、可回退的演示流程，且必须能回答「每个需求/设计决策的理由」。

**Their Current Pain:**
- 没有真实脑电帽和轮椅硬件，无法演示真实脑控；
- 零基础，从零搭硬件成本高、周期长；
- 演示最怕链路断线、指令失效、现场不可控。

**What They Need:**
- 全软仿真：手机代替脑电帽，Unity 代替轮椅；
- 稳定可回退：热点组网 + 手动兜底模式；
- 可解释：协议清晰，答辩能讲明白每帧数据。

### Example User Story
"Meet 大一学生小张，他的小组要在 20 天内做出一个脑控轮椅项目。没有脑电帽、没有轮椅硬件，他们在 AI 帮助下做出一个 A 手机模拟脑电 → B 手机解析指令 → Unity 3D 虚拟轮椅运动的全链路演示，答辩时能现场跑通并解释每帧协议。"

## The Problem We're Solving

脑控轮椅是可行的方向，但真实方案需要脑电帽、轮椅等硬件，成本高且接口封闭，教学演示几乎不可行。本项目在零硬件预算下，用「双手机 + WiFi UDP + Unity 3D」复现完整「意图→指令→运动→状态回显」闭环，让课程评审在 3 分钟内理解脑控轮椅的软件链路，也为未来接入真实硬件预留协议与蓝牙输入源接口。

**Why Existing Solutions Fall Short:**
- 真实脑控轮椅方案（NeuroSky/OpenBCI + 轮椅）：设备昂贵、接口封闭、不可教学复现；
- 纯 Unity 单机演示：只展示了运动效果，没有「感知→决策→执行」闭环，不真实；
- 纯 Android 单机 App：没有执行端，看不到运动结果。

## User Journey

### Discovery → First Use → Success

1. **Discovery Phase**
   - 场景：课程立项答辩，评委听到「脑控轮椅」眼前一亮
   - 吸引点：不需要昂贵脑电设备，用手机模拟也能演示脑控闭环
   - 决策触发：零预算 + 三端协议可解释，评委认可可行性

2. **Onboarding (First 5 Minutes)**
   - 打开 A 手机信号源 App，点「开始模拟」
   - 打开 Unity 场景，等轮椅就位
   - 打开 B 手机上位机，连接热点、点击「开始」，进入脑控模式

3. **Core Usage Loop**
   - 触发：A 手机 SSVEP 意图切换（注视闪烁目标）或置信度波动（模拟）
   - 动作：B 手机解析→生成前进/转向/急停指令
   - 奖励：Unity 轮椅实时移动/转弯/停止
   - 投资：状态页实时看到速度与运行状态

4. **Success Moment**
   - 「Aha!」时刻：注视「前进」目标，轮椅明显前进；切换「左转」，轮椅左转——「真的能用眼神选择方向」
   - 分享触发：评委现场把手指放到按钮上试操控，链路全程可控可回退

## MVP Features（对齐协议草案 v0.2）

### Must Have for Launch

#### 1. [FR-01] A 手机虚拟脑机信号源（SSVEP 模拟）
- **What:** 模拟 SSVEP 解码器输出：5 个闪烁目标（前进/后退/左转/右转/停止，闪烁动画模拟 SSVEP 刺激）选择意图，经 UDP 9000 端口按统一帧格式以 **10Hz** 发送脑机帧（意图指令码 + 置信度 + 预留 + 时间戳，7B 数据区）
- **User Story:** 作为信号源，我想模拟 SSVEP 解码结果（意图+置信度）并定时发送，以便在没有脑电帽时驱动整个链路
- **Success Criteria:**
  - [ ] 指定端口可发帧，帧格式含帧头(0xAA 0x55)/类型/长度/数据/校验/帧尾(0x0D 0x0A)
  - [ ] 5 个意图目标（前/后/左/右/停）可在界面上切换，带闪烁动画模拟 SSVEP 刺激（演示可控）
  - [ ] 置信度可在界面上手动调节或自动波动（调低模拟电极脱落/干扰）
- **Priority:** P0 (Critical)

#### 2. [FR-02] B 手机上位机（核心交付物）
- **What:** 收 A 手机脑机帧(UDP 9000)→校验→阈值映射→经 UDP 9001 发指令帧；脑控/手动双模式、急停优先、断链提示、蓝牙输入源预留
- **User Story:** 作为操作者，我想在脑控模式下靠 SSVEP 意图控制轮椅、手动模式下用方向键控制，以便现场演示可兜底
- **Success Criteria:**
  - [ ] 脑控模式：置信度判定（<30 拒绝执行发停止 / ≥30 执行意图，速度=(置信度-30)/70×100 线性），意图切换需连续 3 帧确认，判定函数集中一处便于调整
  - [ ] 手动模式：方向键可前后左右；**手动操作瞬间关闭信号接收、优先执行手动指令，30 秒无操作后自动恢复**
  - [ ] 急停按钮任何模式下立即发 `0x06`，10Hz 连发 3 次，1 秒内生效并锁止
  - [ ] 断链提示：1 秒收不到脑机帧 → 界面提示「信号丢失」
  - [ ] 三页界面（连接页/控制页/状态页）可切换
  - [ ] 连接页预留「输入源」切换：WiFi 模拟（默认）/ 蓝牙串口（预留，SPP 与 BLE UART 两种形态，地址直连 + 适配器解析，断开视为信号丢失）
- **Priority:** P0 (Critical)

#### 3. [FR-03] Unity 3D 虚拟轮椅执行端
- **What:** 收 B 手机指令帧(UDP 9001)，驱动 3D 轮椅位移/转向/轮子旋转/停止，可回传状态帧(UDP 9002, 5Hz)
- **User Story:** 作为执行端，我想按指令驱动虚拟轮椅并回传状态，以便完整呈现「执行→回显」闭环
- **Success Criteria:**
  - [ ] 前进/后退/左转/右转/停止/急停/调速指令均可生效
  - [ ] 轮子随运动旋转，动作平滑（无卡顿）
  - [ ] **1 秒收不到合法指令帧 → 自动停车**（看门狗）
  - [ ] 场景含平地/坑地/爬坡/下坡（坑地自动停车、超坡度停车、下坡限速，详见 `docs/simulation-safety-design-BrainchairAPP.pdf`）
- **Priority:** P0 (Critical)

#### 4. [FR-04] 演示现场组网与回退
- **What:** 手机热点组网方案 + 断线提示 + 手动模式兜底
- **User Story:** 作为讲解员，我想在演示现场稳定组网并随时回退到手动模式，以便演示不因断线翻车
- **Success Criteria:**
  - [ ] 现场热点组网步骤 ≤3 步（写在演示脚本里）
  - [ ] 链路断线时 B 手机提示，Unity 自动停车
  - [ ] 全程可切手动模式兜底
- **Priority:** P0 (Critical)

### Nice to Have (If Time Allows)
- **状态回传**: Unity → B 手机回显实际速度/位置，状态页展示
- **注意力波动动画**: 信号源界面实时波形图
- **语音播报**: 置信度/意图切换语音提示（答辩加分）

## Out of Scope (Not in MVP)
- **真实脑电硬件接入**: 需脑电帽 + SDK，演示日后再议；本期仅做 B 手机蓝牙输入源预留（连接+字节流接收+适配器框架）
- **真实轮椅/小车硬件驱动**: 硬件成本与调试风险，仅预留协议接口
- **多轮椅/多用户**: 单链路演示即可
- **云端/后台/账号体系**: 纯局域网单机演示
- **历史数据记录与导出**: 演示不需要
- **摇杆全向控制（角度控制）**: 虚拟摇杆 + 0x07 全向指令码 + Unity 全向运动（万向轮模拟）——**中期评价后实施**，本期不做

*Why we're waiting: 保持 MVP 聚焦「零硬件全链路闭环」，20 天内可完成、答辩可讲清。摇杆全向控制属「手动操控 + 万向轮扩展」，中期评价后作为二期方向实施。*

## Business Rules & Edge Cases（FR-01~FR-04 通用规则）

**指令优先级（任何模式生效）：** 急停 > 手动 > 脑控。

**权限与安全联动：**
- **急停（FR-02/FR-03）：** 急停按钮任何模式下可用，立即发 `0x06`，10Hz 连发 3 次，1 秒内生效并锁止；急停 `0x06` 不进入脑机帧，仅 B 端按钮触发。

**冲突动作与接管：**
- **手动接管（FR-02）：** 手动模式操作方向键瞬间，B 端关闭信号接收、优先执行手动指令；30 秒无操作自动恢复脑控接收。
- **意图切换确认（FR-01/FR-02）：** 脑控模式下意图码切换需连续 3 帧（约 300ms）一致才生效，防止抖动误触。

**边界值与无效输入：**
- **置信度边界（FR-02）：** 置信度 <30 → 拒绝执行并下发停止 `0x05`；≥30 → 执行意图，速度 = (置信度-30)/70×100（仅前进/后退有效）。
- **错帧处理（FR-02）：** 帧头/帧尾/长度/校验不符的帧一律丢弃，不计入确认帧计数。
- **空状态（FR-02）：** 未连接 WiFi 时操作控件置灰并提示「未连接」；蓝牙输入源未实现时连接页对应选项禁用并标注「预留」。

**失败与兜底行为：**
- **断链提示（FR-02）：** 1 秒收不到脑机帧 → 界面提示「信号丢失」，不向 C 端发指令。
- **看门狗停车（FR-03）：** Unity 1 秒收不到合法指令帧 → 自动停车。
- **场景边界（FR-03）：** 坑地自动停车、超坡度停车、下坡限速（详见 `docs/simulation-safety-design-BrainchairAPP.pdf`）。
- **蓝牙断连（预留通道）：** 蓝牙断开视同信号丢失，触发同样的断链提示流程。

## How We'll Know It's Working

### Launch Success Metrics（答辩前一周）
| Metric | Target | Measure |
|--------|--------|---------|
| 全链路演示成功率 | ≥ 3 次连跑无中断 | 彩排记录 |
| 指令到运动时延 | ≤ 300ms 可感知即时 | 秒表计时/录屏 |
| 协议文档完整度 | 每帧字段可解释 | 答辩问答清单 |
| 组员可解释率 | 每人可解释自己模块协议 | 内部问答演练 |
| 安全机制演示 | 急停/断链停车/坑地停车均现场可演示 | 彩排记录 |

### Growth Metrics (Months 2-3)
| Metric | Target | Measure |
|--------|--------|---------|
| 真实硬件接入可行性验证 | 蓝牙输入源对接脑电 SDK 可行 | 若后续做真实版，评审真实硬件闭环 |

## Look & Feel

**Design Vibe:** 简洁、科技感、可解释（组内评审一致）
**视觉原则：**
1. 每个界面只服务一个任务（连接/控制/状态分离）
2. 状态信息大字号高对比，演示时后排能看清
3. 急停按钮醒目红色，任何页面可用

**Key Screens/Pages:**
1. **连接页**: 设置 IP/端口、输入源选择（WiFi 模拟/蓝牙串口）、开始连接、模式选择
2. **控制页**: 脑控（SSVEP 意图区 + 置信度条）/手动（方向键）+ 急停
3. **状态页**: 速度、运行状态、链路状态（可选回显）

### Simple Wireframe
```
[控制页 Main Screen]
┌─────────────────────────┐
│   Brainchair 上位机      │
├─────────────────────────┤
│   意图: [前][后][左][右][停] │
│   置信度: ████░░  72    │
│   模式: [脑控] [手动]    │
│                         │
│        [↑ 前进]          │
│   [←]        [→]        │
│                         │
│       [急停 STOP]        │
├─────────────────────────┤
│  连接页 | 控制页 | 状态页 │
└─────────────────────────┘
```

## Technical Considerations（对齐协议草案 v0.2）

**Platform:** Android（B 手机上位机 + A 信号源）、Windows + Unity 3D（执行端）
**Protocol（草案 §2 统一帧格式）:**
- 帧结构：帧头 `0xAA 0x55`(2B) + 类型(1B) + 长度(2B) + 数据(NB) + 校验(1B) + 帧尾 `0x0D 0x0A`(2B)
- 脑机帧(A→B, UDP 9000, 10Hz)数据区 7B：意图码(0x01前进/0x02后退/0x03左转/0x04右转/0x05停止)/置信度(0~100)/预留/秒级时间戳(4B)；急停 0x06 仅 B 端按钮触发，不进脑机帧
- 指令帧(B→C, UDP 9001)数据区 4B：指令码(前进0x01/后退0x02/左转0x03/右转0x04/停止0x05/急停0x06)/速度(0~100)/转向强度/序列号(0~255)
- 状态帧(C→B, UDP 9002, 5Hz, 可选)：速度/位置/运行状态/时间戳
- 校验算法 XOR 或 CRC8（草案 §8 待全员锁定）
- **模拟说明：** 本项目为软件模拟——真实系统「脑电帽→处理端」的**蓝牙段替换为 WiFi UDP 9000**（A 手机代替脑电帽）；蓝牙通道按 `docs/bluetooth-protocol-BrainchairAPP.md` 预留，供后续实体设备接入使用（详见下方 Bluetooth 小节）

**Responsive:** 手机竖屏为主，一页一任务
**Performance:** 指令端到端时延 ≤ 300ms（局域网）；脑机帧 10Hz；UI 无卡顿
**Accessibility:** 大字状态、对比度高的急停按钮、振动提示（可选）
**Security/Privacy:** 局域网演示无云端；协议帧校验防错帧；无任何用户数据采集
**Scalability:** 单链路即可；协议帧类型字段预留扩展；B 手机输入源抽象（WiFi/蓝牙可切换）
**Bluetooth (预留通道，实体开发使用):** 模拟阶段该段替换为 WiFi UDP（A 手机代替脑电帽走 WiFi），蓝牙协议见 `docs/bluetooth-protocol-BrainchairAPP.md`；Android 12+ 需运行时申请 BLUETOOTH_SCAN/BLUETOOTH_CONNECT；SPP 与 BLE UART 服务（Nordic UUID `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`）适配器框架，MVP 阶段仅实现「连接+字节流接收+原始数据展示」，拿到真实设备地址即可配对连接

## AI / Automation Scope

**Product AI:** 无（产品内不引入 AI 功能；AI 仅用于开发辅助与文档生成）
**User Outcome:** N/A —— MVP 不含产品内 AI
**Data Access:** N/A
**Provider / Retention:** N/A
**Output Contract:** N/A
**Human Confirmation:** N/A
**Evaluation:** N/A —— 若后续引入 AI 功能，需另立数据边界与评估集
**Telemetry / Cost:** N/A

> 备注：AI 在此项目中担任「开发助手」（生成代码、协议文档、测试），全部产出须组员逐条人工审查——课程要求 AI 内容可解释、无虚构证据。

## Quality Standards

**What This App Will NOT Accept:**
- 假数据/假演示：证据与演示必须真实可复现（课程红线，禁止虚构访谈引文）
- 半成品功能：列出的功能要么能用，要么不进 MVP
- 无协议文档的代码：每帧字段必须有文档可解释（协议草案 v0.2 为准）
- 现场不可回退：任何状态下都有手动兜底与急停

*这些标准由 AI 编码助手在开发中强制检查。*

## Budget & Constraints

**Development Budget:** 0 元（全部免费工具：Android Studio、Unity Personal、GitHub）
**Monthly Operating:** 0 元
**Timeline:** 20 天至演示日（文档提交 10-18）
**Team:** 5 人：主讲/UI/场景建模（B 端 App UI 设计、Unity 3D 场景建模交付、PPT、汇报开场+结尾）+ B 端开发 ×2（内部自行分工）+ Unity 负责人（轮椅建模+执行端+场景集成）+ A 端/设备连接研究（详见 `docs/team-roles-BrainchairAPP.md`）
**里程碑:** M1 协议与环境(1-2d, 须锁定 WiFi 草案 §8 六项 + 蓝牙协议 §7 五项) → M2 三端并行(3-7d) → M3 首次联调(8-10d) → M4 打磨(11-14d) → M5 物料彩排(15-20d)

## Open Questions & Assumptions
- 协议草案 §8 待全员评审锁定：端口 9000/9001/9002、校验算法（XOR vs CRC8）、置信度阈值与速度映射初值（<30 / (x-30)/70*100）、帧版本号、状态回传是否入 MVP、超时 1s 与断链提示文案 → **责任人：B 端开发×2 汇总评审；截止：M1 第 1 天**
- 资料佐证已完成（WHO/中国残联/Mordor/arXiv，见 `docs/research-BrainchairAPP.md`，2026-10-18 复核属实）；真实访谈/观察证据可选补充 → **责任人：主讲/UI；截止：答辩前**（Proposal 第 3 节已含来源链接与访问日期）
- Unity 学习曲线是最大不确定项 → **责任人：Unity 负责人；对策：M2 内先跑通 Unity UDP 最小示例**
- 假设：手机热点下 UDP 单播在 Android/Windows 间可互通 → **责任人：B 端开发×2；截止：M1 实测验证**
- 假设：SSVEP 置信度映射简单阈值即可演示效果 → **责任人：A 端研究 + B 端开发；截止：M3 首次联调校准**

## Launch Strategy (Brief)

**Soft Launch:** 第 15 天起每日组内彩排，模拟评委问答
**Target Users:** 课程评委（答辩）、同班同学（互测）
**Feedback Plan:** 彩排记录 + 同学试玩反馈表
**Iteration Cycle:** 每 2 天一次联调迭代

## Definition of Done for MVP

The MVP is ready to launch when:
- [ ] 所有 P0 功能可运行（信号源/上位机/Unity 三端齐全）
- [ ] A→B→Unity 全链路连续演示 ≥3 次无中断
- [ ] 急停与手动兜底随时可用；断链/坑地停车等安全机制可演示
- [ ] 协议文档每帧字段可被组员逐条解释（草案 v0.2 已锁定）
- [ ] 课程文档（Proposal/PRD）已按指南提交（含真实证据，无虚构）
- [ ] 演示视频与 PPT 物料完成
- [ ] 答辩问答预演通过

## Next Steps

After this PRD is approved:
1. 全员评审并锁定两份协议待定项：WiFi 协议草案 §8 六项 + 蓝牙协议草案 §7 五项（M1 第 1 天）
2. 生成技术设计文档（Part 3 流程）
3. 建立开发环境（GitHub 协作、Android Studio、Unity）
4. 按协议文档并行开发三端
5. 第 8 天前完成首次联调
6. 提交课程文档（10-18）并彩排

---
*Document updated: 2026-10-16（v0.4：对齐协议草案 v0.2；脑电范式改为 SSVEP 意图选择——脑机帧「意图指令码+置信度」、映射改为置信度判定；新增蓝牙协议引用、摇杆全向控制列入 Out of Scope 中期评价后实施）*
*Status: Draft — Ready for Technical Design*

---
## Handoff Context
<!-- Machine-readable summary for the next workflow step. Do not delete; the next prompt in the workflow reads this block. -->
- Stage: prd
- App name: BrainchairAPP
- User level: A (vibe coder — 零基础大一学生，依赖 AI 辅助构建)
- Target platform: mobile（Android 上位机/信号源）+ desktop（Unity 3D 执行端）
- Budget: 0 元（可选蓝牙小车 ≤100 元加分项）
- Timeline: 20 天至演示日；课程文档提交 2026-10-18
- AI in product scope: no（仅 AI 辅助开发；产品内 AI 未引入）
- Research status: Partial research — 桌面资料佐证完成（WHO/中国残联/Mordor/arXiv，来源与访问日期 2026-10-15 已标注，见 `docs/research-BrainchairAPP.md`）；真实用户访谈未做（可选补充）
- Constraints: 5 人零基础；2 台 Android + 1 台 Windows；手机热点局域网；禁止虚构证据；AI 内容须人工审查；协议先行
- Decisions: 三端链路 A(信号源)→B(上位机)→Unity(虚拟轮椅)；UDP 9000/9001/9002 三端口；统一帧格式 帧头0xAA55/类型/长度/数据/校验/帧尾0x0D0A；脑机帧10Hz、状态帧5Hz；阈值映射 40/70 分档；脑控/手动双模式 + 急停优先(连发3次)；C 端 1s 超时自动停车；手动接管 30s 恢复；蓝牙输入源预留(SPP/BLE UART)；Unity 平地/坑地/爬坡/下坡四场景；摇杆全向控制(0x07 角度控制)不入 MVP、中期评价后实施
- Open questions: WiFi 草案 §8 六项 + 蓝牙协议 §7 五项待评审锁定；真实访谈证据待收集；Unity 学习曲线；热点 UDP 互通性待验证；SSVEP 置信度映射待校准（摇杆全向控制已决策：中期评价后实施）
- Source files: docs/research-BrainchairAPP.md（资料佐证）→ docs/signal-simulation-design-BrainchairAPP.md（WiFi 链路协议草案 v0.2，模拟阶段）→ docs/bluetooth-protocol-BrainchairAPP.md（蓝牙协议草案 v0.2，实体开发预留）→ docs/simulation-safety-design-BrainchairAPP.pdf（安全+场景设计）→ docs/PRD-BrainchairAPP-MVP.md
---

```json
{
  "schemaVersion": 1,
  "documentType": "prd",
  "appName": "BrainchairAPP",
  "oneLiner": "通过模拟脑电信号经 WiFi UDP 控制 Unity 3D 虚拟轮椅的安卓上位机，零硬件完成脑控轮椅全链路演示",
  "targetUsers": "大一零基础课程项目组（答辩演示）",
  "phase": "Foundation",
  "mustHave": ["A手机模拟脑电信号源", "B手机上位机解析指令与双模式", "Unity 3D 虚拟轮椅执行端(含坑地/爬坡/下坡场景)", "演示现场组网与手动兜底"],
  "niceToHave": ["状态回传显示", "置信度波形动画", "语音播报"],
  "notInMvp": ["真实脑电硬件接入(仅预留蓝牙输入源)", "真实轮椅/小车驱动", "云端后台账号", "多用户多轮椅", "历史数据记录", "摇杆全向控制(中期评价后实施)"],
  "successMetrics": ["全链路连跑≥3次无中断", "指令到运动时延≤300ms", "协议逐字段可解释", "课程文档无虚构证据", "安全机制现场可演示"]
}
```
