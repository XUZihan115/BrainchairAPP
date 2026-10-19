# -*- coding: utf-8 -*-
"""用「试次间时间域平均 + 功率谱」直接测出每个方向的真实刺激频率。

与 verify_labels_full.py 的 CCA 方法完全独立:
  - 不构造任何参考信号 (sin/cos)
  - 不预设候选频率列表

关键点: SSVEP 是**锁相**于刺激的, 而 alpha 节律(约10Hz)/肌电/噪声**不锁相**。
所以先把某方向的 25 个试次在时间域上平均, alpha 与噪声相互抵消, 剩下的
诱发响应里就只剩刺激频率的清晰谱线。之后求谱, 峰值位置即真实刺激频率。

(注意: 若直接对单个试次的功率谱做平均, 结论会被 alpha 淹没而完全错误,
 这正是第一版脚本失败的原因。)

用法:
    py -3 verify_psd_peak.py <mat>
"""
import sys

import numpy as np

import extract_openbmi as E

PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2']
CANDIDATES = [5.45, 6.67, 8.57, 12.00]
SEARCH_LO, SEARCH_HI = 4.0, 16.0


def evoked_psd(smt, y_dec, picks, cls, fs):
    """试次间平均后再求功率谱 (只保留锁相成分)。"""
    trials = [j for j in range(smt.shape[1]) if y_dec[j] == cls]
    n = smt.shape[0]

    acc = np.zeros((n, len(picks)), dtype=np.float64)
    for j in trials:
        acc += smt[:, j, :][:, picks].astype(np.float64)
    acc /= float(len(trials))

    nfft = 1 << 16
    freqs = np.fft.rfftfreq(nfft, 1.0 / fs)
    spec = np.fft.rfft(acc * np.hanning(n)[:, None], n=nfft, axis=0)
    power = np.sum(np.abs(spec) ** 2, axis=1)
    return freqs, power, len(trials)


def local_peaks(freqs, power, lo, hi, topk=3):
    mask = (freqs >= lo) & (freqs <= hi)
    f, p = freqs[mask], power[mask]
    idx = np.where((p[1:-1] > p[:-2]) & (p[1:-1] >= p[2:]))[0] + 1
    if len(idx) == 0:
        return []
    order = idx[np.argsort(p[idx])[::-1]][:topk]
    return sorted([(float(f[i]), float(p[i])) for i in order], key=lambda kv: -kv[1])


def value_at(freqs, power, target):
    return float(power[int(np.argmin(np.abs(freqs - target)))])


def peak_snr(freqs, power, f, half=0.30, bg=2.0):
    """f 处的峰值 / 其邻域背景 (背景窗内剔除中心 ±half, 以免把自己算进背景)。

    用比值而不是绝对功率, 可以抵消 1/f 背景与 alpha 隆起的影响。
    """
    i0 = np.argmin(np.abs(freqs - f))
    lo = np.searchsorted(freqs, f - half)
    hi = np.searchsorted(freqs, f + half)
    peak = float(power[lo:hi].max()) if hi > lo else float(power[i0])

    blo = np.searchsorted(freqs, f - bg)
    bhi = np.searchsorted(freqs, f + bg)
    m = np.ones(bhi - blo, dtype=bool)
    m[max(0, lo - blo):max(0, hi - blo)] = False
    base = float(np.median(power[blo:bhi][m])) if m.any() else 1e-12
    return peak / max(base, 1e-12)


def find_fundamental(freqs, power, lo, hi, n_harm=4):
    """在 [lo,hi] 上搜索基频 f, 使 f,2f,3f,4f 处同时出现谱峰。

    逐点候选搜索, 不依赖任何预设频率; 打分用各次谐波的峰值信噪比的几何平均,
    这样「只有基频强」和「只有某次谐波强」的假基频都会被打低分。
    """
    best_f, best_score, best_snrs = float('nan'), -1.0, []
    f = lo
    while f <= hi:
        snrs = [peak_snr(freqs, power, f * k) for k in range(1, n_harm + 1)]
        gm = float(np.exp(np.mean(np.log(np.maximum(snrs, 1e-9)))))
        if gm > best_score:
            best_f, best_score, best_snrs = f, gm, snrs
        f += 0.005
    return best_f, best_score, best_snrs


