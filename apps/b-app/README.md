# BrainChair Android B 端（Stage 1～3）

这是可独立构建的 Android 子工程，归档自 B 端本地 `codex/b-stage3-unity` 的提交 `6064d5fbf818c948ae2f7926095fba77275b9a9a`。团队仓库根目录的 A 端/原 Android 工程保持独立；请在 Android Studio 中打开本目录，或明确以本目录为 Gradle 项目路径。

本次是 B 端成果首次纳入团队仓库。原独立仓库的历史留在本地，没有合并两套无关 Git 历史；原提交号和归档差异见 [来源记录](docs/ORIGIN-HISTORY.md)。

## 当前能力与验证边界

- 已实现：A→B UDP v2 数据9000/发现9001、CRC32、通道/时间戳与流缓冲；连续4～45Hz滤波、9通道CCA、正式2.5秒窗口；门控/投票/去抖、STOP优先、新流隔离、超时与急停锁存。
- 已实现：手动/EEG双模式，Unity IP与速度保存，B→Unity Sum8 UDP9001、每200ms持续发送，UDP9002状态反馈。Unity运动租期按负责人最新确认使用1秒，解除急停由Unity Z键及B端人工确认完成，没有0x07。
- 既有模拟验证：Stage 3本地18项自动化测试、API35模拟器23条检查及独立UDP接收器验证通过；真实回放复用数据15/16运动试次稳定识别，仍有1个右转拒识。
- **尚未完成**：与张小衡实际Unity程序联调、真实A/B手机联调、完整EEG安全验收及真实轮椅安全验证。独立UDP对端收到STOP不等于真实Unity已执行停车。

阈值0.65/0.15、三窗投票、300ms去抖为当前候选配置，不是正式真实EEG安全标定通过。A端当前协商为按住持续推流、松手STOP、换方向结束旧流再发INFO；最新A APK行为仍需实际核验。

## 独立构建

需要JDK17、Android SDK Platform35/对应构建工具。Gradle Wrapper固定8.11.1（AGP8.9.2、Kotlin2.1.20）；minSdk24/targetSdk35。配置自己的 `ANDROID_HOME` 或本机 `local.properties`，不要提交机器路径。

在团队仓库根目录执行：

```powershell
cd apps/b-app
.\gradlew.bat assembleDebug
# 目录迁移所需的4项无数据协议检查 + 独立构建
.\scripts\verify-team-delivery.ps1
```

macOS/Linux进入本目录后运行 `./gradlew assembleDebug`。不要仅在团队根目录调用子目录wrapper而省略 `cd` 或 `-p`，否则可能构建到根目录的另一套Android工程。

Wrapper首次使用可能下载固定Gradle和依赖。已有Gradle8.11.1可执行：

```powershell
.\scripts\verify-team-delivery.ps1 -GradleCommand '<existing-gradle-8.11.1>/bin/gradle.bat' -Offline
```

APK默认在 `app/build/outputs/apk/debug/app-debug.apk`，验证脚本另复制到 `output/team-delivery/BrainChair-B-stage3-team-debug.apk`；均仅本地保存，未上传历史APK。包名 `com.yourteam.brainchair.receiver`，版本 `0.4.0-unity` / code6。

## 运行与联调

1. 安装本地APK，手机和Unity电脑连接同一局域网；输入电脑IPv4和速度，保存并启动控制服务。
2. 先用手动模式独立验证四方向、STOP、急停和状态反馈，不依赖A。确认电脑UDP9001入站与手机9002反馈可用。
3. 切到EEG模式后开启新的A信号流；新方向未可靠识别前保持STOP。停止/急停不能由旧CCA结果覆盖。
4. Unity Z解除后，B收到新鲜静止、无故障及已解除反馈，再人工确认解除；不会恢复旧方向。

可选Python3标准库独立协议对端：

```powershell
python scripts/stage3_udp_peer.py --port 9001 --lease-ms 1000 --out output/peer
```

这是测试接收器，不是张小衡的Unity程序。协议细节和真机检查步骤见 [Stage 3接口说明](docs/stage3/integration-notes.md)，当前团队A协议另见 [根目录协议](../../docs/udp-protocol.md)。

## 源码、测试和资料

- `app/src/main/.../receiver`：冻结的UDP v2、CRC与会话缓冲；`signal`：滤波/CCA/门控；`control`：最终输出/模式/安全/Unity；Activity只承担调试显示。
- `app/src/test`：阶段自动化测试；归档含历史4秒测试与外部数据测试，不建议直接把无数据的全套测试当成当前2.5秒验收。迁移验证只选择4项无数据协议检查，不重跑EEG实验。
- [Stage 1](docs/stage1/README.md)、[Stage 2](docs/stage2/README.md)、[Stage 2.5](docs/stage25/README.md)、[Stage 2.6](docs/stage26/README.md)、[Stage 2.7](docs/stage27/README.md)、[Stage 3实际报告](docs/stage3/README.md)。历史“未推送”等表述是当时状态，不能视为本次同步状态。
- [四窗口离线比较](docs/cca-window-comparison.md)及逐试次/逐窗口CSV已保留；不把重叠窗口当独立试验，也不把已暴露数据当新验收集。
- `scripts/`保留实验/回放/历史构建工具。旧 `Build-App.cmd`、`Start-Demo.cmd`和阶段脚本含原开发机路径，不是团队通用入口，不要据此安装或迁移环境。
- 本次源码来源清单：[snapshot-manifest.json](docs/snapshot-manifest.json)；目录构建验证：[TEAM-DELIVERY.md](docs/TEAM-DELIVERY.md)。

不包含原始EEG数据、A/B APK、构建缓存、机器配置、签名或录像。真实回放工具所需数据须另行取得使用权限；用 `--data` / `--apk` / Gradle `-Pstage3Data=...` 等参数指向本地输入，缺失时不得伪造测试结果。历史验收器 `acceptance_probe.py` 仅留作参考，不作为唯一验收依据。
