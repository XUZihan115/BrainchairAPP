# -*- coding: utf-8 -*-
"""A 端「降低 B 端置信度」功能的**权威参考实现**与标定曲线。

语义必须与 Kotlin 侧 SignalDegrade.kt 完全一致, 否则滑块刻度就是假的:

  notch : band = IFFT(FFT(x) * targetMask)    # 只保留 4 个刺激频率的 1/2/3 次谐波谱线
          x'   = x - alpha * band             # 等价于把那些谱线乘 (1-alpha)
  noise : x'   = x + alpha * rms_bp * N(0,1)
          rms_bp = 该段 4-45 Hz 带限 RMS

两者都在**整段**上做**循环** FFT(补齐到 2 的幂), 与 A 端「段内硬回绕」的播放
方式天然一致, 边界不会跳变。降质作用在原始信号上; 之后 B 端对**连续流**做带通
再切片 —— 这里同样对整段做循环带通, 避免「对 2 秒窗单独带通」产生的边缘振铃
把相关值虚高 10 个百分点以上。

用法:
    py -3 degrade_reference.py <assets目录> [--dump-test-vector]
"""
import json
import os
import sys

import numpy as np

FREQS = {'forward': 12.00, 'left': 8.57, 'right': 6.67, 'backward': 5.45}
LABELS = list(FREQS.keys())
# 枕顶区通道。注意本数据集是 62 通道布局, 里面**没有 PO7/PO8**
# (见 udp-protocol.md §3 的通道表), 所以下面用 `if c in names` 过滤,
# 真正参与计算的是 **9 个通道**: Pz/O1/Oz/O2/P1/P2/POz/PO3/PO4
# (下标 24/28/29/30/41/42/43/60/61)。
# 保留 PO7/PO8 是为了将来换到有这两个位置的 64 通道数据集时不用改代码。
# 下文所有 rho / 阈值数字都是这 9 个通道的结果。
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2', 'PO7', 'PO8']

HARMONICS = (1, 2, 3)
HALF_WIDTH_HZ = 0.6
BAND_LO, BAND_HI = 4.0, 45.0
WINDOW = 2000
RHO_FULL = None         # 由 alpha=0 的实测值自动填入(见 main), 保证 c=100% 恰好等于 alpha=0
RHO_DEEP = 0.30
NOISE_GRID = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.25, 1.5,
              2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 8.0]
NOTCH_GRID = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9]


def np2(n):
    return 1 << (n - 1).bit_length()


def circular_filter(x, fs, spec_fn):
    """循环(补齐到 2 的幂)频域处理, 返回与 x 同长的结果。"""
    n = x.shape[0]
    N = np2(n)
    pad = np.empty((N, x.shape[1]), dtype=np.float64)
    pad[:n] = x
    if N > n:
        pad[n:] = x[:N - n]           # 段内硬回绕
    spec = np.fft.rfft(pad, axis=0)
    out = np.fft.irfft(spec_fn(spec, np.fft.rfftfreq(N, 1.0 / fs)), n=N, axis=0)
    return out[:n]


def target_mask(f, halfwidth=HALF_WIDTH_HZ):
    m = np.zeros_like(f, dtype=bool)
    for f0 in FREQS.values():
        for k in HARMONICS:
            m |= np.abs(f - k * f0) <= halfwidth
    return m


def analysis_band(f):
    return (f >= BAND_LO) & (f <= BAND_HI)


# --------------------------------------------------- A 端 prepare: band + rms

def prepare_region(x, fs):
    """返回 (band 平面, 4-45Hz RMS)。band = 目标频段成分, x' = x - alpha*band。"""
    n = x.shape[0]
    N = np2(n)
    pad = np.empty((N, x.shape[1]), dtype=np.float64)
    pad[:n] = x
    if N > n:
        pad[n:] = x[:N - n]
    spec = np.fft.rfft(pad, axis=0)
    f = np.fft.rfftfreq(N, 1.0 / fs)

    keep = np.where(target_mask(f)[:, None], spec, 0.0)
    band = np.fft.irfft(keep, n=N, axis=0)[:n]

    # 4-45Hz 带限 RMS: Parseval 直接从谱上算, 与 Kotlin 侧保持同一算法
    b = analysis_band(f)
    energy = np.sum(np.abs(spec[b]) ** 2, axis=0)          # 每个通道
    interior = b & (f > 0) & (f < fs / 2.0)
    energy2 = np.sum(np.abs(spec[interior]) ** 2, axis=0)
    rms_par = float(np.sqrt(2.0 * np.sum(energy2) / (float(N) ** 2 * x.shape[1])))
    # 对照: 直接时域算一遍
    bp = circular_filter(x, fs, lambda s, ff: np.where(analysis_band(ff)[:, None], s, 0.0))
    rms_dir = float(np.sqrt(np.mean(bp ** 2)))
    return band, rms_par, rms_dir


