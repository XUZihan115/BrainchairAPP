# -*- coding: utf-8 -*-
"""实测"按住持续推流"时循环拼接方案对识别准确率的影响。

按住按钮时, 4 秒的真实试次必须反复循环播放。拼接处会产生相位/幅度跳变,
可能被接收端的 CCA/FBCCA 误判。这里比较三种方案:

  A. hard      —— 硬切 (直接回绕)
  B. crossfade —— 尾部 50 ms 与头部线性交叉淡化
  C. cycles    —— 截断为刺激频率的整数个周期 (相位连续, 不淡化)

评价方式: 把拼接后的 40 秒流按 1 秒步长切 4 秒窗, 对四个频率跑 CCA,
统计每个方向窗口的识别正确率。

用法:
    py -3 test_loop_modes.py <assets目录>
"""
import json
import os
import sys

import numpy as np

FREQS = {'forward': 12.00, 'left': 8.57, 'right': 6.67, 'backward': 5.45}
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2']
FADE_MS = 50
STREAM_SECONDS = 40
WIN = 4000
STRIDE = 1000


def bandpass(x, fs, lo, hi):
    spec = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(x.shape[0], 1.0 / fs)
    spec[(f < lo) | (f > hi)] = 0.0
    return np.fft.irfft(spec, n=x.shape[0], axis=0)


def reference(freq, fs, n, harmonics=3):
    t = np.arange(n) / float(fs)
    cols = []
    for k in range(1, harmonics + 1):
        cols.append(np.sin(2.0 * np.pi * k * freq * t))
        cols.append(np.cos(2.0 * np.pi * k * freq * t))
    return np.stack(cols, axis=1)


def cca(x, y):
    x = x - x.mean(axis=0, keepdims=True)
    y = y - y.mean(axis=0, keepdims=True)
    qx, _ = np.linalg.qr(x)
    qy, _ = np.linalg.qr(y)
    return float(np.linalg.svd(qx.T @ qy, compute_uv=False)[0])


def loop_hard(seg, total):
    reps = int(np.ceil(total / float(len(seg))))
    return np.concatenate([seg] * reps, axis=0)[:total]


def loop_crossfade(seg, total, fade):
    """构造无缝循环流: 输出第 i 个样本时, 回绕点的最后 fade 个样本与开头线性混合。"""
    n = len(seg)
    base = loop_hard(seg, total).copy().astype(np.float64)
    if fade <= 0 or fade * 2 >= n:
        return base
    # 对每个循环边界做等功率线性交叉淡化
    k = 0
    while True:
        start = k * n                      # 本次循环起点
        if start >= total:
            break
        # 边界位于 start+n; 把 [start+n-fade, start+n) 与下一次循环的头部混合
        for i in range(fade):
            idx = start + n - fade + i
            if idx >= total:
                break
            w = (i + 1) / float(fade + 1)   # 0->1 淡化权重
            nxt = seg[(n - fade + i) % n] if False else None
            # 用下一次循环对应位置的样本 (即 seg[i]) 混合
            base[idx] = seg[n - fade + i] * (1.0 - w) + seg[i] * w
        k += 1
    return base


def loop_cycles(seg, total, freq, fs):
    """截断到刺激频率的整数个周期, 使拼接处相位连续。"""
    cycles = max(1, int(round(freq * len(seg) / float(fs))))
    n = int(round(cycles * fs / float(freq)))
    n = min(n, len(seg))
    return loop_hard(seg[:n], total)


def evaluate(stream, picks, fs, label, refs):
    correct = 0
    total = 0
    for start in range(0, len(stream) - WIN, STRIDE):
        x = stream[start:start + WIN, :][:, picks].astype(np.float64)
        x = bandpass(x, fs, 4.0, 45.0)
        scores = {k: cca(x, refs[k]) for k in FREQS}
        warm = max(scores, key=scores.get)
        correct += 1 if warm == label else 0
        total += 1
    return correct, total


def main():
    root = sys.argv[1]
    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')
    nch = meta['channelCount']
    fs = meta['sampleRate']
    names = meta['channelNames']
    data = raw.reshape(-1, nch)

    picks = [names.index(c) for c in PICK if c in names]
    refs = {k: reference(FREQS[k], fs, WIN) for k in FREQS}

    # 每个方向取第一个段
    first = {}
    for seg in meta['segments']:
        if seg['label'] in FREQS and seg['label'] not in first:
            off, cnt = seg['sampleOffset'], seg['sampleCount']
            first[seg['label']] = data[off:off + cnt, :].astype(np.float64)

    total_win = int(STREAM_SECONDS * fs)
    print('流长 %d 秒, 窗长 4 秒, 步长 1 秒 -> 每个方向 %d 个窗' % (
        STREAM_SECONDS, (total_win - WIN) // STRIDE))
    print()

    modes = ['hard', 'crossfade', 'cycles']
    summary = {m: [0, 0] for m in modes}

    for label in FREQS:
        seg = first[label]
        print('方向 %-9s (%.2f Hz), 段长 %d 点' % (label, FREQS[label], len(seg)))
        for mode in modes:
            if mode == 'hard':
                stream = loop_hard(seg, total_win)
            elif mode == 'crossfade':
                stream = loop_crossfade(seg, total_win, int(FADE_MS * fs / 1000.0))
            else:
                stream = loop_cycles(seg, total_win, FREQS[label], fs)
            c, t = evaluate(stream, picks, fs, label, refs)
            summary[mode][0] += c
            summary[mode][1] += t
            print('    %-10s 准确率 %3d/%3d = %6.2f%%' % (mode, c, t, 100.0 * c / max(t, 1)))
        print()

    print('=' * 52)
    print('汇总 (四个方向合计)')
    for mode in modes:
        c, t = summary[mode]
        print('  %-10s %3d/%3d = %6.2f%%' % (mode, c, t, 100.0 * c / max(t, 1)))


if __name__ == '__main__':
    main()
