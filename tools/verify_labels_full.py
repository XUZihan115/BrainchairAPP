# -*- coding: utf-8 -*-
"""对 OpenBMI 全部 100 个训练试次做 CCA 分类, 用混淆矩阵确定
"方向名 <-> 刺激频率" 的真实对应关系。

用法:
    py -3 verify_labels_full.py <mat>
"""
import sys

import numpy as np

import extract_openbmi as E

FREQ_CANDIDATES = [5.45, 6.67, 8.57, 12.00]
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2']


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
    x = x - x.mean(axis=0, keepdims=True)
    y = y - y.mean(axis=0, keepdims=True)
    qx, _ = np.linalg.qr(x)
    qy, _ = np.linalg.qr(y)
    return float(np.linalg.svd(qx.T @ qy, compute_uv=False)[0])


def main():
    path = sys.argv[1]
    data = E.open_dataset(path, max_blocks=1)
    buf, fields = data[0]

    fs = int(np.ravel(E.numeric_field(buf, fields['fs']))[0])
    smt = E.numeric_field(buf, fields['smt'])          # (4000, 100, 62), 列主序
    y_dec = np.ravel(E.numeric_field(buf, fields['y_dec'])).astype(int)
    y_class = E.cell_text_field(buf, fields['y_class'], 100)
    names = E.cell_text_field(buf, fields['chan'], 62)

    picks = [names.index(c) for c in PICK if c in names]
    print('采样率 %d Hz, 试次 %d, 通道 %d, 使用枕顶通道 %d 个' % (
        fs, smt.shape[1], smt.shape[2], len(picks)))
    print()

    n_trials = smt.shape[1]
    n_samples = smt.shape[0]

    # 预计算参考信号
    refs = {f: reference(f, fs, n_samples) for f in FREQ_CANDIDATES}

    # 混淆矩阵: 行=真实方向, 列=CCA 判出的频率
    dirs = ['up', 'left', 'right', 'down']
    conf = {d: {f: 0 for f in FREQ_CANDIDATES} for d in dirs}

    for j in range(n_trials):
        x = smt[:, j, :][:, picks].astype(np.float64)
        x = bandpass(x, fs, 4.0, 45.0)
        scores = {f: cca(x, refs[f]) for f in FREQ_CANDIDATES}
        best = max(scores, key=scores.get)
        conf[y_class[j]][best] += 1

    header = '%-8s %-7s %s' % ('真实方向', '试次数', '  '.join('%9s' % ('%.2fHz' % f) for f in FREQ_CANDIDATES))
    print(header)
    print('-' * len(header))
    for d in dirs:
        row = conf[d]
        total = sum(row.values())
        print('%-8s %-7d %s' % (
            d, total, '  '.join('%9d' % row[f] for f in FREQ_CANDIDATES)))

    print()
    print('每个方向的主导频率 (占比):')
    mapping = {}
    for d in dirs:
        row = conf[d]
        total = max(sum(row.values()), 1)
        best = max(row, key=row.get)
        mapping[d] = best
        print('  %-8s -> %.2f Hz   (%d/%d = %.1f%%)' % (
            d, best, row[best], total, 100.0 * row[best] / total))

    print()
    print('结论映射: ' + ', '.join('%s=%.2fHz' % (d, mapping[d]) for d in dirs))


if __name__ == '__main__':
    main()
