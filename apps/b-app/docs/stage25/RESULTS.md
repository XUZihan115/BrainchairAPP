# Stage 2.5 数值结果（脚本生成）

所有滑动窗口高度重叠，循环重复不增加独立试次数；以下不是跨被试准确率。

候选参数：{"absolute": 0.55, "difference": 0.05, "votes": 3, "debounceMs": 300, "calibrationCorrect": 285, "deploymentApproved": false, "rule": "zero calibration wrong-motion and rest-motion; maximize correct motion, ties prefer larger threshold sum"}

## 分离标定与评价

|配置|运动有效窗|正确运动|错误运动|拒识停止|静息窗|静息误动|
|---|---:|---:|---:|---:|---:|---:|
|cal-default|392|173|0|219|38|0|
|cal-selected|392|285|0|107|38|0|
|eval-default|392|173|0|219|38|0|
|eval-selected|392|226|0|166|38|0|
|eval-reference|392|151|0|241|38|0|

## 候选参数评价集：稳定判决混淆矩阵

|真实\输出|前进|左转|右转|后退|停止|
|---|---:|---:|---:|---:|---:|
|forward|96|0|0|0|2|
|left|0|74|0|0|24|
|right|0|0|38|0|60|
|backward|0|0|0|18|80|
|stop|0|0|0|0|38|

## 候选参数评价集：原始门控判决混淆矩阵

|真实\输出|前进|左转|右转|后退|停止|
|---|---:|---:|---:|---:|---:|
|forward|98|0|0|0|0|
|left|0|80|0|0|18|
|right|0|0|50|0|48|
|backward|0|0|0|29|69|
|stop|0|0|0|0|38|

## 评价集 CCA 分布

|真实标签|最高相关 min/median/max|差值 min/median/max|
|---|---|---|
|forward|0.6815 / 0.7633 / 0.8156|0.2752 / 0.3603 / 0.4514|
|left|0.4336 / 0.6786 / 0.8067|0.0129 / 0.2448 / 0.4330|
|right|0.4440 / 0.5548 / 0.7494|0.0040 / 0.1459 / 0.3814|
|backward|0.3399 / 0.4639 / 0.7114|0.0039 / 0.1137 / 0.3696|
|stop|0.4156 / 0.4640 / 0.5159|0.0140 / 0.0855 / 0.1293|

## 每段数据

对齐分数仅调用同一 Bandpass/Cca 做独立4秒诊断，无0.5秒预热，不能充当完整识别链路成绩。最后三列来自候选参数连续回放，按窗口结束所在段归属，跨试次窗口仍保留。

|ID|划分|标签|前/左/右/后 CCA|对齐 argmax|连续正确运动/错误运动/停止|
|---|---|---|---|---|---|
|0|calibration|forward|0.7215 / 0.2976 / 0.3408 / 0.3545|forward|40/0/0|
|1|calibration|forward|0.8048 / 0.2745 / 0.3849 / 0.4269|forward|56/0/2|
|2|evaluation|forward|0.7275 / 0.3270 / 0.2542 / 0.4074|forward|40/0/0|
|3|evaluation|forward|0.7831 / 0.3634 / 0.2985 / 0.3764|forward|56/0/2|
|4|calibration|left|0.2021 / 0.8249 / 0.3803 / 0.4094|left|40/0/0|
|5|calibration|left|0.2349 / 0.7210 / 0.3027 / 0.2928|left|56/0/2|
|6|evaluation|left|0.2701 / 0.7914 / 0.3300 / 0.4676|left|18/0/22|
|7|evaluation|left|0.2176 / 0.8045 / 0.3252 / 0.3848|left|56/0/2|
|8|calibration|right|0.3608 / 0.2467 / 0.6984 / 0.4065|right|34/0/6|
|9|calibration|right|0.2809 / 0.4070 / 0.7368 / 0.3977|right|41/0/17|
|10|evaluation|right|0.3174 / 0.2672 / 0.7278 / 0.4191|right|18/0/22|
|11|evaluation|right|0.2198 / 0.3192 / 0.7509 / 0.3659|right|20/0/38|
|12|calibration|backward|0.2977 / 0.2721 / 0.3373 / 0.6445|backward|8/0/32|
|13|calibration|backward|0.4221 / 0.2963 / 0.3350 / 0.6926|backward|10/0/48|
|14|evaluation|backward|0.2497 / 0.3480 / 0.3453 / 0.7236|backward|10/0/30|
|15|evaluation|backward|0.2773 / 0.3643 / 0.3512 / 0.5883|backward|8/0/50|
|16|calibration|stop|0.3293 / 0.2533 / 0.2748 / 0.5340|backward|0/0/38|
|17|evaluation|stop|0.3093 / 0.4069 / 0.3605 / 0.4480|backward|0/0/38|

## 边界和降质场景

|场景|有效窗|运动正确|错误运动|运动拒识|静息误动|首次正确ms|
|---|---:|---:|---:|---:|---:|---:|
|a-wrap-forward|138|136|0|2|0|5000|
|a-wrap-default-forward|138|136|0|2|0|5000|
|a-wrap-left|138|130|0|8|0|5000|
|a-wrap-default-left|138|77|0|61|0|5000|
|a-wrap-right|138|78|0|60|0|5000|
|a-wrap-default-right|138|28|0|110|0|7400|
|a-wrap-backward|138|34|0|104|0|7800|
|a-wrap-default-backward|138|8|0|130|0|8200|
|a-wrap-stop|58|0|0|0|2|None|
|a-wrap-default-stop|58|0|0|0|0|None|
|continuous-switch|178|61|17|60|6|5000|
|continuous-switch-default|178|42|11|85|0|5000|
|released-switch-forward|18|16|0|2|0|5000|
|released-switch-left|18|16|0|2|0|5000|
|released-switch-right|18|4|0|14|0|7400|
|released-switch-backward|18|0|0|18|0|None|
|released-switch-stop|0|0|0|0|0|None|
|noise-10.0|98|59|0|39|0|5200|
|noise-50.0|98|0|0|98|0|None|
|noise-100.0|98|0|0|98|0|None|
|degraded-noise|98|0|0|98|0|None|
|flat-signal|18|0|0|18|0|None|
|gap-timeout-restart|36|20|0|16|0|5000|

首次正确判决（模拟采样时钟，不是实际运行墙钟）：

|评价方向|默认ms|候选ms|
|---|---:|---:|
|forward|5000|5000|
|left|5000|5000|
|right|8000|7400|
|backward|12200|8400|
