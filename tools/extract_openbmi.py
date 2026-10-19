# -*- coding: utf-8 -*-
"""
从 OpenBMI SSVEP 数据集 (GigaDB 100542) 的 sess01_subjXX_EEG_SSVEP.mat 中抽取真实试次,
导出为该 Android app 内置的 assets 资源:
    segments.bin   —— 全部段顺序拼接的 float32 (小端) 数据
    segments.json  —— 段索引 + 采样率 + 通道信息

用法:
    py -3 extract_openbmi.py info   <mat>
    py -3 extract_openbmi.py export <mat> <输出目录> [每类试次数] [静息段数]

实现说明:
    本机没有 scipy, 因此这里直接用 numpy + 自写 MATLAB v7 (level 5) 解析器。
    只读取需要字段的头部, 数据区用 np.frombuffer 零拷贝映射, 不逐个 unpack,
    因此 24.8M 个 double 的 smt 也能秒级处理。
"""
import io
import json
import os
import struct
import sys
import zlib

import numpy as np

NP_TYPE = {
    9: '<f8', 7: '<f4', 5: '<i4', 4: '<u2', 3: '<i2',
    2: '<u1', 6: '<i1', 13: '<u8', 8: '<u4', 12: '<i8',
}

MI_INT8 = 1
MI_UINT8 = 2
MI_INT16 = 3
MI_INT32 = 5
MI_UINT32 = 6
MI_MATRIX = 14
MI_COMPRESSED = 15

# 五指令映射: OpenBMI 类编号 -> 方向名 -> app 指令标签
CLASS_DIR = {1: 'up', 2: 'left', 3: 'right', 4: 'down'}
DIR_LABEL = {'up': 'forward', 'left': 'left', 'right': 'right', 'down': 'backward'}


def read_elem(buf, pos):
    """读取一个 v5 数据元素头, 返回 (typ, size, data_offset, next_pos)。

    SDE (小数据元素): tag 前 4 字节 u32, 若 u>>16 > 0, 则 size=高16位, type=低16位。
    """
    u = struct.unpack_from('<I', buf, pos)[0]
    if (u >> 16) > 0:
        size = u >> 16
        typ = u & 0xFFFF
        return typ, size, pos + 4, pos + 8
    typ = u
    size = struct.unpack_from('<I', buf, pos + 4)[0]
    data_start = pos + 8
    nxt = data_start + size
    nxt += (-size) % 8
    return typ, size, data_start, nxt


def parse_matrix_header(buf, pos):
    """pos 指向 miMATRIX 的 tag。返回 (mclass, dims, name, body_pos, next_pos)。"""
    typ, size, data_start, nxt = read_elem(buf, pos)
    if typ != MI_MATRIX:
        raise ValueError('期望 miMATRIX(14), 实际为 %d' % typ)

    p = data_start
    ft, fs, fds, fp = read_elem(buf, p)          # array flags
    mclass = buf[fds] & 0xFF
    p = fp

    dt, dsz, dds, dp = read_elem(buf, p)         # dimensions
    n = dsz // 4
    dims = tuple(struct.unpack_from('<%di' % n, buf, dds))
    p = dp

    nt, nsz, nds, np2 = read_elem(buf, p)        # array name
    name = buf[nds:nds + nsz].split(b'\x00')[0].decode('latin1')
    p = np2

    return mclass, dims, name, p, nxt


def parse_struct_fields(buf, pos):
    """pos 指向 1x1 struct 的 miMATRIX tag。返回 (字段名列表, 首个字段位置, 结束位置)。"""
    mclass, dims, name, p, nxt = parse_matrix_header(buf, pos)
    if mclass != 2:
        raise ValueError('期望 struct(mclass=2), 实际为 %d' % mclass)

    ft, fsz, fds, fp = read_elem(buf, p)         # field name length
    field_len = struct.unpack_from('<i', buf, fds)[0]
    p = fp

    nt, nsz, nds, np2 = read_elem(buf, p)        # field names
    nfields = nsz // field_len
    names = []
    for k in range(nfields):
        raw = buf[nds + k * field_len: nds + (k + 1) * field_len]
        names.append(raw.split(b'\x00')[0].decode('latin1'))
    p = np2

    return names, p, nxt


