# -*- coding: utf-8 -*-
"""3s 窗逐 alpha 门控判决扫描 (与 calib_3s_aligned.py 相同口径, 用于替换 receiver-handbook 9.2 表)。

A 端大段陷波/加噪, B 端从每个试次头取 3000 点窗 + 普通 rfft 带通 + CCA。
门控: 绝对门 theta1 + 差值门 theta2。输出逐档门控判决表。
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
import degrade_reference as dref

FREQS = {'forward': 12.00, 'left': 8.57, 'right': 6.67, 'backward': 5.45}
LABELS = list(FREQS.keys())
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2', 'PO7', 'PO8']
BAND_LO, BAND_HI = 4.0, 45.0
HALF_WIDTH_HZ = 0.6
WINDOW = 3000
RHO_FULL, RHO_DEEP = 0.7370, 0.3000
THETA1 = 0.631         # 3s 建议值 (静息最高 0.5783 与方向最低 0.6845 中点)
THETA2 = 0.171         # 3s 建议值 (静息 gap 最高 0.2139 与方向 gap 最低 0.1283 中点)
SEED = 20261018

# 3s 对齐口径置信度 -> 陷波 alpha (来自 calib_3s_aligned.py 权威表)
NOTCH_TABLE = [0.8194, 0.7851, 0.7483, 0.7066, 0.6597, 0.6059,
               0.5417, 0.4625, 0.3607, 0.2192, 0.0000]
NOISE_TABLE = [8.0000, 8.0000, 5.5140, 3.7695, 2.7068, 1.9627,
               1.3982, 0.9594, 0.6199, 0.3439, 0.0000]


def np2(n):
    return 1 << (n - 1).bit_length()


def bandpass_win(x, fs):
    spec = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(x.shape[0], 1.0 / fs)
    spec[(f < BAND_LO) | (f > BAND_HI)] = 0.0
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


def region_rms(x, fs):
    n = x.shape[0]
    N = np2(n)
    pad = np.empty((N, x.shape[1]), dtype=np.float64)
    pad[:n] = x
    if N > n:
        pad[n:] = x[:N - n]
    spec = np.fft.rfft(pad, axis=0)
    f = np.fft.rfftfreq(N, 1.0 / fs)
    b = (f >= BAND_LO) & (f <= BAND_HI) & (f > 0) & (f < fs / 2.0)
    e = np.sum(np.abs(spec[b]) ** 2, axis=0)
    return float(np.sqrt(2.0 * np.sum(e) / (float(N) ** 2 * x.shape[1])))


def notch_region(x, fs, alpha):
    """大段陷波 (SignalDegrade 权威语义: 2 的幂循环补齐 + target_mask 只衰减一次)。"""
    if alpha <= 0.0:
        return x
    n = x.shape[0]
    N = np2(n)
    pad = np.empty((N, x.shape[1]), dtype=np.float64)
    pad[:n] = x
    if N > n:
        pad[n:] = x[:N - n]
    spec = np.fft.rfft(pad, axis=0)
    f = np.fft.rfftfreq(N, 1.0 / fs)
    keep = np.where(dref.target_mask(f)[:, None], spec, 0.0)
    band = np.fft.irfft(keep, n=N, axis=0)[:n]
    return x - alpha * band


def score_all(w, labels_out, fs):
    """对窗口计算 4 个频率的 CCA, 返回 (rho 列表, 获胜 label, 是否判对)。"""
    rho = [cca(w, reference(FREQS[l], fs, WINDOW)) for l in LABELS]
    idx = int(np.argmax(rho))
    return rho, LABELS[idx], LABELS[idx] == labels_out


def gate(rho, t1, t2):
    order = np.argsort(rho)[::-1]
    w, s = int(order[0]), int(order[1])
    if rho[w] < t1:
        return -1
    if rho[w] - rho[s] < t2:
        return -1
    return w


def conf_to_alpha(table, c):
    if c >= 100:
        return 0.0
    if c <= 0:
        return table[0]
    pos = c / 10.0
    i = int(pos)
    frac = pos - i
    return table[i] + (table[i + 1] - table[i]) * frac


def main():
    root = sys.argv[1]
    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')
    nch = int(meta['channelCount'])
    fs = int(meta['sampleRate'])
    names = meta.get('channelNames') or []
    data = raw.reshape(-1, nch)
    picks = [names.index(c) for c in PICK if c in names]

    spans = {}
    for seg in meta['segments']:
        lab = seg['label']
        off, cnt = int(seg['sampleOffset']), int(seg['sampleCount'])
        if lab in spans:
            a, b = spans[lab]
            spans[lab] = (min(a, off), max(b, off + cnt))
        else:
            spans[lab] = (off, off + cnt)

    regions = {}
    for lab, (off, end) in spans.items():
        x = data[off:end, :].astype(np.float64)
        regions[lab] = dict(x=x, rms=region_rms(x, fs))

    trials = {}
    for lab, (off, _end) in spans.items():
        trials[lab] = []
    for seg in meta['segments']:
        trials[seg['label']].append(int(seg['sampleOffset']) - spans[seg['label']][0])

    def evaluate(transform):
        ok_true, ok_min, ok_rho = [], 1.0, []
        argmax_hit = []         # 1 = argmax 命中 (赢家没选错)
        gates_hit = []          # 1 = 判对方向 (过门)
        rest_hit = []           # 1 = 静息被判方向(故障)
        rest_peaks = []
        for lab in LABELS:
            x = regions[lab]['x']
            y = transform(x, lab)
            for off in trials[lab]:
                w = y[off:off + WINDOW][:, picks]
                w = bandpass_win(w, fs)
                rho, _w, correct = score_all(w, lab, fs)
                ok_true.append(float(rho[LABELS.index(lab)]))
                ok_min = min(ok_min, float(rho[LABELS.index(lab)]))
                ok_rho.append(max(rho))
                argmax_hit.append(1 if correct else 0)
                gates_hit.append(1 if gate(rho, THETA1, THETA2) == LABELS.index(lab) else 0)
        x = regions['stop']['x']
        y = transform(x, 'stop')
        for off in trials['stop']:
            w = y[off:off + WINDOW][:, picks]
            w = bandpass_win(w, fs)
            rho = [cca(w, reference(FREQS[l], fs, WINDOW)) for l in LABELS]
            rest_peaks.append(max(rho))
            rest_hit.append(1 if gate(rho, THETA1, THETA2) >= 0 else 0)
        return (float(np.mean(ok_true)), ok_min, float(max(rest_peaks)),
                sum(argmax_hit), len(argmax_hit),
                sum(gates_hit), len(gates_hit), sum(rest_hit), len(rest_hit))

    print('\n基线 (3s 对齐口径):')
    mean_t, min_t, rest_t, ah, ah_n, gh, gh_n, rh, rh_n = evaluate(lambda x, lab: x)
    print('  正确方向 mean=%.4f min=%.4f  静息最高=%.4f  argmax %d/%d 门控方向 %d/%d 静息误判 %d/%d'
          % (mean_t, min_t, rest_t, ah, ah_n, gh, gh_n, rh, rh_n))
    print('  绝对门区间 (%.4f, %.4f) 宽 +%.4f;  建议 theta1=%.3f'
          % (rest_t, min_t, min_t - rest_t, (rest_t + min_t) / 2))

    # 差值门区间: 方向段 top1-top2 最低 vs 静息段 top1-top2 最高
    dir_gaps, rest_gaps = [], []
    for lab in LABELS:
        x = regions[lab]['x']
        for off in trials[lab]:
            w = x[off:off + WINDOW][:, picks]
            w = bandpass_win(w, fs)
            rho = [cca(w, reference(FREQS[l], fs, WINDOW)) for l in LABELS]
            rho.sort(reverse=True)
            dir_gaps.append(rho[0] - rho[1])
    x = regions['stop']['x']
    for off in trials['stop']:
        w = x[off:off + WINDOW][:, picks]
        w = bandpass_win(w, fs)
        rho = [cca(w, reference(FREQS[l], fs, WINDOW)) for l in LABELS]
        rho.sort(reverse=True)
        rest_gaps.append(rho[0] - rho[1])
    print('  差值门: 方向 gap 最低=%.4f (均值 %.4f)  静息 gap 最高=%.4f  区间宽 +%.4f'
          % (min(dir_gaps), np.mean(dir_gaps), max(rest_gaps), min(dir_gaps) - max(rest_gaps)))
    print('  建议 theta2=%.3f' % ((max(rest_gaps) + min(dir_gaps)) / 2))

    print('\n=== 陷波模式 (置信度 -> alpha -> 门控判决) ===')
    print('| 置信度 | alpha | 判对 | rho_true | 静息/方向 | 门控方向 | 静息误判 |')
    for c in [100, 90, 80, 70, 60, 55, 50, 40, 30, 20, 10, 0]:
        a = conf_to_alpha(NOTCH_TABLE, c)
        mean_t, min_t, rest_t, ah, ah_n, gh, gh_n, rh, rh_n = evaluate(
            lambda x, lab, a=a: notch_region(x, fs, a))
        print('| %3d%% | %.4f | %d/%d | %.4f | %.4f/%.4f | %d/%d | %d/%d |'
              % (c, a, ah, ah_n, mean_t, rest_t, min_t, gh, gh_n, rh, rh_n))

    print('\n=== 加噪模式 (置信度 -> alpha -> 门控判决) ===')
    print('| 置信度 | alpha | 判对 | rho_true | 静息/方向 | 门控方向 | 静息误判 |')
    for c in [100, 90, 80, 70, 60, 50, 40, 30, 20, 0]:
        a = conf_to_alpha(NOISE_TABLE, c)
        rng = np.random.default_rng(SEED)

        def noisy_transform(x, lab, a=a):
            return x + a * regions[lab]['rms'] * rng.standard_normal(x.shape)
        mean_t, min_t, rest_t, ah, ah_n, gh, gh_n, rh, rh_n = evaluate(noisy_transform)
        print('| %3d%% | %.4f | %d/%d | %.4f | %.4f/%.4f | %d/%d | %d/%d |'
              % (c, a, ah, ah_n, mean_t, rest_t, min_t, gh, gh_n, rh, rh_n))


if __name__ == '__main__':
    main()
