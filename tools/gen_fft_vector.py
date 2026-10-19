# -*- coding: utf-8 -*-
"""生成 Fft.kt / SignalDegrade.kt 的单测对拍向量。

刻意选 fs=64 Hz, n=64 点: 频率分辨率 1 Hz, 采样点数小到可以直接写进 Kotlin 源码。
这样 4 个候选频率的 1/2/3 次谐波会命中十多个不同的频点, 能真正压测陷波掩码;
而 fs=1000/n=128 那种组合分辨率只有 7.8 Hz, 掩码里只会命中 1 个 bin, 测了等于没测。

用法: py -3 gen_fft_vector.py
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from degrade_reference import prepare_region          # noqa: E402

FS = 64.0
N = 64
ALPHA = 0.5


def main():
    t = np.arange(N) / FS
    x = (np.sin(2 * np.pi * 12.00 * t)
         + 0.6 * np.sin(2 * np.pi * 5.45 * t)
         + 0.4 * np.cos(2 * np.pi * 8.57 * t)
         + 0.3 * np.sin(2 * np.pi * 30.0 * t)
         + 0.2 * np.sin(2 * np.pi * 1.00 * t))

    band, rms_par, rms_dir = prepare_region(x.reshape(-1, 1), FS)
    y = x - ALPHA * band.ravel()

    f = np.fft.rfftfreq(N, 1.0 / FS)
    print('// fs=%.0f Hz, n=%d, alpha=%.2f, 单通道' % (FS, N, ALPHA))
    print('// 4-45Hz 带限 RMS: Parseval=%.6f 时域=%.6f' % (rms_par, rms_dir))
    print('// 1 Hz(带外) 与 30 Hz(带内非目标) 应几乎不受影响, 12/8.57/5.45 Hz 应被削弱一半')
    for label, arr in (('INPUT', x), ('EXPECT', y)):
        body = ', '.join(('%.8gf' % v) for v in arr)
        print('private val %s = floatArrayOf(%s)' % (label, body))
    xin = np.fft.rfft(x)
    yout = np.fft.rfft(y)
    print('// 谱线对照 (Hz: |X| -> |Y|):')
    for hz in (1.0, 5.45, 8.57, 12.0, 30.0):
        k = int(round(hz * N / FS))
        print('//   %6.2f : %.4f -> %.4f' % (hz, abs(xin[k]), abs(yout[k])))


if __name__ == '__main__':
    main()
