# -*- coding: utf-8 -*-
"""打印 OpenBMI .mat 里的标签字段 (y_dec / y_class / class), 用于核对方向映射。

用法:
    py -3 inspect_labels.py <mat>
"""
import sys

import numpy as np

import extract_openbmi as E


def dump(text, value):
    print(text, value)


def main():
    path = sys.argv[1]
    data = E.open_dataset(path)
    buf, fields = data[0]

    y = np.ravel(E.numeric_field(buf, fields['y_dec'])).astype(int)
    dump('y_dec 前20:', list(y[:20]))
    dump('y_dec 计数:', {c: int((y == c).sum()) for c in (1, 2, 3, 4)})

    if 'y_class' in fields:
        mclass, dims, _p, _n = fields['y_class']
        dump('y_class dims:', dims)
        yc = E.cell_text_field(buf, fields['y_class'], int(np.prod(dims)))
        dump('y_class 前20:', yc[:20])
        pairs = {}
        for k in range(min(len(y), len(yc))):
            pairs.setdefault(int(y[k]), set()).add(yc[k])
        dump('y_dec -> y_class:', {k: sorted(v) for k, v in sorted(pairs.items())})

    if 'class' in fields:
        mclass, dims, _p, _n = fields['class']
        dump('class dims:', dims)
        cells = E.cell_text_field(buf, fields['class'], int(np.prod(dims)))
        dump('class cell (列主序顺序):', cells)

    if 'chan' in fields:
        names = E.cell_text_field(buf, fields['chan'], 62)
        dump('chan (62):', names if len(names) <= 62 else names[:62])

    for key in ['fs', 'time_interval', 'rest_interval', 'stim_interval']:
        if key in fields:
            dump('%s:' % key, list(np.ravel(E.numeric_field(buf, fields[key]))))


if __name__ == '__main__':
    main()
