# A/B 真机联调操作与日志收集（本轮尚未真机执行）

2026-10-20。本机当前只发现`emulator-5554`，没有已连接并授权的真实手机。以下是待执行步骤，不能当作已通过结果。

## 先核实A版本，避免用错误交互验收

目前提供的A APK SHA256为`5fdaaedb6cdcfa555d0fd15f8597da8419ad25fe80c626e647050e9e4e46f911`，包名`com.fifteen.eegsender`、versionName1.0/versionCode1。它的UI写明“点击播放4秒、结束不发STOP”，与冻结文档的按住/松手约定不同。

若继续使用这份APK，按“4秒自然结束→B静默超时”记录，并将“按住持续推流、松手发STOP、四方向识别”标为**受版本不匹配阻碍/未通过**，不能自行勾成正常STOP路径通过。请A负责人提供符合已冻结约定的版本或正式讨论交互变更；本轮不修改A APK或协议。

## 设备与安装

1. A、B两台Android手机连接同一个局域网；若A开热点，则B加入该热点。先只测试A/B，避免其他接收器抢答发现。记录实际IP和型号，不套用文档旧IP。
2. 在两台测试手机开启开发者选项和USB调试，连接电脑并在手机上授权。使用`D:/BrainChair/sdk/platform-tools/adb.exe devices -l`记录序列号，明确哪台是A、哪台是B。
3. 将本轮B调试APK`output/ab-integration/BrainChair-B-ab-check.apk`安装到B（例如`adb -s B_SERIAL install -r <APK绝对路径>`）。A用待确认的正确版本；当前原始附件扩展名为`.apk.1`，安装工具要求`.apk`，本轮保留字节相同的`output/ab-integration/A-original.apk`副本。
4. B打开App，点“开始监听”，保持前台服务运行；按系统提示允许必要权限。A中先手工填写B界面显示的实际IPv4，端口9000。先验证单播，再清空IP测试9001自动发现。
5. 固定B默认调试参数0.65/0.15、3票、300ms并记录。候选0.55/0.05没有获得验收批准。A噪声/陷波保持100%，本阶段不做阈值扫描。

## 测试记录：通信和识别分开判定

| 编号 | 操作（符合冻结交互的A版本） | B应观察到 | 必须保存/人工确认 |
|---|---|---|---|
| P01 | B监听，A按住前进 | INFO计数+1、SAMPLE_BLOCK持续增加，1000Hz/62通道、源地址正确 | B状态、包数、序号/缺包计数；开始阶段应保持STOP |
| P02 | 前/后/左/右分别持续至少16秒，再松手 | 真实CCA分数、门控、稳定判决、最终输出 | 每方向是否至少正确输出、是否错误/拒识；保持时长不能当成准确率保证 |
| P03 | 正在输出运动时松手 | 合法STOP计数增加；raw/stable/control均STOP，分数清空；缓冲/会话清理 | `stopAudits`中的seq、reason、source、计时；人工记录松手动作，不能只看显示STOP |
| P04 | 松手后立即再按另一方向 | 新INFO，序列从新流开始；缓冲与投票重新积累，不能直接沿用旧判决 | 两次流的计数、generation、缓冲、新判决；analysisCount是累计计数，不要求归零 |
| P05 | 运动中断开B的Wi-Fi，A不松手 | 无新有效数据超过1秒后，本地最终STOP | 手机操作时间、B状态与诊断原因；这里是网络故障，不是正常STOP |
| P06 | 恢复Wi-Fi | 保持STOP，等A重新开始并发送新INFO | 不应凭旧缓存自动恢复；旧流后续SAMPLE可能被拒为missing-info，需明确重启推流 |
| P07 | A清空目标IP后开始 | B在9001应答，A选中正确B，数据送到应答端口 | 自动发现实际地址、B的ANNOUNCE日志、是否存在多个应答者 |
| P08 | A进后台/退出或被终止 | 查明实际是发STOP还是静默；B最终停止 | A日志与B STOP计数，不能由UI按钮名称推断报文 |
| P09 | 无STOP、无新INFO，持续数据直接换波形 | 独立异常场景，不能用P03代替 | 该模式若A不支持，记未执行；保留Stage2.6已有1.2～1.6秒残留问题 |
| P10 | 数据错误/CRC异常 | B拒收、废弃窗口并STOP | 现有A未必能制造坏包；独立发送器测试与实际A测试分开记录 |

