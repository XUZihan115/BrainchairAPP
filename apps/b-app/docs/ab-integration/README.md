# A→B 实际交互检查与 Unity 联动准备

2026-10-20。分支`codex/b-ab-integration`，从Stage2.6提交`500b81c`开始。Stage1～2.6的提交和产物保留；团队仓库不参与本轮写入，没有推送。

**本轮最重要发现：提供的A APK与冻结文档的交互不一致，正常“按住持续推流、松手STOP”尚不能由这份A APK完成验收。** 本轮完成了同一模拟器内原A/B App交互、B端小范围兼容修复、独立正常STOP路径验证、真机操作与日志准备、Unity协议确认清单。没有新调CCA参数，也没有批准识别安全验收。

## 1. 当前能访问和运行的版本

- A原附件：`app-debug.apk.1`，SHA256 `5fdaaedb6cdcfa555d0fd15f8597da8419ad25fe80c626e647050e9e4e46f911`。只制作了字节相同、便于安装的`.apk`副本，未改写原APK。
- A包名`com.fifteen.eegsender`，versionName1.0 / versionCode1。B本轮包名仍为`com.yourteam.brainchair.receiver`，versionName`0.3.2-ab-check` / versionCode4。
- 设备检查只有`emulator-5554`，没有已连接并授权的真实手机。本轮是**同一个模拟器上的两个真实App**，不是A/B两手机联调；同模拟器的IP、广播和延迟不能外推到热点或真实Wi-Fi。
- 可访问的`system-architecture.md`与Stage1保存副本内容相同，第4节仍标明B→C草案。若ChatGPT项目有更新且未提供，本轮没有自动获得。

## 2. A→B实际流程结果

| 流程 | 本轮实际结果 | 验收结论 |
|---|---|---|
| A开始前/后/左/右/静息 | 每次1个INFO、800个SAMPLE_BLOCK，1000Hz/62通道，共4000点 | 当前A/B接收解析成功 |
| B形成对应运动判决 | 每段只有4秒；B还需0.5秒预热及完整4秒窗，未形成分析结果 | 当前A版本的四方向识别路径未通过，不缩短窗口迁就 |
| 松手发送STOP | A UI明确说明结束不补STOP；短触摸约1.2秒松开后仍发满4秒；长按7秒也仅发4秒 | 与冻结交互不一致；实际A正常松手STOP未验证通过 |
| A自然结束 | 没有STOP，B静默超时后清理会话、保持STOP | 超时路径观察到；不能改称正常STOP |
| A再次开始 | 间隔超过超时后可开始新流；快速切方向发现源端口更换兼容问题，已修复 | 新INFO边界可清理旧流，见下一节 |
| A自动发现 | 原A发起9001发现，B记录ANNOUNCE，随后收到A数据 | 同模拟器成功，真机广播仍未验证 |
| A进程突然消失 | 先收到部分数据，未收到STOP，B随后超时 | 进程中断测试；不是无线网络断开实测，且A短流原本未进入运动 |

A界面上的“■ 停”按钮发送的是静息EEG片段；它不是人工急停，也没有在本轮被观察为STREAM_STOP。不能凭按钮文字推断其协议语义。

原版A的4秒行为由UI文字与实际B报文计数共同确认。测试未读取A按钮标签代替识别；按钮仅由测试人员/脚本选择输入，进入B的是原始EEG报文。完整数据与截图保存在`output/ab-integration/`；本轮脚本临时扩大模拟器视口以访问所有原按钮，结束已恢复1080×2280，没有修改电脑或手机的全局开发环境。

## 3. 修复：同一个A的新INFO换UDP源端口

A每个试次使用新的临时源端口。修复前，B把活动来源绑定为IP+端口，快速切方向的新INFO被拒，随后出现大量foreign-source和missing-info，不能正常开始下一流。

修复规则：已经通过v2包头、CRC、seq=1和INFO字段校验的**同一IP**新INFO，可更换来源端口；接收新流前同步STOP、清空缓冲和投票、重新预热。其他IP的活动流抢占、没有INFO的端口变化，以及旧端口迟到的SAMPLE/STOP仍被拒绝。

实际原A定向复测：INFO由修复前1个变为2个，新方向800个样本包能接收；该次复测拒收计数为空。它解决的是通信会话兼容问题，没有把4秒片段伪造成可识别长流。

协议v2没有独立会话ID；当前仍按一个A运行，不宣称支持同一IP后多个发送实例。未改变任何线上字段、类型、编号、端口或CRC；UdpReceiver只增加接收时间戳，没有另写协议解析器。

## 4. 冻结的正常STOP路径：独立发送器验证

原A无法执行这个交互，因此另用PC独立测试发送器，按冻结v2发送较长EEG流和合法STOP。四方向各使用资产中16秒数据、静息8秒，B采样到的最终输出分别为对应方向/STOP，静息保持STOP。这里只证明对应输出确实出现，不给出新的准确率或阈值批准。

23项通信/清理检查通过，包括：INFO和采样计数、合法STOP后raw/stable/control及分数清理、反复短流重启、实际输出前进后的静默断流、超时后新INFO重新开始。

新增STOP审计记录了11次合法STOP：**从B的socket.receive返回，到同步清理回调完成的观察范围为0.0875～1.3062ms**。有SignalEngine监听器，内部最终STOP在回调完成前已写入。这是本轮模拟器内部时段的观察，不是手机响应上限。

