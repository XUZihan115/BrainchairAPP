# A 端文件交接清单（SSVEP 脑控 A 端发送 App）

> **读者**：接手 A 端（发送端 App）的开发者 / B 端联调人员
> **版本**：v1.0　**日期**：2026-11-12
> **配套文档**：协议看 `docs/udp-protocol.md`，B 端验收看 `docs/receiver-handbook.md`，整体架构看 `docs/system-architecture.md`

本清单只回答一个问题：**A 端相关的文件都在哪、各自是干什么的**。按「App 源码 → 数据资产 → 工具脚本 → 文档 → 构建配置」五层列出，每个文件附一行职责说明和「该文件是否会被打进 APK / 是否参与运行时」的标注，方便判断哪些可以删、哪些不能动。

---

## 0. 先记住一句话

**A 端 App（EEGSender）= 一台「虚拟脑电采集设备」**：按住方向键时，把 OpenBMI SSVEP 真实脑电波形（62 通道 @1000 Hz）按采集设备协议经 UDP 推给 B 端。B 端只按协议解析，不需要了解 App 内部。

```
A 手机（发送端 App，本仓库）
   └─ 读 assets/ssvep/segments.bin（真实波形）
   └─ 按 EegFrame 协议封装 UDP 报文 → 目标 IP:9000（数据）
   └─ UDP 广播 9001（DISCOVER 自动发现）
   └─ 滑块调节降质（加噪 noise / 陷波 notch）→ 影响发给 B 的波形
```

---

## 1. App 源码（Kotlin，打进 APK）

根目录：`app/src/main/java/com/fifteen/eegsender/`

### 1.1 入口与界面

| 文件 | 职责 | 运行时 |
|------|------|--------|
| `MainActivity.kt` | 唯一 Activity：UI、方向键按住推流、滑块降质、日志面板 | ✅ 核心 |
| `app/src/main/res/layout/activity_main.xml` | 主界面布局（最外层 ScrollView） | ✅ 界面 |
| `app/src/main/res/values/strings.xml` | 界面文案 | ✅ |
| `app/src/main/res/values/themes.xml` | 主题 + 方向键按钮样式（CmdButton，已禁用焦点避免滚动跳屏） | ✅ |
| `app/src/main/res/values/colors.xml` | 颜色 | ✅ |
| `app/src/main/res/drawable/bg_btn_direction.xml` | 方向键按钮背景 | ✅ |
| `app/src/main/res/drawable/bg_btn_stop.xml` | 停止键背景 | ✅ |
| `app/src/main/res/drawable/bg_card.xml` | 卡片背景 | ✅ |
| `app/src/main/res/drawable/bg_input.xml` | 输入框背景 | ✅ |
| `app/src/main/res/drawable/ic_app_icon.xml` | 应用图标 | ✅ |
| `app/src/main/AndroidManifest.xml` | 权限（WiFi 状态、局域网）、Activity 注册 | ✅ |

### 1.2 协议层（`protocol/` 子包）

| 文件 | 职责 | 运行时 |
|------|------|--------|
| `EegFrame.kt` | **协议唯一权威定义**：包头/负载/CRC 布局、`EegCommands` 指令编码（1 前进 / 2 左 / 3 右 / 4 后退 / 0 停）、`PacketEncoder` 编码器。B 端解析必须与此一致 | ✅ 核心 |
| `UdpEegSender.kt` | 推流引擎：定时节拍（每 5 ms 一包）、有界发送、掉线检测、松手收尾 STREAM_STOP | ✅ 核心 |
| `EegSignalSource.kt` | 数据源：从 assets 载入真实波形、按方向循环播放、降质（加噪/陷波）在线变换 | ✅ 核心 |
| `SignalDegrade.kt` | 降质算法：陷波成分提取（FFT 域 4 刺激频率 1/2/3 次谐波掩码）+ 带限 RMS；与 `tools/degrade_reference.py` 口径一致 | ✅ |
| `Fft.kt` | 实数 FFT 实现（降质预热用） | ✅ |
| `EegSignalSource` 数据 → `EegFrame` 打包 → `UdpEegSender` 发送，三者是 A 端数据通路，改动任一都必须回看 `docs/udp-protocol.md` 和验收工具 | | |
| `UdpDiscovery.kt` | UDP 9001 广播：A 发 DISCOVER、B 应答 ANNOUNCE（自动发现对端 IP） | ✅ |
| `LocalNetwork.kt` | 局域网网卡枚举、网段判断（给界面提示） | ✅ |
| `NetDiagnostics.kt` | 网络异常信息翻译（EPERM、热点隔离等 → 可执行提示） | ✅ |