# --------------------------------------------------------------- 打分

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


def score_windows(band_limited, fs, picks):
    rows = []
    for s in range(0, band_limited.shape[0] - WINDOW + 1, WINDOW):
        win = band_limited[s:s + WINDOW][:, picks]
        rows.append([cca(win, reference(FREQS[l], fs, WINDOW)) for l in LABELS])
    return np.array(rows)


# --------------------------------------------------------------- 主流程

def build(root, verbose=True):
    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')
    nch, fs = int(meta['channelCount']), int(meta['sampleRate'])
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
        band, rms_par, rms_dir = prepare_region(x, fs)
        regions[lab] = dict(x=x, band=band, rms=rms_par, cnt=end - off)
        if verbose:
            print('%-9s cnt=%5d  RMS(Parseval)=%7.3f  RMS(时域)=%7.3f  差=%.2f%%' % (
                lab, end - off, rms_par, rms_dir,
                100.0 * abs(rms_par - rms_dir) / rms_dir))
    if verbose:
        print()
    return regions, fs, picks


def bandlimit(x, fs):
    return circular_filter(x, fs, lambda s, f: np.where(analysis_band(f)[:, None], s, 0.0))


def degrade(r, mode, alpha, rng):
    x = r['x']
    if mode == 'noise':
        return x + alpha * r['rms'] * rng.standard_normal(x.shape)
    return x - alpha * r['band']


def evaluate(regions, fs, picks, mode, alpha, rng):
    """对全部段降质 -> 循环带通 -> 2 秒窗打分。

    返回的 rho_true / rho_second 只统计**方向段**(正确方向的得分 / 次优得分),
    rho_rest 只统计**静息段**的最高分 —— 这三者才是阈值标定要看的量。
    """
    true_scores, second_scores, gaps_true, gaps_rest = [], [], [], []
    hits, total = 0, 0
    misdirect = {}

    for lab in LABELS:
        sc = score_windows(bandlimit(degrade(regions[lab], mode, alpha, rng), fs), fs, picks)
        for j in range(sc.shape[0]):
            order = np.argsort(sc[j])[::-1]
            best, second = int(order[0]), int(order[1])
            true_scores.append(sc[j, LABELS.index(lab)])
            second_scores.append(sc[j, second])
            gaps_true.append(sc[j, best] - sc[j, second])
            total += 1
            if LABELS[best] == lab:
                hits += 1
            else:
                key = '%s->%s' % (lab, LABELS[best])
                misdirect[key] = misdirect.get(key, 0) + 1

    rest_scores = []
    if 'stop' in regions:
        sc = score_windows(bandlimit(degrade(regions['stop'], mode, alpha, rng), fs), fs, picks)
        for j in range(sc.shape[0]):
            order = np.argsort(sc[j])[::-1]
            rest_scores.append(float(sc[j, order[0]]))
            gaps_rest.append(float(sc[j, order[0]] - sc[j, order[1]]))

    return dict(
        rho_true=float(np.mean(true_scores)),
        rho_second=float(np.mean(second_scores)),
        rho_rest=float(np.mean(rest_scores)) if rest_scores else float('nan'),
        rest_max=float(np.max(rest_scores)) if rest_scores else float('nan'),
        # 绝对阈值可行性: 取 min(方向正确得分) 与 max(静息最高分) 的间隔
        abs_lo=float(np.max(rest_scores)) if rest_scores else float('nan'),
        abs_hi=float(np.min(true_scores)),
        # 差值门控可行性: 方向窗的 top1-top2 间隙 vs 静息窗的 top1-top2 间隙
        gap_dir_min=float(np.min(gaps_true)),
        gap_rest_max=float(np.max(gaps_rest)) if gaps_rest else float('nan'),
        acc='%d/%d' % (hits, total),
        misdirect=misdirect,
    )


