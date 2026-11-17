# B 端 0.4.4 交付说明

APK：`BrainChair-B-0.4.4-eeg-feedback-fix.apk`，`versionCode=10`，包名 `com.yourteam.brainchair.receiver`，共 1,048,060 字节。Android Debug 签名验证通过。SHA-256：

```text
9f8ad4eb9bcfcc4f123d4bdfa7f3bcf71aa3e69b4774f455502b95fdf67c3e21
```

[预发布页](https://github.com/XUZihan115/BrainchairAPP/releases/tag/b-app-v0.4.4) · [配套测试清单](eeg-feedback-recovery-results.json) · [安装和复现](../README.md)

## 本次版本改动

- Unity 同时报 `05`（STOP）和仍在运动时，B 端将它视为正常减速，避免无故锁住后续 EEG 输出。减速仍不算静止，不能用于解除急停或故障。
- Unity Z 解除后，经 B 端确认并满足原有新鲜、静止及安全存储条件时，可同时清除相应的反馈故障锁。清除后保持 STOP，丢弃旧方向，不会自行恢复运动。
- 保留导致反馈锁的动作码、标志、速度与远端时间，便于真实复测定位。实际远端急停、故障、未知动作和发送故障仍会阻止运动。
- 包含上一期的手动优先及 4 秒限时、EEG 短暂拒识 750ms 宽限、反馈失联保护、急停持久化及两项仅供 Unity 模拟的安全开关。

A→B UDP v2、B→Unity UDP 9001 指令和 UDP 9002 状态格式、2.5 秒 CCA 窗口及阈值不变。

## 验证范围

- 0.4.4 原始交付构建成功；44 项定向 JVM 测试全部通过。包括合成四频 EEG 经实际滤波、CCA、门控和 UDP 循环测试接收器，逐方向验证运动、STOP、反馈减速及重新推流。失败的旧版本基准和每项测试结果见同目录 JSON。
- 本次在团队子目录重新执行独立构建并通过 49 项无外部 EEG 数据的 JVM 测试（原 44 项加 5 项 Unity 协议测试；0 失败、0 跳过）。预发布沿用上面已校验的原始 APK。
- 本版本尚未用真实 A/B 手机和实际 Unity 程序复测，也未重新运行 Android 模拟器或真实 EEG 准确率实验。当前证据支持软件模拟联调，不能作为真实轮椅安全验收。
- 重新从团队 Unity 分支构建时，请核对场景的「运动指令租期」是否为约定的 1 秒。团队当前已推送的 [Unity 脚本默认值](https://github.com/XUZihan115/BrainchairAPP/blob/fa53693f00dbf8ec2b3bbef50855f7737fe42e80/Assets/Scripts/WheelchairSim/WheelchairBootstrap.cs#L75) 仍写 4 秒；已部署的 Unity 实例可能使用不同配置，本次未改 Unity。

## 来源

团队基础为 `origin/main` 的 `5f3f808c9ad7601065ef74617d8aae005a01e230`。以 `feature/b-app-stage3` 的 `2f6e09c573df0f49c418567ef127ab49d2e98a7c` 归档为起点更新 `apps/b-app/`；团队根工程不改。以下是原 B 端独立仓库的本地提交，未移植其独立 Git 历史。

| 原提交 | 变更 |
| --- | --- |
| `6064d5fbf818c948ae2f7926095fba77275b9a9a` | Stage 3：Unity 收发、两种控制模式、2.5 秒 CCA；此前已归档。 |
| `3c45514eb998aca0eb17723e552d73a869b3e580` | 手动接管、4 秒限时、限制 EEG 拒识宽限。 |
| `a8ddc7e109a993f637aa22b55c21beb4db0df681` | 反馈保护、急停保存及独立安全发送路径。 |
| `cd39a59989f2db2e7ad1e9eecddb307d6cfe1562` | 两项 Unity 软件联调设置。 |
| `0324762fd0e4dcd465597a5dfd135089e07eaa35` | 0.4.4：正常减速判定和 Z 确认后的反馈锁恢复；对应本次 APK。 |
