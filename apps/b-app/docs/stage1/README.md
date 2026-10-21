# Android B 第一阶段交付说明

日期：2026-10-19（Asia/Shanghai）。范围仅 A→B 原始 EEG 接收；未实现 CCA 或 Unity 输出。

## 工程和原型保护

- 工作目录：`D:/college/project-based CST/脑控轮椅`。
- 基线：`9917360`；开发分支：`codex/b-stage1-udp`。无远程推送。
- 原型备份：`D:/BrainChair/backups/prototype-before-stage1-20261019`，429 个文件逐个 SHA256 校验；只排除可再生 `.gradle`、`.kotlin` 缓存。备份含原始 Git 目录、本机配置、APK、录像、临时演示成果。
- 清单：`D:/BrainChair/backups/prototype-before-stage1-20261019-manifest.json`。
- 旧 `MainActivity`、`comm`、`model`、Python 模拟器、历史输出均保留。新版本启动 ReceiverActivity；旧 Activity 不对外导出。
- 新 APK 应用 ID 为 `com.yourteam.brainchair.receiver`，可与旧 `com.yourteam.brainchair` 同时安装；这只是本地阶段版本，不替团队决定最终应用 ID。
- 团队仓库未修改，检查时仍为干净的 `main`，HEAD `e3ced1e505e4178f61b2b1d0fa90e467d0e6b931`。
- 源码忽略缓存、build、local.properties、output、APK/AAB、录像和签名密钥。历史测试/新测试的摘要在文档中，原始产物不提交。

## 构建与安装

在本工程 PowerShell 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-stage1.ps1
```

只使用已经存在的 JDK17、Gradle8.11.1、SDK35 和依赖缓存，离线构建；不修改系统 PATH。
源码仍在本目录，英文路径 `D:/BrainChair/build-stage1` 仅为可再生构建副本，避免既有中文路径 JVM 测试问题。该路径与旧构建副本分离。

产物：`output/stage1/BrainChair-B-stage1.apk`，调试签名、minSdk24、targetSdk35、版本 `0.2.0-stage1`。
不要使用旧 `Build-App.cmd`、`Start-Demo.cmd` 或 `scripts/build.ps1` 来交付本阶段，它们是保留的原型流程，输出仍指向旧 APK。

```powershell
& 'D:/BrainChair/sdk/platform-tools/adb.exe' install -r 'output/stage1/BrainChair-B-stage1.apk'
& 'D:/BrainChair/sdk/platform-tools/adb.exe' shell am start -n 'com.yourteam.brainchair.receiver/com.yourteam.brainchair.receiver.ReceiverActivity'
```

手机上点击“开始监听”，允许通知；A、B 连同一局域网。A 自动发现或填 B 的 IPv4，按住回放按钮。B 显示 INFO、速率、通道、样本序号和日志。点击“停止监听”释放端口和锁；退到后台继续接收。前台服务不会在进程被杀后自行重启。
“暂停显示”只冻结调试文字，数据仍接收；恢复显示后查看最新状态。
通知栏也可停止监听。它不是轮椅急停，当前无 Unity/真实设备控制。

## 模块边界

```text
9001 DISCOVER → EegProtocol 校验 → ANNOUNCE 回请求源 IP:端口
9000 EEG → UdpReceiver → EegProtocol → StreamSession → ReceiverSnapshot → 调试界面
                                                └→ SampleWindow（下一阶段算法接口）