不包含A触摸/松手处理、A→B网络传播、内核收包前排队、日志刷新或Unity执行；没有测量端到端停车。`processingMs`从进入会话处理开始，边界更窄，不应与`receiveToInvalidationMs`混用。

明细、原A/独立发送器/构建版本的分开证据见 [RESULTS.md](RESULTS.md) 和 [evidence.json](evidence.json)。

## 5. 真机准备

手机尚未连接，所有A/B手机结果标为**未验证**。已准备 [操作与日志指南](PHONE-TEST-GUIDE.md)、[人工记录模板](PHONE-TEST-RECORD.md)、只读日志收集器`collect_ab_logs.py`。日志收集器已在模拟器上验证可收集两个指定App的版本、状态、私有诊断CSV和各自进程日志；此自检不是手机验收。

下一次真机测试必须先确认A版本和实际交互；如果仍用当前4秒/无STOP版本，不能将其填成P02/P03通过。先验证单播、再自动发现，逐项保存日志。真实Wi-Fi断开/恢复、A前后台行为和手机调度都需要人工执行并记录。

## 6. 尚存安全问题

- Stage2.5/2.6候选0.55/0.05在完整静息拼接场景的2个错误后退窗口继续未解决；候选未采用为默认或验收参数。
- 无STOP、也无新INFO，且数据持续有效时，B只能判断报文合法性、连续性、新鲜度和窗口的CCA可靠性；**不能直接知道真实意图已从运动变为静息**。4秒窗口仍可能支持旧方向，已有约1.2～1.6秒停止残留继续保留。
- 新INFO是明确的流边界，已同步停止重置；它与“没有任何边界标志，只换波形”是两个不同场景。
- 数据持续输入且分类仍错误地高置信通过时，B的断流/结果过期检查不会自动解决分类错误；未来C的1秒链路超时也不会纠正仍在持续收到的错误运动指令。
- B尚未实现新链路的完整人工优先级与急停锁止，Unity也未接入。当前仅提供识别模块的本地输出。

## 7. Unity准备与下一阶段建议

`ControlOutputSource.currentControl()`已作为独立只读接口，读取会检查时效，返回方向、原因、版本和有效期。未来发送模块应每次发送/心跳前读取当前状态，不缓存过期运动。接口不含线上编号、端口、急停位或解除语义。

所有B→Unity草案值及必须确认的问题见 [UNITY-PROTOCOL-CHECKLIST.md](UNITY-PROTOCOL-CHECKLIST.md)。没有自行实现9100编码器、广播或发送模块，也没有把草案视作Unity负责人已确认。

建议按以下顺序推进，仍需小组确认后成为新开发任务：

1. A负责人确认当前APK是否提供错版本，优先提供符合冻结约定的持续推流/松手STOP构建；记录版本、SHA256和实际抓包。若决定改成点击试次，需要正式评审交互与窗口匹配，不能让B自行缩窗。
2. 使用本轮B APK完成两手机的正常路径与故障记录，不再对旧EEG片段扫阈值。识别拒识与通信成功分别记结论。
3. 与Unity负责人先冻结STOP/急停、端口、方向、字节布局、心跳、C端1秒合法指令超时与地址方式；确认状态回传是否纳入。
4. 再实现独立发送模块及C端接收器，先用人工固定指令验证映射、心跳、STOP/急停和超时，随后接入EEG最终输出。测试演示持续标注“录制EEG回放，识别安全尚未验收”。

## 8. 文件与复现

新增控制读取接口`signal/ControlOutputSource.kt`；StreamSession增加有效包计数、同IP新INFO端口切换和最近32次STOP审计；UdpReceiver记录接收时刻；RecognitionTrace导出诊断字段。没有更改Bandpass、Cca或DecisionGate。新增7项集成单元测试，与已有通信/信号/安全测试合计选定35项通过，0失败、0错误、0跳过；Lint仍为0错误和3个已有警告。

构建：`scripts/build-ab-integration.ps1`，独立目录`D:/BrainChair/build-ab-integration`，产物`output/ab-integration/BrainChair-B-ab-check.apk`，不会覆盖旧阶段APK。Python与Android开发环境复用现有安装。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-ab-integration.ps1
& 'D:/BrainChair/sdk/platform-tools/adb.exe' -s emulator-5554 install -r .\output\ab-integration\BrainChair-B-ab-check.apk
& 'D:/BrainChair/stage2-test-env/Scripts/python.exe' .\scripts\probe_actual_a_apk.py
& 'D:/BrainChair/stage2-test-env/Scripts/python.exe' .\scripts\verify_frozen_ab_path.py
```

原A脚本需要先有字节相同的`output/ab-integration/A-original.apk`并安装到测试模拟器；完整首次记录保留在before-peer-fix，修复后定向脚本为`probe_actual_a_apk.py --focused`。首次试跑曾因A拒绝loopback目标、部分按钮超出视口而未收包，工具修正为实际网卡IP及临时测试视口后重新执行，预检记录未用作功能结论。

脚本会重启模拟器上的测试App、覆盖该次诊断文件；实际保存的原始日志分别位于`before-peer-fix/`、`actual-a-fixed/`、`contract-sender/`，报告已保留小型证据。构建产物、录像、缓存和原始大文件均不提交Git。