def main():
    global RHO_FULL, WINDOW
    argv = [a for a in sys.argv[1:] if not a.startswith('--')]
    root = argv[0]
    if len(argv) > 1 and argv[1].isdigit():
        WINDOW = int(argv[1])
    regions, fs, picks = build(root)
    print('窗口 %d 点 (%.1f s)' % (WINDOW, WINDOW / float(fs)))

    base = evaluate(regions, fs, picks, 'noise', 0.0, np.random.default_rng(20261018))
    RHO_FULL = base['rho_true']
    print('基线(不降质): rho_true=%.4f  rho_2nd=%.4f  静息最高=%.4f  方向最低=%.4f'
          % (RHO_FULL, base['rho_second'], base['abs_lo'], base['abs_hi']))
    print('  -> 绝对阈值窗口 = (%.4f, %.4f)  宽 %.4f'
          % (base['abs_lo'], base['abs_hi'], base['abs_hi'] - base['abs_lo']))
    print('  -> 差值门控: 静息 gap 最大 %.4f  vs  方向 gap 最小 %.4f'
          % (base['gap_rest_max'], base['gap_dir_min']))
    print('  (RHO_FULL 取 %.4f, 保证滑块 100%% 恰好等于 alpha=0)\n' % RHO_FULL)

    print('=' * 118)
    print('alpha -> rho 曲线')
    print('  rho_true/rho_2nd = 方向段「正确方向」/「次优方向」得分均值;  rho_rest = 静息段最高分均值')
    print('  绝对门 = [静息最高分, 方向最低分]: 阈值必须落在这个区间内才既不漏判也不误判')
    print('  差值门 = [静息 top1-top2 最大值, 方向 top1-top2 最小值]: 同理')
    print('=' * 118)
    curves = {}
    for mode, grid in (('noise', NOISE_GRID), ('notch', NOTCH_GRID)):
        print('\n[%s]' % ('加噪(信号干扰)' if mode == 'noise' else '陷波(SSVEP 削弱)'))
        print('%-7s %-8s %-9s %-9s | %-19s | %-19s | %s' % (
            'alpha', '判对率', 'rho_true', 'rho_2nd', '绝对门 静息/方向', '差值门 静息/方向', '误判去向'))
        print('-' * 118)
        pts = []
        for alpha in grid:
            rng = np.random.default_rng(20261018)
            m = evaluate(regions, fs, picks, mode, alpha, rng)
            pts.append((alpha, m['rho_true']))
            mis = ', '.join('%s x%d' % (k, v) for k, v in sorted(m['misdirect'].items()))
            print('%-7.2f %-8s %-9.4f %-9.4f | %.4f / %.4f    | %.4f / %.4f    | %s' % (
                alpha, m['acc'], m['rho_true'], m['rho_second'],
                m['abs_lo'], m['abs_hi'], m['gap_rest_max'], m['gap_dir_min'],
                mis if mis else '-'))
        curves[mode] = pts

    print('\n' + '=' * 78)
    print('置信度 -> alpha 反查 (线性目标 rho_true = %.3f + (%.3f-%.3f)*c/100)' % (
        RHO_DEEP, RHO_FULL, RHO_DEEP))
    print('=' * 78)
    table = {}
    for mode in ('noise', 'notch'):
        pts = curves[mode]
        a = np.array([p[0] for p in pts])
        r = np.array([p[1] for p in pts])
        order = np.argsort(r)
        xp, fp = np.unique(r[order], return_index=True)
        fp = a[order][fp]
        print('\n[%s]' % ('加噪' if mode == 'noise' else '陷波'))
        print('%-9s %-11s %-11s' % ('置信度', '目标 rho', 'alpha'))
        print('-' * 34)
        tbl = []
        for c in range(0, 101, 10):
            target = RHO_DEEP + (RHO_FULL - RHO_DEEP) * c / 100.0
            alpha = float(np.interp(target, xp, fp))
            tbl.append(alpha)
            print('%-9d %-11.3f %-11.3f' % (c, target, alpha))
        table[mode] = tbl

    print('\nKotlin 常量 (索引 = 置信度/10, 0..10):')
    for mode in ('noise', 'notch'):
        print('  %s_ALPHA = floatArrayOf(%s)' % (
            mode.upper(), ', '.join('%.4ff' % v for v in table[mode])))

    if '--dump-test-vector' in sys.argv:
        n, fs_t = 128, 1000.0
        t = np.arange(n) / fs_t
        x = (np.sin(2 * np.pi * 12.00 * t) + 0.5 * np.sin(2 * np.pi * 6.67 * t)
             + 0.25 * np.cos(2 * np.pi * 5.45 * t) + 0.1 * np.sin(2 * np.pi * 30.0 * t))
        alpha = 0.5
        x2 = x.reshape(-1, 1)
        band, _, _ = prepare_region(x2, fs_t)
        y = (x2 - alpha * band).ravel()
        print('\n// ---- 单测参考向量 (fs=1000, n=128, alpha=0.5, 1 通道) ----')
        print('INPUT = floatArrayOf(%s)' % ', '.join('%.9gf' % v for v in x))
        print('EXPECT = floatArrayOf(%s)' % ', '.join('%.9gf' % v for v in y))


if __name__ == '__main__':
    main()