def index_fields(buf, pos):
    """只读每个字段的头, 返回 {name: (mclass, dims, data_pos, next_pos)}。"""
    names, p, nxt = parse_struct_fields(buf, pos)
    out = {}
    cur = p
    for nm in names:
        mclass, dims, fname, dpos, fnext = parse_matrix_header(buf, cur)
        out[nm or fname] = (mclass, dims, dpos, fnext)
        cur = fnext
    return out


def numeric_field(buf, entry):
    """把数值字段映射成 numpy 数组 (列主序)。"""
    mclass, dims, dpos, fnext = entry
    typ, size, ds, nxt = read_elem(buf, dpos)
    if typ not in NP_TYPE:
        raise ValueError('字段类型 %d 不是数值类型' % typ)
    np_dtype = np.dtype(NP_TYPE[typ])
    count = size // np_dtype.itemsize
    flat = np.frombuffer(buf, dtype=np_dtype, count=count, offset=ds)
    return flat.reshape(dims, order='F')


def text_field(buf, entry):
    """读取 char 字段。"""
    mclass, dims, dpos, fnext = entry
    typ, size, ds, nxt = read_elem(buf, dpos)
    raw = buf[ds:ds + size]
    if typ == 16:
        return raw.decode('utf-8', 'replace')
    if typ == 17:
        return raw.decode('utf-16-le', 'replace')
    return raw.decode('latin1', 'replace')


def cell_text_field(buf, entry, count):
    """读取 1xN cell of char, 返回字符串列表。"""
    mclass, dims, dpos, fnext = entry
    if mclass != 1:
        return None
    out = []
    p = dpos
    for _ in range(count):
        mclass2, dims2, name2, dp2, np2 = parse_matrix_header(buf, p)
        typ, size, ds, nxt = read_elem(buf, dp2)
        raw = buf[ds:ds + size]
        if typ == 16:
            out.append(raw.decode('utf-8', 'replace'))
        elif typ == 17:
            out.append(raw.decode('utf-16-le', 'replace'))
        else:
            out.append(raw.decode('latin1', 'replace'))
        p = np2
    return out


def load_blocks(path, max_blocks=None):
    """解开 MATLAB v7 文件里的压缩块, 返回解压后缓冲区列表。

    max_blocks 为 None 时解析全部块; 若只需第一个块 (train), 传 1 可省一半时间与内存。
    """
    blocks = []
    with open(path, 'rb') as f:
        f.seek(128)
        while True:
            tag = f.read(8)
            if len(tag) < 8:
                break
            u = struct.unpack('<I', tag[:4])[0]
            if (u >> 16) > 0:
                f.read(4)
                continue
            typ = u
            size = struct.unpack('<I', tag[4:])[0]
            if typ == MI_COMPRESSED:
                comp = f.read(size)
                dec = zlib.decompressobj()
                parts = []
                i = 0
                while i < len(comp):
                    chunk = comp[i:i + 2_000_000]
                    i += len(chunk)
                    out = dec.decompress(chunk)
                    while dec.unconsumed_tail:
                        out += dec.decompress(dec.unconsumed_tail)
                    parts.append(out)
                parts.append(dec.flush())
                blocks.append(b''.join(parts))
                # 注意: 本文件压缩块之间没有 padding, 不要额外对齐
                if max_blocks is not None and len(blocks) >= max_blocks:
                    break
            else:
                f.seek(size + ((-size) % 8), 1)
    return blocks


def open_dataset(path, max_blocks=None):
    """返回 [(解压缓冲区, 字段索引), ...], 供后续取数。"""
    blocks = load_blocks(path, max_blocks)
    result = []
    for buf in blocks:
        fields = index_fields(buf, 0)
        result.append((buf, fields))
    return result