def main():
    path = sys.argv[1]
    data = E.open_dataset(path, max_blocks=1)
    buf, fields = data[0]

    fs = int(np.ravel(E.numeric_field(buf, fields['fs']))[0])
    smt = E.numeric_field(buf, fields['smt'])
    y_dec = np.ravel(E.numeric_field(buf, fields['y_dec'])).astype(int)
    names = E.cell_text_field(buf, fields['chan'], 62)

    picks = [names.index(c) for c in PICK if c in names]

    print('=' * 78)
    print('试次间时间域平均 + 功率谱 (无参考信号, 无候选频率假设)')
    print('=' * 78)
    print('  通道 %d 个: %s' % (len(picks), ', '.join(PICK)))
    print('  4 s 窗口 + Hann 窗, 零填充 65536 点 (分辨率 %.4f Hz)' % (fs / 65536.0))
    print()

    dirs = {1: 'up', 2: 'left', 3: 'right', 4: 'down'}
    # 已确认的映射, 与 EegFrame.kt / docs/udp-protocol.md §7 保持一致。
    # (交接文档原表曾把 left/right 写反, 已于 2026-10-18 更正)
    doc = {'up': 12.00, 'left': 8.57, 'right': 6.67, 'down': 5.45}
    TOL = 0.25  # 搜索网格步长 0.005 Hz, 但取峰窗宽 ±0.3 Hz, 故留 0.25 Hz 容差
    result = {}

    for cls in (1, 2, 3, 4):
        freqs, power, n_trials = evoked_psd(smt, y_dec, picks, cls, fs)
        peaks = local_peaks(freqs, power, SEARCH_LO, SEARCH_HI, topk=3)
        f0, score, snrs = find_fundamental(freqs, power, SEARCH_LO, SEARCH_HI)
        result[dirs[cls]] = f0

        print('  %-6s (类 %d, %d 个试次平均)' % (dirs[cls], cls, n_trials))
        print('      >> 谐波一致性搜索得到的基频: %7.3f Hz   (得分 %.1f)'
              % (f0, score))
        print('         各次谐波峰值信噪比: ' +
              ', '.join('%df=%.2f' % (k + 1, s) for k, s in enumerate(snrs)))
        print('      4~16 Hz 内最强谱峰: ' +
              ', '.join('%.3f Hz' % p[0] for p in peaks))
        print('      校验: 2f=%.2f 3f=%.2f 4f=%.2f' % (2 * f0, 3 * f0, 4 * f0))
        vals = {c: value_at(freqs, power, c) for c in CANDIDATES}
        mx = max(vals.values())
        for c in CANDIDATES:
            mark = ' <== 最高' if vals[c] == mx else ''
            print('        %6.2f Hz  %5.1f%%  %s%s' % (
                c, 100.0 * vals[c] / mx, '#' * int(round(30.0 * vals[c] / mx)), mark))
        print('      已确认映射 %s = %.2f Hz  ->  %s'
              % (dirs[cls], doc[dirs[cls]],
                 '一致' if abs(f0 - doc[dirs[cls]]) <= TOL else
                 '不一致 (差 %.2f Hz)' % abs(f0 - doc[dirs[cls]])))
        print()

    print('=' * 78)
    print('汇总')
    print('=' * 78)
    print('  本方法(锁相谱+谐波一致性):  已确认映射:   结论')
    bad = []
    for d in ['up', 'left', 'right', 'down']:
        same = abs(result[d] - doc[d]) <= TOL
        if not same:
            bad.append(d)
        print('    %-6s -> %6.2f Hz          %6.2f Hz      %s'
              % (d, result[d], doc[d], '一致' if same else '不一致'))
    print()
    if bad:
        print('  ==> 不一致的方向: %s  (需检查数据集或脚本)' % ', '.join(bad))
    else:
        print('  ==> 全部一致, 与 EegFrame.kt / docs/udp-protocol.md 的映射相符')


if __name__ == '__main__':
    main()
