# A→B 联调证据（2026-10-20）

所有Android操作均在同一个模拟器。原A APK测试、独立发送器测试与真机未验证三者分开；未复标阈值。

## 原版A APK实际行为

|操作|INFO|有效采样包|STOP|计算窗口|最终输出|
|---|---:|---:|---:|---:|---|
|single-click-forward|1|800|0|0|STOP|
|single-click-left|1|800|0|0|STOP|
|single-click-right|1|800|0|0|STOP|
|single-click-backward|1|800|0|0|STOP|
|single-click-rest-button|1|800|0|0|STOP|
|rapid-direction-new-stream|1|172|0|0|STOP|
|hold-and-release|1|800|0|0|STOP|
|a-process-loss|1|186|0|0|STOP|
|automatic-discovery|1|800|0|0|STOP|

五个单次按钮均发送4000点后停止发送，靠B超时清理；0个有效分析窗口，不能称四方向识别通过。长按7秒也未延长4秒发送；A的“停”按钮发送静息波形，不是STREAM_STOP或人工急停。

上表是本轮源端口修复前的双App运行记录；修复后定向复测快速新流与提前松手。下述独立发送器与35项回归测试使用最终B构建，两个构建的SHA256分别保存在evidence.json。

## 同IP换源端口的新INFO兼容修复

|版本|INFO|有效采样包|STOP|拒收|
|---|---:|---:|---:|---|
|修复前|1|172|0|{"foreign-source": 198, "missing-info": 603}|
|修复后|2|995|0|{}|

修复后仍未改变A的4秒/无STOP交互，不能因此宣称完整正常路径已通过。短触摸约1.2秒后松开，原A仍继续补满4秒、STOP计数不增加。具体时序见本地actual-a-fixed/observations.json。

## 独立发送器：冻结正常STOP路径

|数据方向|发送秒数|采样到的最终输出集合|B内部接收至同步失效ms|
|---|---:|---|---:|
|forward|16.0|FORWARD,STOP|1.306200|
|left|16.0|LEFT,STOP|0.216900|
|right|16.0|RIGHT,STOP|0.156900|
|backward|16.0|BACKWARD,STOP|0.445900|
|stop|8.0|STOP|0.201500|

共23项通信/清理检查，全部通过=True。STOP计时样本11个，范围0.087500–1.306200ms。样本很少，只是本轮观察，不是响应上限或真机保证。

边界是B的socket.receive返回，到同步回调全部完成（本地最终STOP已写入）；不包含A触摸/网络、日志读取或Unity停车。

这一路使用PC独立发送器，数据取自A资产，不是原A APK的松手行为。检查还覆盖短流反复启动和已处于运动时的静默超时；原始checks.json有逐项记录。

## 构建与未验证

选定回归测试 35 项；失败/错误/跳过合计 0。

未完成A/B两手机网络、正常松手STOP版本兼容、原A单4秒的四方向识别和B→Unity联动。Stage2.6静息拼接2个误动、无STOP切换1.2～1.6秒残留继续未解决。

详细源端口、报文数、STOP边界、测试版本和范围见同目录evidence.json；完整日志在本地output/ab-integration/，大型APK和波形不提交Git。