### 1.3 测试代码

| 文件 | 职责 |
|------|------|
| `app/src/test/java/com/fifteen/eegsender/protocol/SignalDegradeTest.kt` | 降质算法单测 |
| `app/src/test/java/com/fifteen/eegsender/protocol/LocalNetworkTest.kt` | 局域网工具单测 |
| `app/src/test/java/com/fifteen/eegsender/protocol/ProtocolWireTest.kt` | 协议字节级单测（与 `docs/udp-protocol.md` 对照） |

---

## 2. 数据资产（打进 APK）

根目录：`app/src/main/assets/ssvep/`

| 文件 | 大小 | 职责 |
|------|------|------|
| `segments.bin` | ~17 MB | **真实脑电波形**（OpenBMI SSVEP 数据集抽取，float32 小端、时间主序）。**APK 体积主要来源，别乱删** |
| `segments.json` | 3.8 KB | 波形清单：采样率、通道名、各方向采样区段（sampleOffset / sampleCount） |

> 用 `tools/verify_assets.py` 校验结构与循环拼接完整性；重新导出用 `tools/extract_openbmi.py`。

---

## 3. 工具脚本（tools/，不进 APK，留档/验收用）

### 3.1 数据导出与校验（A 端数据源头）

| 文件 | 职责 |
|------|------|
| `extract_openbmi.py` | 从 OpenBMI .mat 原始数据抽取 → `segments.bin/json` |
| `verify_assets.py` | assets 校验：结构 + 循环拼接边界 + CCA 方向分类 |
| `verify_ssvep_cca.py` | 校验导出的波形是否携带真实 SSVEP 频率信息 |
| `verify_psd_peak.py` | 用试次对齐平均 + 功率谱直接测每个方向的刺激频率 |
| `inspect_labels.py` | 打印 OpenBMI .mat 标签字段，核对方向映射 |
| `verify_labels_full.py` | OpenBMI 全部 100 个训练试次 CCA 分类，核对方向标签 |
| `gen_fft_vector.py` | 生成 `Fft.kt` / `SignalDegrade.kt` 的单元测试向量 |

### 3.2 降质标定（A 端"信号差"旋钮的权威口径）

| 文件 | 职责 |
|------|------|
| `degrade_reference.py` | **降质权威参考实现 + 标定器**：notch/noise 公式的 Python 版，与 `SignalDegrade.kt` 同口径（改 Kotlin 必须先改这里比对） |
| `calib_3s_aligned.py` | 3 s 试次头对齐窗 + 普通 rfft 带通（B 端当前口径）的权威标定 |
| `calib_3s_gate.py` | 3 s 口径下 alpha→门控判决逐窗扫描（替代 receiver-handbook 旧 §9.2 表） |
| `sweep_quality.py` | 扫「人为降质」强度对 SSVEP 置信度（CCA 峰值）的影响 |

### 3.3 验收与联调（对接 B 端 / 真机）

| 文件 | 职责 |
|------|------|
| `acceptance_probe.py` | **A→B 判决链路验收器**（兼最小接收端原型）：收 UDP → 带通 → CCA → 门控 → 判决。`--selftest` 自检、`--verify/--sweep/--degrade` 标定验证、默认真机联调 |
| `udp_probe.py` | 实时抓 A 端报文：解析、校验（CRC/序号/时间戳）、逐包统计 |
| `decode_wire_dump.py` | 离线解析 wireshark dump 的协议报文（对比抓包与 `udp-protocol.md`） |
| `test_loop_modes.py` | 实测「按住循环」三种拼接方式对识别准确率的影响 |
| `start_probe.ps1` | 一键启动 PC 端替身接收（抓包 + 协议校验） |
| `allow_firewall.ps1` | 放行 Windows 防火墙 UDP 9000/9001（真机联调前必跑） |