ReceiverService：前台生命周期、通知、MulticastLock/WifiLock、显式停止
```

`EegProtocol` 无 Android 依赖，独立负责字节与字段校验。`UdpReceiver` 是可在 JVM 上实测的 socket 层，发现和数据各一个线程。`StreamSession` 是同步保护的会话与环形缓冲；提供只读快照和复制后的时间主序 4 秒窗口，附带 generation、起始下标、时间与通道信息。每次新流或连续性中断更新 generation，后续滤波器必须据此重置。
接收线程不执行 CCA，不写文件，不做逐包 UI 更新。日志最多 60 条、最近序号最多 1024 个，原始样本缓冲最多 4 秒。下一阶段连续滤波需要按新样本增量消费，不能反复滤整个原始窗口。

## 接收策略及协议边界

- 数据端口9000、发现9001；监听所有本机 IPv4。发现无论广播还是单播均走同一应答逻辑，向请求源地址的临时端口回复，dataPort=9000。
- 数据按 v2、小端、16字节头、末尾 IEEE CRC32、时间主序 float32 实现。不调用旧 AA55 协议。
- 严格整包长度，不接受尾随字节；接收缓冲65535字节以识别超长包，解析上限1472。校验字节布局后才能分配样本缓冲。
- 只支持当前 dtype=1；预留 int16、非零 reserved、非有限浮点、异常通道信息和未知类型均计数拒收，服务继续运行。
- 采样率和通道从 INFO 获取，不硬编码1000/62或通道下标。接收资源范围：1–4000Hz、1–128通道，4秒样本缓冲上限8MB；超出明确拒绝，属于实现边界而非协议修改。u64超出 JVM 有符号范围或样本下标超过安全运算范围明确拒绝。
- 收到 INFO 才准备流；未收到时不猜通道配置。只有一个来源 IP:端口可持有当前流，其他来源计数拒绝；STOP、超时或手动停止后释放。
- 重复采样包丢弃；迟到或重叠数据丢弃并计数，不做重排等待。序号跳跃和样本缺口分别记录；缺口清空连续窗口，再积累新数据，不补零伪造连续 EEG。迟到包不会抵消之前已记录的缺口。
- 包时间戳需满足同一流 origin + firstSampleIndex × 1e6 / rate，允许1µs整数舍入误差。断流超时使用本机单调时钟，不能用远端 Unix 时间与本机时钟直接比较延迟。
- STREAM_STOP.lastSampleIndex 是下一个应有序号/样本总数；STOP清空流信息、样本、来源和时钟；累计统计保留到停止并重新启动监听。
- 1000ms无有效新样本/INFO时清空会话，最多再加200ms网络轮询和系统调度延迟；这只是本阶段接收状态失效，不是已实现轮椅停车保证。重复包和坏包不续期。
- 冻结协议没有 sessionId。每个合法 INFO 保守地清空窗口；重复 INFO 也会重新积累。相同来源跨流的延迟 INFO/STOP无法完全辨别，CRC也不是来源认证。记录此限制，不擅改协议；正式安全控制前需与A/C共同评估。
- 掉了 INFO 不重传：界面提示 missing-info，需A松手重新推流。不编造缺失的通道布局。
- Android已声明INTERNET、网络/Wi-Fi状态、CHANGE_WIFI_STATE、MULTICAST、WAKE_LOCK、前台服务connectedDevice与通知权限；服务持有组播锁和Wi-Fi锁。没有添加未经核实的新系统局域网权限；新Android/厂商ROM行为需要实机验证。

## 独立测试复现

`ReceiverTest.kt` 使用独立 fixture writer、逐位 CRC 算法（已用123456789→CBF43926校验），与生产 Java CRC32 实现分离。
`scripts/stage1_packets.py` 使用 Python struct/zlib，完全不导入 A 验收器。

```powershell
python scripts/stage1_packets.py --host <B的IPv4> --seconds 30
```

该脚本发送合成斜坡数据，仅验证传输和排列，不证明真实 EEG 识别。其打印的 sentBlocks 只是发送计数，必须与 B 的接收计数核对。

已安装 APK 的 API35 模拟器自动检查：

```powershell
python scripts/verify_stage1_android.py
```

脚本使用 emulator-5554、UDP转发19000→9000和19001→9001，独立发包并读取真实界面结果；会启动/停止测试App、短暂息屏并恢复，不操作团队仓库。结果在 `output/stage1/android`。
最终数值与未验证项见 `TEST-REPORT.md`；方案矛盾见 `CONFLICTS.md`。
