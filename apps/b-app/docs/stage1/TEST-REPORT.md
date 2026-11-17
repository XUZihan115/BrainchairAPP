# 第一阶段实测记录

日期：2026-10-19（Asia/Shanghai）。这是开发阶段验证，不是小组最终验收，不代表真实脑控轮椅安全认证。

## 自动测试

| 检查 | 实际结果 | 证据 |
|---|---|---|
| assembleDebug | 成功 | output/stage1/build.log |
| 新接收模块 JUnit | 11项、0失败、0错误 | output/stage1/junit/TEST-com.yourteam.brainchair.receiver.ReceiverTest.xml |
| 保留原型 JUnit | 7项、0失败、0错误 | output/stage1/junit/TEST-com.yourteam.brainchair.SafetyTest.xml |
| lintDebug | 0错误、3警告 | output/stage1/lint.txt |
| JVM实际UDP环回压力 | 30秒、6000个1280字节数据包、62通道、全部接受、无缺口/解析错误，缓冲4000样本 | ReceiverTest XML 的 system-out |
| API35 APK安装与启动 | 成功 | 实际ADB安装、界面截图/XML、checks.json |
| Android发现应答 | 单播请求得到合法ANNOUNCE，nonce2271560481、数据端口9000 | output/stage1/android/checks.json |
| Android持续收流 | 15秒发3000个样本包；连同INFO/STOP共3002包，全部接受，无缺包 | normal-stop.xml及checks.json |
| Android异常处理 | CRC错1、长度错2、重复1、迟到1、序号缺口1、样本缺口5，均与构造场景一致 | malformed-and-order.xml及checks.json |
| Android无STOP断流 | 等待超时后清空流信息、缓冲与样本下标 | timeout.xml |
| Android短暂后台息屏 | API35模拟器后台息屏10秒，2000样本包+INFO/STOP均接受 | background.xml |
| 监听停止/重启 | 停止释放服务；重新绑定端口，计数清零，再次发现成功 | service-stop.xml / restart.xml |

Android脚本共9项记录全部通过，另断言dumpsys中isForeground=true；运行中服务证据service-running.txt。所有结果来自实际执行；不调用A端acceptance_probe.py。
原始XML/JUnit可能含UTC时间戳，报告日期按Asia/Shanghai记录。

## 覆盖范围

独立测试覆盖CRC标准向量、动态采样率/通道名、时间主序排列、u32序号、小包截断、尾随字节、非法长度、UTF-8、重复/空通道名、不支持的数据类型、NaN、u64越界、未知类型、重复/迟到/缺包、STOP和新推流、来源隔离、通道不一致、时间戳线性关系、非法STOP下标、1000ms边界、四秒有界缓冲、窗口复制隔离、发现nonce/CRC/UTF-8名称截断、真实UDP收发。

开发过程中首次界面检查发现频繁重复setText导致UI自动化无法获得空闲状态，已改为仅变化时更新，并增加“暂停显示（接收继续）”，随后实际APK检查通过。
检查中还修正服务监听标识，使其来自socket运行状态，而不是仅依赖启动时设置的布尔值。

## 静态检查说明

3项警告均来自保留的旧原型：旧API忽略新蓝牙属性、备份配置建议、旧布局重复背景绘制。
中文调试界面的SetTextI18n已局部显式抑制并注明由后续UI整合处理；不是全工程禁用Lint。
构建工具另提示SDK XML解析版本差异及android.overridePathCheck实验选项；本次离线构建/测试通过，未据此修改或迁移开发环境。

## 未验证，不能填写通过

- 真实A端APK、真实OpenBMI资产的端到端收流；当前发送内容是独立构造的斜坡数据。
- 真实手机热点广播发现、厂商ROM、省电策略、实际锁屏长跑、Wi-Fi切换、权限拒绝及Android16+局域网限制。
- API24真机运行（最低版本只是配置值）；当前实测API35模拟器。
- 30分钟持续运行/内存趋势；目前只有30秒JVM压力与15秒App流量、10秒模拟器息屏测试。
- CCA正确率、阈值、真实脑控意图、Unity运动、手动接管、急停锁止、蓝牙实机和300ms指标，均不属于本阶段已验成果。
- 冻结协议没有sessionId，对同源跨流迟到报文不能保证完全消歧；详见README中的接收策略。

## 可复核产物

- APK：output/stage1/BrainChair-B-stage1.apk（调试签名）。
- 签名校验：output/stage1/signature.txt。
- 构建、测试、界面证据：output/stage1/。artifact-manifest.json记录APK/测试报告/源码散列；provenance.json记录交付时本地提交。
- 原APK与原录像仍保持备份前SHA256一致，原型源代码文件未改写。
- 团队共享仓库工作区保持干净，HEAD仍e3ced1e；没有推送、合并或重置。

## 真实A/B人工联调步骤（待执行）

1. 记录A APK版本、A/B设备型号、Android版本、热点拓扑及B APK SHA256。
2. B点击开始监听；A留空目标IP触发发现。记录发现耗时、ANNOUNCE和实际数据来源，不能把A的“发送成功”当作B接收证明。
3. A按住回放方向至少10秒，核对B显示1000Hz/62通道、约200包/秒、样本下标增长；松手后确认缓冲清空。
4. 再次按住，确认新INFO和序号重启；切换方向、快速松手重按、网络断开时观察缺口、拒收和超时状态。
5. 单播定向发现与实际广播分别验证；信息包丢失时重新推流，不猜通道。
6. 运行30分钟并记录内存和接收统计，包含息屏、前后台、Wi-Fi切换。失败也保留日志，不将这些空白项改写为“已通过”。
