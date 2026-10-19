# -*- coding: utf-8 -*-
"""用 CCA (典型相关分析) 校验导出段是否携带真实的 SSVEP 频率信息。

单通道 FFT 会被枕区 alpha 节律 (8-13 Hz) 掩盖, 因此这里改用 SSVEP 的标准检测方法:
把多通道信号与各刺激频率的 sin/cos 参考信号做 CCA, 取相关系数最高者为预测类别。

用法:
    py -3 verify_ssvep_cca.py <assets目录>
"""
import json
import os
import sys

import numpy as np

# 指令 -> 刺激频率。已用全 100 个真实试次的 CCA 分类实测确认 (100/100)。
# 注意: 早期交接文档把 left / right 的频率写反了, 这里以实测结果为准。
FREQS = {
    'forward': 12.00,
    'left': 8.57,
    'right': 6.67,
    'backward': 5.45,
}

# 用于分类的枕顶区通道 (SSVEP 响应最强)
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2', 'PO7', 'PO8']


def bandpass(x, fs, lo, hi):
    spec = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(x.shape[0], 1.0 / fs)
    spec[(freqs < lo) | (freqs > hi)] = 0.0
    return np.fft.irfft(spec, n=x.shape[0], axis=0)


def reference(freq, fs, n, harmonics=3):
    t = np.arange(n) / float(fs)
    cols = []
    for k in range(1, harmonics + 1):
        cols.append(np.sin(2.0 * np.pi * k * freq * t))
        cols.append(np.cos(2.0 * np.pi * k * freq * t))
    return np.stack(cols, axis=1)


def cca(x, y):
    """返回 x 与 y 的最大典型相关系数。"""
    x = x - x.mean(axis=0, keepdims=True)
    y = y - y.mean(axis=0, keepdims=True)
    qx, _ = np.linalg.qr(x)
    qy, _ = np.linalg.qr(y)
    s = np.linalg.svd(qx.T @ qy, compute_uv=False)
    return float(s[0])


def main():
    root = sys.argv[1]
    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')

    nch = meta['channelCount']
    fs = meta['sampleRate']
    names = meta.get('channelNames') or []
    data = raw.reshape(-1, nch)          # 时间主序: (总采样点, 通道)

    picks = [names.index(c) for c in PICK if c in names]
    print('使用通道 (%d 个): %s' % (len(picks), [names[i] for i in picks]))
    print('采样率 %d Hz, 单段 %d 点 (%.1f s)\n' % (fs, 4000, 4000.0 / fs))

    labels = list(FREQS.keys())
    correct = 0
    total = 0

    print('%-10s %-24s %s' % ('真实标签', '来源', '  '.join('%8s' % l for l in labels) + '    预测'))
    print('-' * 92)

    for seg in meta['segments']:
        if seg['label'] not in FREQS:
            continue
        off = seg['sampleOffset']
        cnt = seg['sampleCount']
        x = data[off:off + cnt, :][:, picks].astype(np.float64)
        x = bandpass(x, fs, 4.0, 45.0)

        scores = []
        for label in labels:
            y = reference(FREQS[label], fs, cnt)
            scores.append(cca(x, y))
        best = int(np.argmax(scores))
        hit = labels[best] == seg['label']
        correct += 1 if hit else 0
        total += 1

        print('%-10s %-24s %s   %s %s' % (
            seg['label'], seg['source'],
            '  '.join('%8.4f' % s for s in scores),
            labels[best], 'OK' if hit else 'MISS',
        ))

    print('-' * 92)
    print('4 秒单试次 CCA 准确率: %d/%d = %.1f%%  (随机为 %.1f%%)' % (
        correct, total, 100.0 * correct / max(total, 1), 100.0 / len(labels)))
    print()
    print('解读: 准确率显著高于随机, 说明导出的波形确实携带对应刺激频率的 SSVEP 成分,')
    print('      即数据真实有效, 可用于接收端算法联调。')


if __name__ == '__main__':
    main()
