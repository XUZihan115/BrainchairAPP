# Stage 2.6 可复核数值结果

正常波形下修复前后 CCA、门控、稳定和输出逐记录相同：**True**。不将停止逻辑修复描述为分类准确性提高。

## 原始试次为统计单位

历史保留评价集8个运动试次、1个静息片段；此前已查看过，不称新独立测试集。以下为修复后；修复前相同。

### default / single

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|0|0|0|0|2|
|left|0|0|0|0|2|
|right|0|0|0|0|2|
|backward|0|0|0|0|2|
|stop|0|0|0|0|1|

运动试次无输出：8/8；有错误运动的试次：0；静息误动片段：0/1。
有效运动滑窗中停止次数：0/0（仅补充诊断，不当独立样本）。

### default / repeat

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|2|0|0|0|0|
|left|0|2|0|0|0|
|right|0|0|2|0|0|
|backward|0|0|0|1|1|
|stop|0|0|0|0|1|

运动试次无输出：1/8；有错误运动的试次：0；静息误动片段：0/1。
有效运动滑窗中停止次数：205/304（仅补充诊断，不当独立样本）。

### candidate / single

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|0|0|0|0|2|
|left|0|0|0|0|2|
|right|0|0|0|0|2|
|backward|0|0|0|0|2|
|stop|0|0|0|0|1|

运动试次无输出：8/8；有错误运动的试次：0；静息误动片段：0/1。
有效运动滑窗中停止次数：0/0（仅补充诊断，不当独立样本）。

### candidate / repeat

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|2|0|0|0|0|
|left|0|2|0|0|0|
|right|0|0|2|0|0|
|backward|0|0|0|2|0|
|stop|0|0|0|0|1|

运动试次无输出：0/8；有错误运动的试次：0；静息误动片段：0/1。
有效运动滑窗中停止次数：130/304（仅补充诊断，不当独立样本）。

single=每个原始4秒片段单独开始/停止，均需如实计入无法启动；repeat=同一试次硬回绕3次提供12秒输入，只算该原始试次1个单位。repeat矩阵取保守类别：若出现任何错方向，以首次错方向计；否则至少一次正确输出计正确；完全不运动计停止。正确格不代表全程持续正确输出。

完整四层窗口混淆矩阵（CCA候选、门控、稳定、最终输出）见 layer-matrices.json；窗口仅为诊断单位，候选argmax本身没有静息拒识能力，不等同于控制输出。

## 全部试次拼接与无STOP切换（窗口诊断）

### default 全部试次拼接

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|136|0|0|0|2|
|left|0|77|0|0|61|
|right|0|0|28|0|110|
|backward|0|0|0|8|130|
|stop|0|0|0|0|58|

### default 无STOP切换

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|16|0|0|0|2|
|left|4|18|0|0|18|
|right|0|4|5|0|31|
|backward|0|0|3|3|34|
|stop|0|0|0|0|40|

### default 正在前进转静息（无STOP）

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|16|0|0|0|2|
|left|0|0|0|0|0|
|right|0|0|0|0|0|
|backward|0|0|0|0|0|
|stop|5|0|0|0|35|

### candidate 全部试次拼接

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|136|0|0|0|2|
|left|0|130|0|0|8|
|right|0|0|78|0|60|
|backward|0|0|0|34|104|
|stop|0|0|0|2|56|

### candidate 无STOP切换

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|16|0|0|0|2|
|left|5|25|0|0|10|
|right|0|6|12|0|22|
|backward|0|0|6|8|26|
|stop|0|0|0|6|34|

### candidate 正在前进转静息（无STOP）

|真实/输出|前|左|右|后|停止|
|---|---:|---:|---:|---:|---:|
|forward|16|0|0|0|2|
|left|0|0|0|0|0|
|right|0|0|0|0|0|
|backward|0|0|0|0|0|
|stop|7|0|0|0|33|

## 无STOP切换延迟

|配置/场景|目标|切换前输出|首次停止ms|首次正确稳定ms|静息持续停止ms|错误运动窗|
|---|---|---|---:|---:|---:|---:|
|default/switch-without-stop|left|FORWARD|1000|3800|None|4|
|default/switch-without-stop|right|LEFT|1000|3800|None|4|
|default/switch-without-stop|backward|RIGHT|800|4200|None|3|
|default/switch-without-stop|stop|STOP|200|200|200|0|
|default/active-to-rest|stop|FORWARD|1200|1200|1200|5|
|candidate/switch-without-stop|left|FORWARD|1200|3200|None|5|
|candidate/switch-without-stop|right|LEFT|1400|3400|None|6|
|candidate/switch-without-stop|backward|RIGHT|1400|3400|None|6|
|candidate/switch-without-stop|stop|STOP|200|200|1600|6|
|candidate/active-to-rest|stop|FORWARD|1600|1600|1600|7|

None=本段8秒观测内未出现；停止延迟按200ms采样记录，非真机上限。持续停止指此后直到该段结束均停止，不能证明之后永不再误动。

## 状态机修复前后

|场景|旧存储状态|旧读取后|新存储状态|新读取后|
|---|---|---|---|---|
|STREAM_STOP synchronous|FORWARD|STOP|STOP|STOP|
|CRC current source|FORWARD|FORWARD|STOP|STOP|
|1200ms stale window despite fresh incoming data|FORWARD|FORWARD|STOP|STOP|
|foreign malformed ignored|FORWARD|FORWARD|FORWARD|FORWARD|
|gap synchronous|FORWARD|STOP|STOP|STOP|
|new INFO synchronous|FORWARD|STOP|STOP|STOP|
|data timeout event|FORWARD|STOP|STOP|STOP|

旧版没有独立最终输出，表中用其stable作对照；新版本记录control.output。旧版snapshot读取已有STOP兜底，存储状态滞后不等同于已向真实设备发送旧指令。迟到窗口测试注入固定分数和一次1.2秒计算延迟，只用于隔离生命周期问题，不用于EEG准确性。

## 识别质量诊断

|方向|首次4秒对齐真类ρ均值|重复第三遍对齐均值|初始化状态差异最大值|混合滑窗真类ρ min/median/max|
|---|---:|---:|---:|---|
|forward|0.7592|0.7444|0.0368|0.6818 / 0.7607 / 0.8218|
|left|0.7854|0.7888|0.0121|0.5159 / 0.6800 / 0.8361|
|right|0.7285|0.7224|0.0238|0.4031 / 0.6081 / 0.7553|
|backward|0.6623|0.6650|0.0334|0.3210 / 0.5183 / 0.7251|

四个基频的滤波器稳态幅值增益：{"12": 0.9999843623557853, "8.57": 0.9957397463302229, "6.67": 0.9710003195450805, "5.45": 0.910908628231529}

完整逐试次、错误与延迟记录见同目录 CSV。`safetyAccepted=false` 为验收失败，不能用单元测试运行通过覆盖此结果。
