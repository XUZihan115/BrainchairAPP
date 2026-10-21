# Stage 2.7 本阶段实测记录

代码提交 `e09eea1ea58ab01a0b57ac6b07d95b470c09f06c`；测试日期 2026-10-20。本报告只列本阶段实际运行结果。

- 构建：`assembleDebug` 成功；Lint 0 错误、3 个已有警告。APK 1,006,898 字节，versionName `0.3.3-a4s-stop`。
- APK SHA256：`2c772641a63ea1c4037231ed1d089b845e26415d61555bfeb7c6a932c7e5b7a0`。
- JVM：15 项，0 失败、0 错误、0 跳过。
- 安装后的 Android APK：32 项检查，32 通过；设备仅 API35 x86_64 模拟器。

## 四秒交互与异常场景

| 场景 | CCA 分析次数 | 原始/稳定判决 | 最终/发送/模拟接收 | 结果 |
|---|---:|---|---|---|
| single forward 4s | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| forward to left before 4s | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| rapid directions INFO-only | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| switch includes explicit SWITCHED STOP | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| immediate restart right | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| CRC and missing packet | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| 4s without STOP then timeout | 0 | STOP/STOP | STOP/STOP/STOP | 通过停止/隔离检查；不代表运动识别成功 |
| new flow cannot release ESTOP | 0 | STOP/STOP | ESTOP/ESTOP/ESTOP | 通过停止/隔离检查；不代表运动识别成功 |

四秒结束 STOP、INFO-only 切换、带 reason=3 的切换 STOP 分开验证。连续快速切换与新的四秒周期都没有拼接窗口。CRC/缺包场景检测到错误但保持停止，断流场景无合法 STOP，独立依赖超时停止。静态结果之外，脚本在发送期间采样并检查模拟接收历史没有运动指令。

额外 Android 检查：未监听时按急停再开始监听；急停下新 INFO；普通停止不解锁；强制结束 B 进程后重开保留急停；显式人工解除仍保持 STOP。

JVM 使用真实滤波/CCA 的合成连续流，先建立实际运动再注入 CRC、缺包和超时，确认模拟接收确实变为 STOP；另将真正的 CCA 计算暂停在工作线程，分别并发发送 STOP/新 INFO，释放计算后仍无旧运动发布。状态机单元测试直接构造控制结果用于验证六命令映射、过期和锁定，不充当 EEG 分类准确性证据。

## 实际时间线

| 环境与事件 | 相对新流开始 | 口径 |
|---|---:|---|
| JVM fourSeconds：INFO | 0.000 ms | 4s window plus unchanged 0.5s warmup |
| JVM fourSeconds：source STOP sent | 4002.321 ms | 800 SAMPLE_BLOCK |
| JVM fourSeconds：全部同步失效回调完成 | 4004.518 ms | 1.837 ms；最终 STOP 在此之前已撤销运动 |
| JVM fourSeconds：B mock send | 4004.193 ms | 1.8721 ms source STOP to B send |
| JVM fourSeconds：mock receive | 4004.202 ms | 0.0083 ms mock send to receive; NO B-C UDP |
| JVM sixSecondDiagnostic：first gated raw | 4535.625 ms | actual causal filter + CCA |
| JVM sixSecondDiagnostic：first mock received stable motion | 4946.622 ms | not a 4s click |
| Android 六秒诊断：首次门控原始判决 | 4555.153 ms | B 接受 INFO 到实际判决时刻；分析编号 1 |
| Android 六秒诊断：首次模拟接收稳定前进 | 4970.042 ms | B 同进程单调时钟；不是 Unity UDP |

正常四秒流没有首次可靠运动判决。六秒诊断单独生成连续合成 EEG，不重复四秒片段、不跨流填窗；只用于观察原算法所需时间。它不能作为“四秒规则可运动”的证据。

CSV 中 `B stop revoked` 使用的是 `invalidationCompletedNs`，即所有同步清理回调完成的时刻。最终控制监听器先撤销运动，随后算法监听器清理，发送线程可在全部清理完成前发送已生效的 STOP，因此表中发送时间略早于回调完成；没有先发送后撤销运动。

## STOP 时延与范围

- 以独立 JVM 发送器的四秒周期截止 `t=4000ms` 定义信号结束：B 模拟 STOP 发送在 `t=4004.1935ms`，相差 **4.1935ms**。其中周期截止到源端实际发出 STOP 为 2.3214ms 调度误差；之后的传播与 B 处理见下一项。这是从测试代码的四秒截止时刻和实测时间线推导，未测量原 A APK 的内部结束事件。
- JVM 本地实际 A→B UDP：源端 STOP 发出到 B 模拟发送约 1.8721 ms；模拟发送到模拟接收约 0.0083 ms。
- Android 在已经观察到前进之后发送 STOP：B socket 返回到最终 STOP 0.4385 ms，到模拟发送 0.6975 ms；模拟发送到模拟接收 0.0143 ms。
- 各次 STOP 的单独数值在 evidence.json。没有以 UI 刷新代替最终控制；没有以 B 内部 STOP 代替 Unity 制动。JVM 与 Android 时钟、源端发送与 B 收包、模拟传输与真实 UDP 均分开。以上不构成网络/手机实时上界。

## 六秒诊断的运行指标

- 采到 7 次不同 CCA 计算，平均 5.146 ms，最大 11.074 ms。
- 实际判决周期间隔中位数 208 ms，最大 212 ms。
- Java 堆观测 4.71～11.62 MiB；整个 App 进程 CPU 约单核 31.8%，包含 UI、JSON 证据、收包和模拟发送。不是手机性能保证。

## 尚未通过或尚未验证

- 四秒点击的可见运动目标：不成立。4000点扣除500点预热，只剩3500点，未能形成4000点窗口，随后立即 STOP。需需求决策；未修改窗口、阈值、投票或 STOP 时机。
- 真实 Unity UDP：未验证且禁用编码，缺类型、长度、速度/指令布局、校验和接收代码；真实急停解除/确认也待确认。
- 真正 A 新版四秒+STOP APK、A/B 两手机 Wi-Fi 和真实 Unity 场景：未联调。使用只读 A 资产的独立发送器不能代替 A APK 交互验收。
- 冻结 v2 的不可区分旧 INFO/STOP 和有限退休表：详见 README；未声称绝对流身份隔离或无限历史保护。
- 真实 EEG 分类安全与阈值：没有在本阶段获得新的准确率或安全验收结论。

完整原始记录：`output/stage27/junit/`、`output/stage27/jvm/`、`output/stage27/android/`。源码中保留本目录的小型 XML、JSON 与 CSV，APK、资产、截图和大日志均未进入 Git。
