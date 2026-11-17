# BrainChair Android B 端 · 0.4.4

这是团队仓库内可独立构建的 B 端 Android 工程。团队根目录是 A 端，Unity 工程在 `unity-wheelchair-model` 分支。

- [下载 Android Debug APK（0.4.4）](https://github.com/XUZihan115/BrainchairAPP/releases/download/b-app-v0.4.4/BrainChair-B-0.4.4-eeg-feedback-fix.apk)
- [版本说明、测试结果与 SHA-256](docs/B-RELEASE-0.4.4.md)

## 安装及联调

1. 安装 APK（Android 7.0 或更高），手机与 Unity 电脑连接同一局域网。在 B 端填写电脑的局域网 IPv4、保存并启动服务。
2. 先以手动模式验证方向、停止和急停。B 端向电脑的 UDP 9001 发送指令，Unity 将状态发到手机的 UDP 9002。每条手动运动最长 4 秒；Unity 独立运动指令租期请在场景配置确认是约定的 1 秒。
3. 如果急停已锁定，等 Unity 静止后在 Unity 内按 Z，再点击 B 端的「确认 Unity Z 解除」。如需使用脑控，解除后请明确选择 EEG 模式，再从 A 端推流。手动方向、停止和急停按钮会接管为手动模式。
4. 正常 EEG 识别使用 2.5 秒窗口。A 端发送 `STREAM_STOP`、数据断流或失效时，B 端停止输出运动。默认调试阈值 0.65/0.15、三窗投票、300ms 去抖；无需为本次反馈修复调低阈值。

「联调安全设置」和 Debug APK 仅供 Unity 模拟。0.4.4 已通过合成 EEG 和 UDP 测试对端验证，尚未完成真实 A/B 手机和实际 Unity 程序复测。

## 构建和测试

需要 JDK 17、Android SDK Platform 35。Gradle Wrapper 固定 8.11.1（AGP 8.9.2、Kotlin 2.1.20）。配置自己的 `ANDROID_HOME`，或用 Android Studio 生成本机 `local.properties`。请在 Android Studio 中单独打开 `apps/b-app`。

在团队仓库根目录运行：

```powershell
cd apps/b-app
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest --tests '*EegFeedbackRecoveryTest' --tests '*FeedbackSafetyTest' --tests '*EmergencyReliabilityTest' --tests '*SimulationSafetySettingsTest' --tests '*ControlFixTest' --tests '*Stage3ProtocolTest'
```

macOS/Linux 将 `gradlew.bat` 换成 `./gradlew`。本地 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。上述测试无需外部 EEG 数据；部分旧的回放测试另需本地数据。

可选的独立测试对端（Python 3 标准库，仅用于隔离的模拟环境）：

```powershell
python scripts/stage3_udp_peer.py --port 9001 --lease-ms 1000 --out output/peer
```

需要收集调试日志时，可以用 `scripts/collect-control-diagnostics.ps1`；它只读已授权的手机或模拟器，日志默认保存在忽略的 `output/`。

## 源码及历史资料

- `app/src/main`：A→B UDP v2/CRC32、连续滤波和 CCA、控制仲裁及 Unity UDP；`app/src/test`：协议、识别和安全回归测试。
- [A 端 UDP v2 协议](../../docs/udp-protocol.md) · [Unity 9001/9002 接口](docs/stage3/udp-9001-interface-source.md) · [三端联调步骤](docs/stage3/integration-notes.md)。
- [CCA 四窗口离线对比](docs/cca-window-comparison.md) · [Stage 3 历史报告](docs/stage3/README.md) · [首次团队归档及构建记录](docs/TEAM-DELIVERY.md)。旧报告和 `verify-team-delivery.ps1` 中的 Stage 3 提交、哈希及结果都是当期历史记录。
- [阶段来源与原始 Git 提交](docs/ORIGIN-HISTORY.md)。原独立开发历史继续保留；团队只归档 B 端子工程。

仓库不保存 APK、私有配置、原始 EEG 数据及构建缓存。0.4.4 的已校验安装包单独通过 GitHub Release 交付。
