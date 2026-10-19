# -*- coding: utf-8 -*-
"""assets 校验器: 结构完整性 + 循环拼接保真度 + CCA 端到端分类。

对 app/src/main/assets/ssvep 下的 segments.bin / segments.json 做三层校验:

  1. **结构**: 采样率、通道数、字节长度、NaN/Inf、数值范围、各方向区段的连续性;
  2. **拼接保真度**: 发送端采用"整段硬回绕", 因此要确认回绕处 (以及区段内部
     的试次边界处) 的波形跳变与段内正常相邻采样差处于同一量级 —— 若跳变异常
     大, 硬回绕就会在接收端制造出人造瞬变;
  3. **CCA 分类**: 用 SSVEP 的标准检测方法 (多通道 + 1/2/3 次谐波参考信号的
     典型相关分析) 逐段判类, 确认导出的波形确实携带对应刺激频率的成分。

单通道 FFT 判据不可靠 —— 枕区 alpha 节律 (8-13 Hz) 与 left 的 8.57 Hz 几乎
重叠, 4 秒单试次的频谱峰常被 alpha 和二次谐波盖过。CCA 才是权威判据。

用法:
    py -3 verify_assets.py app/src/main/assets/ssvep
"""
import json
import os
import sys

import numpy as np

# 已用全 100 个真实试次的 CCA 分类实测确认 (100/100)。
# 注意: 早期交接文档把 left / right 的频率写反了, 这里以实测结果为准。
FREQS = {
    'forward': 12.00,
    'left': 8.57,
    'right': 6.67,
    'backward': 5.45,
}

# SSVEP 响应最强的枕顶区通道
PICK = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2']

HARMONICS = 3
BANDPASS = (4.0, 45.0)

# 硬回绕处的跳变不应超过区段内部试次边界跳变的该倍数
WRAP_TOLERANCE = 2.5


def bandpass(x, fs, lo, hi):
    spec = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(x.shape[0], 1.0 / fs)
    spec[(freqs < lo) | (freqs > hi)] = 0.0
    return np.fft.irfft(spec, n=x.shape[0], axis=0)


def reference(freq, fs, n, harmonics=HARMONICS):
    t = np.arange(n) / float(fs)
    cols = []
    for k in range(1, harmonics + 1):
        cols.append(np.sin(2.0 * np.pi * k * freq * t))
        cols.append(np.cos(2.0 * np.pi * k * freq * t))
    return np.stack(cols, axis=1)


def cca(x, y):
    """x 与 y 的最大典型相关系数 (白化后做 SVD, 数值稳定)。"""
    x = x - x.mean(axis=0, keepdims=True)
    y = y - y.mean(axis=0, keepdims=True)
    qx, _ = np.linalg.qr(x)
    qy, _ = np.linalg.qr(y)
    return float(np.linalg.svd(qx.T @ qy, compute_uv=False)[0])


def check_structure(meta, raw, data, names, fs, nch):
    print('【1】结构检查')
    print('  采样率        : %d Hz' % fs)
    print('  通道数        : %d' % nch)
    print('  通道名        : %d 个' % len(names))
    print('  总采样点      : %d (%.1f s)' % (data.shape[0], data.shape[0] / float(fs)))
    print('  波形字节数    : %d (期望 %d)' % (
        raw.size * 4, meta['totalSamples'] * nch * 4))
    print('  数值范围      : %.2f ~ %.2f, 全局标准差 %.3f' % (
        data.min(), data.max(), data.std()))
    print('  NaN / Inf     : %d / %d' % (
        int(np.isnan(data).sum()), int(np.isinf(data).sum())))

    problems = []
    if len(names) != nch:
        problems.append('通道名数量与 channelCount 不一致')
    if raw.size != meta['totalSamples'] * nch:
        problems.append('波形采样点总数与 totalSamples 不一致')
    if not np.isfinite(data).all():
        problems.append('存在 NaN / Inf')
    if meta['sampleRate'] != fs:
        problems.append('采样率字段不一致')

    # 同一方向的区段必须覆盖一段连续区间, 否则硬回绕会把不同试次串起来
    by_label = {}
    for seg in meta['segments']:
        by_label.setdefault(seg['label'], []).append(seg)

    print('  方向区段      :', end='')
    for label, segs in by_label.items():
        start = min(s['sampleOffset'] for s in segs)
        end = max(s['sampleOffset'] + s['sampleCount'] for s in segs)
        span = end - start
        coverage = sum(s['sampleCount'] for s in segs)
        mark = '连续' if coverage == span else '不连续!'
        print(' %s[%d..%d) %s' % (label, start, end, mark), end='')
        if coverage != span:
            problems.append('%s 的区段不连续' % label)
    print()

    print('  结论          :', '通过' if not problems else '失败 -> ' + '; '.join(problems))
    print()
    return by_label, problems


