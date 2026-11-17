# 本地可选测试数据位置

本目录保留为空数据占位，供原有 Gradle 测试输入声明使用；没有 EEG 采样值或伪造数据。

独立构建及 `scripts/verify-team-delivery.ps1` 的四项协议验证不依赖 EEG 数据。
Stage 2 数值测试所需合成 oracle 应由 `scripts/stage2_oracle.py` 按历史说明独立生成；真实回放测试需要另外取得有权使用的 A APK/原始标注并指定路径。原始数据、缓存和运行输出不入库。