---

## 4. 文档（docs/）

| 文件 | 职责 |
|------|------|
| `udp-protocol.md` | **协议规范（v2）**：字节级布局、时序、结束语义。A 端与 B 端共同依据 |
| `receiver-handbook.md` | B 端接收实现手册 + §9.4 验收标准（3 s 试次头对齐口径，θ₁≈0.63 / θ₂≈0.171） |
| `system-architecture.md` | 整体架构：A/B/C 三方拓扑、热点组网、数据流 |
| `handover-to-team-b.md` | 交接给 B 端团队的总体说明（验收口径演进 v1.2→v1.4） |
| 本文件 `a-side-file-map.md` | A 端文件位置总览（就是本清单） |

---

## 5. 构建配置（工程骨架）

| 文件 | 职责 |
|------|------|
| `settings.gradle.kts` / `build.gradle.kts` | 工程与模块配置 |
| `app/build.gradle.kts` | App 模块：依赖、AGP 配置 |
| `gradle/libs.versions.toml` | 依赖版本目录 |
| `gradle/wrapper/*` | Gradle Wrapper（`gradlew` / `gradlew.bat` / jar / properties） |
| `gradle.properties` | 构建 JVM 参数（`-Dfile.encoding=UTF-8` 等） |
| `gradle/gradle-daemon-jvm.properties` | Gradle 守护进程 JVM 配置 |
| `local.properties` | 本机 SDK 路径（**已 gitignore，不入库**） |
| `.gitignore` | 忽略规则（build/、.kotlin/、.tmp/、*.apk、*.keystore 等） |
| `.gitattributes` | Git 换行符等属性 |
| `.idea/`（部分入库） | IDE 配置（`vcs.xml` 已忽略；其余为团队共享 IDE 配置） |

---

## 6. 快速判断「哪些能删」

| 情况 | 处理 |
|------|------|
| APK 太大想瘦身 | 主因是 `segments.bin`（17MB）；要减必须重新抽取/降通道，**不能直接删**（删了 App 没数据可发） |
| 仓库里有 IDE 杂文件 | `.idea/vcs.xml`、`local.properties` 已忽略；如看到未忽略的 `.idea/` 文件多为 Android Studio 自动生成 |
| 验收完成想清工具 | 保留 `acceptance_probe.py`、`udp_probe.py`、`start_probe.ps1`、`allow_firewall.ps1`（联调常用）；`calib_3s_*.py`、`degrade_reference.py` 是标定依据，改算法前别删 |
| 想换数据集 | `segments.bin/json` + `extract_openbmi.py` 一起换，然后跑 `verify_assets.py` + `verify_ssvep_cca.py` |

---

## 7. A 端关键口径备忘（改代码前必读）

1. **指令编码**：1=前进(12 Hz)、2=左转(8.57 Hz)、3=右转(6.67 Hz)、4=后退(5.45 Hz)、0=停。早期文档 left/right 频率曾写反，以 `EegFrame.kt` 实测为准。
2. **协议版本 v2**：magic `0x47EE`、小端序、16 字节包头 + CRC-32 尾。B 端与 `udp-probe.py` 都按此解析。
3. **降质口径**：`notch: x' = x − α·band`（FFT 域 4 刺激频率谐波掩码）；`noise: x' = x + α·rms·N(0,1)`。Kotlin 与 Python 必须同口径。
4. **验收阈值**：θ₁≈0.63（判方向）、θ₂≈0.171（拒识停）、降质档位翻转点≈76%。来自 `calib_3s_gate.py` 实测。
5. **发送语义**：按住持续发（不限长度）、松手补 `STREAM_STOP(reason=0x01)`、换方向不发收尾（新 STREAM_INFO 即硬边界）、对端失联超 1s 主动中止（reason=0x02）。