def describe(path):
    data = open_dataset(path)
    for idx, (buf, fields) in enumerate(data):
        mclass, dims, name, _p, _n = parse_matrix_header(buf, 0)
        print('===== 块 %d: %s dims=%s =====' % (idx, name, dims))
        for key in ['fs', 'y_dec', 'time_interval', 'rest_interval']:
            if key in fields:
                arr = numeric_field(buf, fields[key])
                print('  %-14s dims=%s -> %s' % (key, arr.shape, np.ravel(arr)[:10]))
        for key in ['smt', 'x', 'pre_rest', 'post_rest', 'EMG']:
            if key in fields:
                print('  %-14s dims=%s dtype=%s' % (key, fields[key][1], fields[key][0]))
        if 'chan' in fields:
            names = cell_text_field(buf, fields['chan'], 62)
            print('  通道前8个: %s' % (names[:8] if names else '解析失败'))
            print('  通道数: %s' % (len(names) if names else '?'))
        if 'y_dec' in fields:
            y = np.ravel(numeric_field(buf, fields['y_dec'])).astype(int)
            print('  每类试次数: %s' % {c: int((y == c).sum()) for c in (1, 2, 3, 4)})
        print()


def export(path, out_dir, per_class=4, rest_segments=2, which='train'):
    data = open_dataset(path)
    buf, fields = data[0] if which == 'train' else data[-1]

    fs = int(np.ravel(numeric_field(buf, fields['fs']))[0])
    smt = numeric_field(buf, fields['smt'])            # (samples, trials, channels)
    y_dec = np.ravel(numeric_field(buf, fields['y_dec'])).astype(int)
    samples, n_trials, n_ch = smt.shape

    ch_names = None
    if 'chan' in fields:
        ch_names = cell_text_field(buf, fields['chan'], n_ch)

    segments = []
    chunks = []

    def add_segment(array_2d, label, source):
        """array_2d: (samples, channels)"""
        arr = np.ascontiguousarray(array_2d, dtype='<f4')
        segments.append({
            'label': label,
            'source': source,
            'sampleOffset': int(sum(c.shape[0] for c in chunks)),
            'sampleCount': int(arr.shape[0]),
            'channelCount': int(arr.shape[1]),
        })
        chunks.append(arr)

    # --- 四类 SSVEP 真实试次 ---
    for cls in (1, 2, 3, 4):
        trials = [j for j in range(n_trials) if y_dec[j] == cls]
        picked = trials[:per_class]
        direction = CLASS_DIR[cls]
        for j in picked:
            add_segment(smt[:, j, :], DIR_LABEL[direction], '%s:class%d:trial%d' % (which, cls, j))
        print('类 %d (%s): 抽取试次 %s' % (cls, direction, picked))

    # --- 静息段 (停) ---
    if 'pre_rest' in fields:
        rest = numeric_field(buf, fields['pre_rest'])   # (60000, channels)
        step = samples
        for k in range(rest_segments):
            start = k * step * 5
            if start + samples > rest.shape[0]:
                break
            add_segment(rest[start:start + samples, :], 'stop', 'pre_rest:seg%d' % k)
        print('静息段: 抽取 %d 段 (每段 %d 点)' % (rest_segments, samples))

    total = sum(c.shape[0] for c in chunks)
    all_data = np.concatenate(chunks, axis=0)

    os.makedirs(out_dir, exist_ok=True)
    bin_path = os.path.join(out_dir, 'segments.bin')
    json_path = os.path.join(out_dir, 'segments.json')

    with open(bin_path, 'wb') as f:
        f.write(all_data.astype('<f4').tobytes())

    meta = {
        'source': os.path.basename(path),
        'sampleRate': fs,
        'channelCount': int(n_ch),
        'channelNames': ch_names,
        'totalSamples': int(total),
        'segments': segments,
    }
    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    print()
    print('写出: %s (%.1f MB)' % (bin_path, os.path.getsize(bin_path) / 1048576.0))
    print('写出: %s' % json_path)
    print('段数: %d, 单段: %d 点 x %d 通道' % (len(segments), samples, n_ch))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    mode = sys.argv[1]
    mat = sys.argv[2]
    if mode == 'info':
        describe(mat)
    elif mode == 'export':
        out_dir = sys.argv[3]
        per_class = int(sys.argv[4]) if len(sys.argv) > 4 else 4
        rest_segments = int(sys.argv[5]) if len(sys.argv) > 5 else 2
        export(mat, out_dir, per_class, rest_segments)
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
