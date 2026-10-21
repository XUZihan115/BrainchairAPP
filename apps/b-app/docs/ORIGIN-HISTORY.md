# B端来源及原始Git提交

团队分支从最新 `origin/main` 的 `70f7fc2479255970dfb52e40fec9ef0767b06e23` 创建。`apps/b-app` 是原B独立仓库当前源码快照，不是把原B仓库历史合并到团队main。原B仓库及所有提交仍在本地保留；下列原提交号不能冒充已存在于GitHub的团队提交。

|日期|原B提交（完整SHA）|阶段成果|
|---|---|---|
|2026-10-19|`9917360f67b143ee105c84ac97db69eb65cacb29`|Preserve original Android simulation prototype before UDP v2 development|
|2026-10-19|`b7eaf03cf632a920cbb9bd65b089c349e779e055`|Implement stage 1 EEG UDP v2 receiver with independent tests and audit evidence|
|2026-10-19|`467f1060f0bfff23834ca2923009e219764d5a01`|Implement stage 2 streaming EEG filtering and CCA with independent numerical and Android tests|
|2026-10-19|`1fed131a9be72cd5937f1a8aa1dc3a38ba3cc000`|Validate APK EEG replay with held-out calibration and document unsafe threshold cases|
|2026-10-19|`500b81c177c9be4415ed478cfadb4a5db308165f`|Fix synchronous recognition invalidation and stale result leases; add trial-level safety acceptance|
|2026-10-20|`3a35592a0520a2d04c8b708420b623d8d89b2a8c`|Verify actual A APK lifecycle, support peer port restart, and prepare Unity integration|
|2026-10-20|`e09eea1ea58ab01a0b57ac6b07d95b470c09f06c`|Implement stage 2.7 stream isolation, latched safety output and simulated Unity delivery|
|2026-10-20|`f6dcb4785d6e064f9f06a9fe5068165c509bf6b7`|Record stage 2.7 APK tests, four-second timing conflict and Unity protocol blockers|
|2026-10-20|`f899d86b5b7f033e0f1b3669bf96698086dabee4`|Clarify signal end deadline versus source STOP and mock delivery latency|
|2026-10-20|`5e789208772f214780cf44a69a1a2f372f513172`|Compare four CCA windows on trial-bounded real EEG without changing production defaults|
|2026-10-21|`6064d5fbf818c948ae2f7926095fba77275b9a9a`|Integrate Unity Sum8 UDP control and feedback with 2.5s EEG and safe mode barriers|

## 归档差异

- 所有Kotlin源码、Android资源、清单、Gradle配置和原测试代码按6064d5f导出，未重新开发识别或控制逻辑。Git存储按团队现有规范统一CRLF/LF；内容核对明确只允许换行差异。
- 根README改为团队使用说明；新增通用目录构建验证脚本及空数据占位；仅B子目录增加忽略规则和wrapper换行/执行权限。
- 以下历史资料/工具中个人聊天目录路径替换为本地 `app-debug.apk.1` 占位，不改变数据SHA、分类/测试数值或原项目文件：
  - `scripts/stage27_sender.py`
  - `scripts/validate-stage25.ps1`
  - `scripts/validate-stage26.ps1`
  - `docs/ab-integration/README.md`
  - `docs/stage25/data-inspection.json`
  - `docs/stage25/README.md`
  - `docs/stage27/evidence.json`

- 原始EEG/A APK/所有历史B APK/缓存不在快照中；报告中的哈希和结果描述的是原本地测试，不能被理解为本次重新运行了那些实验。
- 原开发机路径的历史脚本保留供追溯；从团队README的通用构建入口运行。
- 两套Git对象历史仍独立，团队只增加本次快照提交，不虚构此前已同步过Github。
