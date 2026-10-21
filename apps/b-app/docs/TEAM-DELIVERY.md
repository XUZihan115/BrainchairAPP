# B端首次团队同步及目录构建验证

日期：2026-10-21（Asia/Shanghai）。这是本次目录归档/构建记录，不是重做或扩大此前EEG验收。

## 来源和边界

- 团队基础：`70f7fc2479255970dfb52e40fec9ef0767b06e23`（读取到的最新远程main，A端工程）。原本地main仍保留旧`e3ced1e`，没有重置或改写。
- B端来源：`6064d5fbf818c948ae2f7926095fba77275b9a9a`；完整原本地历史保留。团队新分支 `feature/b-app-stage3` 仅新增 `apps/b-app/`。
- 使用同一团队仓库的独立Git工作区 `BrainchairAPP-b-stage3`；没有在原团队main工作目录覆盖/搬移文件，也没有合并两套独立Git历史。
- 导出162个已跟踪文件，其中152个内容一致（允许团队Git的CRLF/LF规范化）；51个Android源码、测试及构建关键文件内容全部一致。差异仅限README、子目录忽略/wrapper规则、7处个人路径脱敏和新增交付资料，原快照、工作区及暂存Git对象的逐文件SHA与换行说明见 `snapshot-manifest.json`。

## 本次实际运行

```powershell
# 在已有JDK17/SDK35/Gradle8.11.1缓存环境下
.\apps\b-app\scripts\verify-team-delivery.ps1 -GradleCommand 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -Offline
```

脚本明确使用子工程 `-p apps/b-app`，没有把B模块加入根工程 `settings.gradle.kts`。本机使用现有Gradle，完整Wrapper已归档；没有重新安装/迁移开发环境。

- 独立 `assembleDebug`：成功，Gradle本次日志 `BUILD SUCCESSFUL in 45s`。
- 四项无数据协议迁移验证：4通过，0失败；黄金报文、状态示例校验修正、小端/时间戳、异常字段。没有重跑EEG窗口比较、标定或模拟器测试。
- 本次新构建APK：`output/team-delivery/BrainChair-B-stage3-team-debug.apk`，988,428字节；SHA256 `6ff8e5aafbc6090dab6de658ac6ec305992275ca80e789c5dca66ca6d119ec1c`。仅本地保存，未将其加入源码提交。
- 原Stage3报告的APK哈希描述的是之前原工程构建，不应与本次重新打包文件哈希混为一谈。
- 完整迁移JUnit XML和机器路径等本机证据位于 `output/team-delivery/`，保持忽略。两条历史测试辅助代码的空值编译警告仍保留，不伪称全套历史测试已运行。

## 仍待验收

既有18项Stage3自动化及23条API35模拟器检查、15/16复用运动试次结果作为历史证据保留，未冒称本次重新执行。与张小衡实际Unity程序、真实A/B手机联调和完整EEG安全验收尚未完成。保持候选阈值、安全停止和急停机制，不因归档而改动算法或验收结论。

源码提交排除 `.git`、构建缓存、`local.properties`、APK/录像/临时日志、签名/密钥及原始EEG数据。未确认再分发权限的数据须另行在本地准备；不通过复制原始数据来制造可运行验收结果。
