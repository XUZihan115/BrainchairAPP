# -*- coding: utf-8 -*-
"""最终权威标定: 试次头对齐窗 + 普通 rfft 带通 (B 端必须遵守的对齐口径)。

为什么是这个口径:
  - A 端把同方向 4 试次合并成 16000 点大段硬回绕循环播放, 段内有试次拼接跳变
  - B 端若固定定时器任意取窗 -> 窗口跨跳变 -> 绝对门全废(实测全窗长倒挂)
  - B 端唯一可行实现: 从 STREAM_INFO 锚定试次头(sampleIndex 对齐 4000 点周期),
    窗口永远从试次头开始取 -> 无跳变 -> 等价于本脚本「段头对齐窗 + 普通带通」

输出:
  - 基线: RHO_FULL = 3s 对齐窗正确方向均值, 静息最高, 方向最低, 余量
  - 陷波 alpha 网格: 正确方向均值/最低 vs 静息最高, 翻转点
  - 置信度 0..100 -> NOTCH_ALPHA 反查(线性目标 rho, 同旧 degrade_reference)
"""
import json
import os
import sys

import numpy as np

FREQS = {'forward': 12.00, 'left': 8.57, 'right': 6.67, 'backward': 5.45}
LABELS = list(FREQS.keys())
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2', 'PO7', 'PO8']
HALF_WIDTH_HZ = 0.6
BAND_LO, BAND_HI = 4.0, 45.0
WINDOW = 3000
RHO_DEEP = 0.30
NOTCH_GRID = [0.0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5,
              0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9]


def bandpass_win(x, fs):
    """普通 rfft 带通(B 端对窗口本身的处理)。"""
    spec = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(x.shape[0], 1.0 / fs)
    spec[(f < BAND_LO) | (f > BAND_HI)] = 0.0
    return np.fft.irfft(spec, n=x.shape[0], axis=0)


def degrade_notch(x, fs, alpha, halfwidth=HALF_WIDTH_HZ):
    """对窗口本身直接做陷波(与 A 端降质等价; A 端是整段循环 FFT, 这里对齐窗近似)。"""
    if alpha <= 0.0:
        return x
    n = x.shape[0]
    spec = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(n, 1.0 / fs)
    mask = np.ones_like(f)
    for f0 in FREQS.values():
        for k in (1, 2, 3):
            fc = k * f0
            if fc < 2.0:
                continue
            hit = (f >= fc - halfwidth) & (f <= fc + halfwidth)
            mask[hit] = (1.0 - alpha) * mask[hit]
    spec *= mask[:, None]
    return np.fft.irfft(spec, n=n, axis=0)


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


def main():
    root = sys.argv[1]
    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')
    nch = int(meta['channelCount'])
    fs = int(meta['sampleRate'])
    names = meta.get('channelNames') or []
    data = raw.reshape(-1, nch)
    picks = [names.index(c) for c in PICK if c in names]

    dirs_segs = {l: [] for l in LABELS}
    rest_segs = []
    for seg in meta['segments']:
        if seg['label'] in FREQS:
            dirs_segs[seg['label']].append(seg)
        else:
            rest_segs.append(seg)

    print('通道(%d): %s' % (len(picks), [names[i] for i in picks]))
    print('口径: 试次头对齐窗 %d 点 (%.1f s) + 窗口内普通 rfft 带通\n' % (WINDOW, WINDOW / float(fs)))

    # 静息基线(不降质, alpha=0)
    rest_peaks = []
    for seg in rest_segs:
        off = int(seg['sampleOffset'])
        x = data[off:off + WINDOW][:, picks].astype(np.float64)
        x = bandpass_win(x, fs)
        rest_peaks.append(max(cca(x, reference(FREQS[l], fs, WINDOW)) for l in LABELS))
    rest_max = max(rest_peaks)

    curves = {}
    for a in NOTCH_GRID:
        ok = []
        ok_min_seg = []
        for label, segs in dirs_segs.items():
            for seg in segs:
                off = int(seg['sampleOffset'])
                x = data[off:off + WINDOW][:, picks].astype(np.float64)
                x = degrade_notch(x, fs, a)
                x = bandpass_win(x, fs)
                scores = [cca(x, reference(FREQS[l], fs, WINDOW)) for l in LABELS]
                ok.append(float(scores[LABELS.index(label)]))
                ok_min_seg.append(float(scores[LABELS.index(label)]))
        ok = np.array(ok)
        curves[a] = (float(ok.mean()), float(np.min(ok_min_seg)))
        if a == 0.0:
            print('基线(不降质):')
            print('  正确方向: mean=%.4f  min=%.4f  (16 试次)' % (ok.mean(), ok.min()))
            print('  静息最高分: %.4f  (%s)' % (rest_max, [round(v, 4) for v in rest_peaks]))
            margin = float(ok.min() - rest_max)
            print('  绝对门区间: (%.4f, %.4f) 宽 %.4f  %s\n' % (
                rest_max, ok.min(), margin, '正向' if margin > 0 else '倒挂!'))

    print('陷波 alpha 网格 (正确方向 vs 静息最高 %.4f):' % rest_max)
    print('%-6s %-10s %-10s %s' % ('alpha', '正确mean', '正确min', '翻转?'))
    flip_a = None
    for a in NOTCH_GRID:
        mean, mn = curves[a]
        flipped = mn <= rest_max
        if flipped and flip_a is None:
            flip_a = a
        print('%-6.2f %-10.4f %-10.4f %s' % (a, mean, mn, '<<<翻转' if flipped else ''))

    rho_full = curves[0.0][0]
    print('\n置信度 -> 陷波 alpha 反查 (线性目标 rho %.3f -> %.3f):' % (RHO_DEEP, rho_full))
    xs = sorted(k for k in curves if k > 0.0)
    r = np.array([curves[k][0] for k in xs])
    fp = np.array(xs)
    for c in range(0, 101, 10):
        target = RHO_DEEP + (rho_full - RHO_DEEP) * c / 100.0
        if target >= rho_full:
            alpha = 0.0
        elif target <= r[-1]:
            alpha = fp[-1]
        else:
            alpha = float(np.interp(target, r[::-1], fp[::-1]))
        print('  %-9d 目标 rho=%.3f  alpha=%.4f' % (c, target, alpha))

    if flip_a is not None:
        flip_rho = curves[flip_a][0]
        conf = 100.0 * (rho_full - flip_rho) / (rho_full - RHO_DEEP)
        print('\n翻转点: alpha=%.2f, 对应置信度≈%.0f%%' % (flip_a, conf))


if __name__ == '__main__':
    main()
