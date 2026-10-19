# -*- coding: utf-8 -*-
"""扫描「人为降质」强度对 SSVEP 置信度(CCA 相关值)的影响。

目的: A 端要加一个可调开关, 人为把 B 端算出来的置信度压下去, 用来标定 B 端
的「无指令」阈值、验证拒识能力。这个脚本先离线回答两个问题:

  1. 降质强度 -> 获胜相关值 rho 的映射曲线长什么样(是否单调、是否可控)
  2. 降质到什么程度, B 端会开始「误判成别的方向」(这是轮椅安全最怕的情况)

两种降质模型对比:
  noise  : 加高斯白噪声, x' = x + alpha * rms(x) * N(0,1)
           -> 信噪比整体下降, 对应"电极接触变差 / 环境干扰"
  notch  : 把 f_stim 及其 2、3 次谐波附近的谱能量按 (1-alpha) 压掉
           -> 精确削弱 SSVEP 成分, 背景脑电不变, 对应"注意力涣散 / 低响应者"

用法:
    py -3 sweep_quality.py <assets目录>
"""
import json
import os
import sys

import numpy as np

FREQS = {
    'forward': 12.00,
    'left': 8.57,
    'right': 6.67,
    'backward': 5.45,
}
LABELS = list(FREQS.keys())

PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2', 'PO7', 'PO8']

WINDOW = 2000          # 默认 2 秒窗口, 可用第 2 个命令行参数覆盖
REPEATS = 5            # 加噪是随机的, 多次平均才能得到稳定曲线
NOISE_ALPHAS = [0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0]
# 陷波 alpha 是「削弱比例」: 1.0 表示把谱线完全抹掉(信号里再无 SSVEP 成分),
# argmax 会退化成纯噪声决定, 结果不可用; 上限取 0.9, 再高没有物理意义。
NOTCH_ALPHAS = [0.0, 0.15, 0.3, 0.45, 0.6, 0.75, 0.9]
NOTCH_HALFWIDTH = 0.6  # Hz, 谐波陷波半宽


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
    s = np.linalg.svd(qx.T @ qy, compute_uv=False)
    return float(s[0])


def degrade_noise(x, alpha, rng):
    rms = float(np.sqrt(np.mean(x ** 2)))
    return x + alpha * rms * rng.standard_normal(x.shape)


def degrade_notch(x, fs, alpha, halfwidth=NOTCH_HALFWIDTH):
    """把每个候选频率的基频与 2、3 次谐波附近的谱能量按 (1-alpha) 压掉。

    注意: 这里对整段做循环 FFT 滤波, 与 A 端「段内硬回绕」的播放方式天然一致,
    边界不会产生跳变。
    """
    spec = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(x.shape[0], 1.0 / fs)
    mask = np.zeros_like(freqs, dtype=bool)
    for f0 in FREQS.values():
        for k in (1, 2, 3):
            mask |= np.abs(freqs - k * f0) <= halfwidth
    spec[mask] *= (1.0 - alpha)
    return np.fft.irfft(spec, n=x.shape[0], axis=0)


def score_all(x, fs):
    return [cca(x, reference(FREQS[l], fs, x.shape[0])) for l in LABELS]


def main():
    global WINDOW
    root = sys.argv[1]
    if len(sys.argv) > 2:
        WINDOW = int(sys.argv[2])
    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')

    nch = meta['channelCount']
    fs = meta['sampleRate']
    names = meta.get('channelNames') or []
    data = raw.reshape(-1, nch)
    picks = [names.index(c) for c in PICK if c in names]

    print('通道 %d 个, 采样率 %d Hz, 窗口 %d 点 (%.1f s)' % (
        len(picks), fs, WINDOW, WINDOW / float(fs)))

    trials = []      # (label, x)
    rest = []        # 静息窗口, 用于量噪声基线
    for seg in meta['segments']:
        off, cnt = seg['sampleOffset'], seg['sampleCount']
        if cnt < WINDOW:
            continue
        x = data[off:off + cnt, :][:, picks].astype(np.float64)
        x = bandpass(x, fs, 4.0, 45.0)
        for s in range(0, cnt - WINDOW + 1, WINDOW):
            win = x[s:s + WINDOW]
            if seg['label'] in FREQS:
                trials.append((seg['label'], win))
            else:
                rest.append(win)

    print('方向窗口 %d 个, 静息窗口 %d 个' % (len(trials), len(rest)))
    rms_all = float(np.sqrt(np.mean(np.stack([t[1] for t in trials]) ** 2)))
    print('信号 RMS = %.4f (这是加噪幅度的参考基准)\n' % rms_all)

    for method in ('noise', 'notch'):
        title = '加高斯白噪声' if method == 'noise' else '谐波陷波(削弱 SSVEP 成分)'
        print('=' * 84)
        print('降质方式: %s' % title)
        print('=' * 84)
        print('%-7s %-9s %-11s %-11s %-9s %s' % (
            'alpha', '判对率', '获胜 rho', '次优 rho', '静息 rho', '误判去向'))
        print('-' * 84)

        for alpha in (NOISE_ALPHAS if method == 'noise' else NOTCH_ALPHAS):
            rng = np.random.default_rng(20261018)
            hits = 0
            win_rhos = []
            sec_rhos = []
            misdirect = {}

            for label, win in trials:
                acc = []
                for _ in range(REPEATS if method == 'noise' else 1):
                    xd = (degrade_noise(win, alpha, rng) if method == 'noise'
                          else degrade_notch(win, fs, alpha))
                    acc.append(score_all(xd, fs))
                scores = np.mean(acc, axis=0)
                order = np.argsort(scores)[::-1]
                best, second = int(order[0]), int(order[1])
                win_rhos.append(scores[best])
                sec_rhos.append(scores[second])
                if LABELS[best] == label:
                    hits += 1
                else:
                    key = '%s->%s' % (label, LABELS[best])
                    misdirect[key] = misdirect.get(key, 0) + 1

            # 静息基线
            rest_rhos = []
            for win in rest:
                acc = []
                for _ in range(REPEATS if method == 'noise' else 1):
                    xd = (degrade_noise(win, alpha, rng) if method == 'noise'
                          else degrade_notch(win, fs, alpha))
                    acc.append(score_all(xd, fs))
                rest_rhos.append(float(np.max(np.mean(acc, axis=0))))
            rest_mean = float(np.mean(rest_rhos)) if rest_rhos else float('nan')

            mis = ', '.join('%s x%d' % (k, v) for k, v in sorted(misdirect.items()))
            print('%-7.2f %-9s %-11.4f %-11.4f %-9.4f %s' % (
                alpha,
                '%d/%d' % (hits, len(trials)),
                float(np.mean(win_rhos)),
                float(np.mean(sec_rhos)),
                rest_mean,
                mis if mis else '-',
            ))
        print()

    print('解读要点:')
    print('  * 获胜 rho 随 alpha 单调下降, 说明滑块可以线性映射到干扰强度。')
    print('  * 获胜 rho 逼近静息 rho 时, B 端就该判「无指令」(阈值要卡在两者之间)。')
    print('  * 看「误判去向」: 如果降质后大量判成别的方向, 说明 B 端必须靠阈值拒识,')
    print('    不能只取 argmax —— 否则轮椅会在注意力涣散时朝错误方向冲出去。')


if __name__ == '__main__':
    main()