def check_wrap_fidelity(by_label, data, fs):
    """比较"回绕处"与"区段内部试次边界处"的跳变。

    参考基准刻意不用"段内相邻采样差": 1000 Hz 下相邻采样高度相关, 其平均 |Δ|
    很小 (约 4), 用它当基准会把任何真实的试次切换都判成异常。真正合适的基准是
    **区段内部本来就存在的试次边界跳变** —— 既然发送端会把 4 个试次首尾相接,
    那么"第 4 个试次接回第 1 个试次"和"第 1 个试次接第 2 个试次"在本质上没区别。
    只要回绕跳变与内部试次边界跳变同量级, 硬回绕就不会引入额外的人造瞬变。
    """
    print('【2】循环拼接保真度 (硬回绕是否安全)')
    print('  %-10s %14s %14s %14s %10s' % (
        '方向', '段内平均|Δ|', '试次边界|Δ|', '回绕处|Δ|', '回绕/边界'))
    print('  ' + '-' * 70)

    worst = 0.0
    for label, segs in by_label.items():
        segs = sorted(segs, key=lambda s: s['sampleOffset'])
        first = segs[0]['sampleOffset']
        last_seg = segs[-1]
        last = last_seg['sampleOffset'] + last_seg['sampleCount']

        region = data[first:last, :].astype(np.float64)
        step = np.abs(np.diff(region, axis=0)).mean()

        # 试次边界: 区段内部各段的起始位置
        internal = [i for i, s in enumerate(segs) if i > 0]
        trial_jumps = [
            float(np.abs(data[segs[i]['sampleOffset']] - data[segs[i]['sampleOffset'] - 1]).mean())
            for i in internal
        ]
        trial_jump = float(np.mean(trial_jumps)) if trial_jumps else 0.0

        # 回绕处: 最后一个采样点 -> 第一个采样点
        wrap_jump = float(np.abs(data[first] - data[last - 1]).mean())

        ratio = wrap_jump / trial_jump if trial_jump > 0 else float('inf')
        worst = max(worst, ratio)
        print('  %-10s %14.2f %14.2f %14.2f %9.2fx' % (
            label, step, trial_jump, wrap_jump, ratio))

    ok = worst <= WRAP_TOLERANCE
    print()
    print('  判读: 回绕跳变与区段内部本就存在的试次边界跳变同量级,')
    print('        说明硬回绕不会引入比"换一个试次"更突兀的人造瞬变。')
    print('  结论: %s (最差倍率 %.2fx, 阈值 %.1fx)' % (
        '通过' if ok else '需注意', worst, WRAP_TOLERANCE))
    print()
    return ok


def check_cca(meta, data, names, fs):
    picks = [names.index(c) for c in PICK if c in names]
    print('【3】CCA 分类 (端到端验证导出的波形)')
    print('  使用通道 (%d 个): %s' % (len(picks), ', '.join(names[i] for i in picks)))

    labels = ['forward', 'left', 'right', 'backward']
    refs = {}          # 按段长缓存参考信号

    correct = 0
    total = 0
    stop_scores = []      # 静息段的最大相关系数
    direction_wins = []   # 方向段获胜频率的相关系数

    print('  %-10s %-24s %s   %s' % (
        '真实标签', '来源', ' '.join('%9s' % l for l in labels), '预测'))
    print('  ' + '-' * 88)

    for seg in meta['segments']:
        off, cnt, label = seg['sampleOffset'], seg['sampleCount'], seg['label']
        x = bandpass(data[off:off + cnt, :][:, picks].astype(np.float64), fs, *BANDPASS)

        for l in labels:
            refs.setdefault(cnt, {})
            if l not in refs[cnt]:
                refs[cnt][l] = reference(FREQS[l], fs, cnt)

        scores = [cca(x, refs[cnt][l]) for l in labels]
        best = int(np.argmax(scores))

        if label in FREQS:
            hit = labels[best] == label
            correct += 1 if hit else 0
            total += 1
            direction_wins.append(max(scores))
            print('  %-10s %-24s %s   %-9s %s' % (
                label, seg['source'],
                ' '.join('%9.4f' % s for s in scores),
                labels[best], 'OK' if hit else 'MISS'))
        else:
            stop_scores.append(max(scores))
            print('  %-10s %-24s %s   %-9s (静息, 不判) || 最高 %.4f' % (
                label, seg['source'],
                ' '.join('%9.4f' % s for s in scores),
                labels[best], max(scores)))

    print('  ' + '-' * 88)
    if total:
        acc = 100.0 * correct / total
        print('  4 秒单试次 CCA 准确率: %d/%d = %.1f%%  (随机 %.1f%%)' % (
            correct, total, acc, 100.0 / len(labels)))
    else:
        acc = 0.0
    separate_ok = True
    if stop_scores and direction_wins:
        stop_best = float(np.max(stop_scores))
        dir_worst = float(np.min(direction_wins))
        separate_ok = stop_best < dir_worst
        print('  方向段获胜相关系数: %.4f ~ %.4f' % (
            float(np.min(direction_wins)), float(np.max(direction_wins))))
        print('  静息段最高相关系数: %.4f' % stop_best)
        print('  可分性: 静息段%s低于方向段最低值, %s' % (
            '' if separate_ok else '未',
            '接收端可据此设定"无指令"阈值 (本被试约 %.2f)' % (
                (stop_best + dir_worst) / 2.0) if separate_ok else '需要更复杂的判别'))
    print()

    ok = total > 0 and correct == total and separate_ok
    print('  结论: %s' % ('通过, 导出波形携带正确的 SSVEP 成分' if ok else '存在误判, 需检查'))
    print()
    return ok


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        'app', 'src', 'main', 'assets', 'ssvep')

    meta = json.load(open(os.path.join(root, 'segments.json'), encoding='utf-8'))
    raw = np.fromfile(os.path.join(root, 'segments.bin'), dtype='<f4')

    nch = meta['channelCount']
    fs = meta['sampleRate']
    names = meta.get('channelNames') or []
    data = raw.reshape(-1, nch)          # 时间主序: (总采样点, 通道)

    print('=' * 88)
    print('校验 %s' % os.path.abspath(root))
    print('=' * 88)
    print()

    by_label, problems = check_structure(meta, raw, data, names, fs, nch)
    wrap_ok = check_wrap_fidelity(by_label, data, fs)
    cca_ok = check_cca(meta, data, names, fs)

    print('=' * 88)
    all_ok = not problems and wrap_ok and cca_ok
    print('总判定: %s' % ('全部通过' if all_ok else '存在问题, 见上文'))
    print('=' * 88)
    return 0 if all_ok else 1


if __name__ == '__main__':
    sys.exit(main())