P02如果收包正常但没有正确识别，要分别写“通信通过、识别拒识/未通过”，不能因为数据通了就给识别打勾。单4秒试次不足以提供0.5秒预热+4秒窗口；不要为通过而缩短窗长。

每项结束后先收集日志，再停止/重启B监听。**重新开始监听会覆盖本轮私有trace文件**；STOP审计最多保留最近32次。

## 日志收集

电脑无需安装新的Python库；使用已有独立环境或任意可用Python运行：

```powershell
& 'D:/BrainChair/stage2-test-env/Scripts/python.exe' .\scripts\collect_ab_logs.py --b B_SERIAL --a A_SERIAL --label P03-release
```

将`B_SERIAL`和`A_SERIAL`替换为真实序列号。本脚本只读指定设备的本项目日志和包信息，不清除应用数据、不修改网络或系统设置。每次生成独立目录`output/ab-integration/phone/<时间>/`，收集：

- `b-status.json`：最新四层状态、报文类型计数、源地址、STOP审计。
- `b-trace.csv`：每约200ms诊断快照（允许重复同一判决，不当独立样本）。
- A/B包版本、手机型号、系统版本、各自App进程的logcat。
- `collection.json`：收集时间、序列号、标签和每个文件的成功/失败状态。

`run-as`要求B调试APK；失败会记录退出码及错误，不能假装收集成功。脚本不会自动判断测试通过或宣称真机已验收，仍需填写人工记录。遇到手机权限弹窗、无设备或`unauthorized`先处理设备授权，不能直接填“测试失败=算法错误”。

需要独立验证B的冻结STOP协议时，可先停止真实A推流，再运行：

```powershell
& 'D:/BrainChair/stage2-test-env/Scripts/python.exe' .\scripts\verify_frozen_ab_path.py --serial B_SERIAL --target-ip B_PHONE_IP
```

这是PC独立发送器对B测试，会重启B测试App；**不是A APK松手测试**。不应与A同时发送，以免来源冲突。IP和序列号必须明确填入；结果保存到独立的`output/ab-integration/phone-contract/<时间>/`，不会覆盖本轮模拟器证据。

## 如何读STOP延迟

`stopAudits[].receiveToInvalidationMs`的边界为：B的`socket.receive()`返回，到同步失效回调全部完成；有SignalEngine监听器时，本地最终STOP已在此之前写入。它包含解析、校验、调度/锁等待与同步清理，不包含内核收包前排队、A触摸处理、A→B网络、日志刷新、Unity或实际轮椅制动。

`processingMs`仅从B进入会话处理到回调完成，范围更窄。`listenersNotified`应至少为1；若没有有效STOP，审计为空或计数不变，不能拿静默超时当STOP响应计时。外部ADB看到STOP的延迟还包含200ms日志刷新，不能替代上述内部指标。

A松手至B本地停止、B至Unity停车都需要额外对时/关联事件。Unity尚未接入，禁止在本轮填写“端到端停车延迟”。

## 人工记录模板

每个P编号填：日期、A/B型号与系统、A/B版本和SHA256、序列号/IP、网络方式、阈值、操作步骤、通信结果、识别结果、STOP计数及审计seq/原因、观察到的运动残留、日志目录、结论（通过/失败/未执行/版本阻碍）、执行人与复核人。不能用“日志已收集”代替功能验收。

当前整张真机表的初始状态均为**未验证**。
