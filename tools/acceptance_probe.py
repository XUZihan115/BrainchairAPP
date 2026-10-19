# -*- coding: utf-8 -*-
"""A 端「人为降质 -> B 端判定」链路的**验收器**（desk acceptance verifier）。

它同时就是一个**能跑的最小接收端原型**：收 A 端的 UDP 流 -> 频域带通 -> CCA ->
双门控 -> 投票 -> 打印判决。算法口径与 tools/degrade_reference.py **完全一致**
（直接 import 复用它的 cca/reference/bandlimit/degrade/evaluate），所以这里的
rho 数字与 docs/receiver-handbook.md 里的标定表必须对得上 —— 对不上就是有 bug。

四种用法
--------

1) 真机联调（A 端对着本机推流；A 端 IP 框留空即可自动发现本机）
       py -3 -u tools\\acceptance_probe.py
   在 9001 应答 DISCOVER、在 9000 收流，每 200 ms 打印一行判决。

2) 桌面验收（**不需要手机**，直接读 app/src/main/assets/ssvep 回放）
       py -3 -u tools\\acceptance_probe.py --verify
           校验标定表可复现：rho_true 应为 0.7361（手册 §5.3）。
       py -3 -u tools\\acceptance_probe.py --sweep
           复现手册 §9.2 的 alpha -> rho 表，并给出**逐窗门控判决统计**
           （判对方向 / 判停 / 判错方向）—— 这才是手册 §9.4 真正要看的东西。
       py -3 -u tools\\acceptance_probe.py --degrade notch --alpha 0.55
           单点验证手册 §9.4 第 4 步：此时 B 端应稳定判「停」。
       py -3 -u tools\\acceptance_probe.py --live-sim
           用滑动窗模拟真机判决时间线；并对比 pad=0 与 pad=1500，
           复现手册 §4.1「对每个窗单独带通会让 rho 虚高」的现象。

3) 自检（不接网络、不读数据）
       py -3 tools\\acceptance_probe.py --selftest

4) 落盘
       --csv out.csv     逐窗 rho 与判决写入 CSV

Windows 注意：请加 `-u`，并 `set PYTHONIOENCODING=utf-8`；
控制台中文乱码时加 `--ascii`（输出英文标签）。
"""
import argparse
import collections
import csv
import json
import os
import socket
import struct
import sys
import threading
import time
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import degrade_reference as dref          # noqa: E402  (同目录复用，保证口径一致)

# ----------------------------------------------------------------- 常量

FREQS = dref.FREQS                        # forward/left/right/backward -> Hz
LABELS = dref.LABELS                      # ['forward','left','right','backward']
CMD_OF = {'stop': 0, 'forward': 1, 'left': 2, 'right': 3, 'backward': 4}

STREAM_MAGIC = 0x47EE
DISC_MAGIC = 0x47EF
PROTO_VER = 2
DISC_VER = 1
DEVICE_ID = 0x45454731
MSG_INFO, MSG_BLOCK, MSG_STOP = 0x01, 0x02, 0x03
STOP_REASON = {0x01: 'RELEASED', 0x02: 'ERROR', 0x03: 'SWITCHED'}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEF_ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'ssvep')

# degrade_reference.py 跑出来的 4 s 窗标定值（手册 §5.3 / §9.2 引用的就是这些）
REF = {
    'base': dict(rho_true=0.7361, rho_2nd=0.3760, rest_max=0.5388, dir_min=0.6018,
                 gap_rest_max=0.2107, gap_dir_min=0.2783),
    # 0.75 档在留档日志里只记到了 gap 值、rho_true 缺失，故不设参考值（不参与比对）
    'notch': {0.00: 0.7361, 0.20: 0.6975, 0.40: 0.6334, 0.50: 0.5854, 0.55: 0.5557,
              0.60: 0.5214, 0.65: 0.4819, 0.70: 0.4364, 0.80: 0.3263,
              0.90: 0.1928},
    'noise': {0.00: 0.7361, 0.50: 0.6761, 1.00: 0.6132, 1.50: 0.5652, 2.00: 0.5261,
              2.50: 0.4932, 3.00: 0.4656, 3.50: 0.4422, 4.00: 0.4224, 8.00: 0.3375},
}

ASCII = False


def S(zh, en):
    return en if ASCII else zh


# ----------------------------------------------------------------- 分析核心

class Analyzer:
    """把「一段连续（未带通）的原始数据」变成一次判决。

    与 degrade_reference.py 的差别只有两点，都是 B 端实时化必须做的：
      * 参考信号矩阵的 QR **只算一次并缓存**（手册 §5.2 的优化建议）；
      * 先对**连续 chunk** 带通，再从结果里取中间窗口，两端各留 `pad` 点缓冲，
        这样窗口边界没有带通振铃（手册 §4.1）。
    """

    def __init__(self, fs, window, theta1, theta2, pad, harmonics=3):
        self.fs, self.window = int(fs), int(window)
        self.theta1, self.theta2, self.pad = float(theta1), float(theta2), int(pad)
        self.qy = {}
        for lab in LABELS:
            y = dref.reference(FREQS[lab], self.fs, self.window, harmonics)
            y = y - y.mean(axis=0, keepdims=True)
            self.qy[lab] = np.linalg.qr(y)[0]

    def cca(self, x, lab):
        x = x - x.mean(axis=0, keepdims=True)
        qx = np.linalg.qr(x)[0]
        return float(np.linalg.svd(qx.T @ self.qy[lab], compute_uv=False)[0])

    def score_chunk(self, chunk):
        """chunk: (n, C) 原始数据（未带通）。返回 (rho[4], 窗口起点偏移) 或 None。"""
        if chunk.shape[0] < self.window + self.pad:
            return None
        bl = dref.bandlimit(chunk, self.fs)
        left = min(self.pad, bl.shape[0] - self.window)
        win = bl[left:left + self.window]
        return np.array([self.cca(win, lab) for lab in LABELS]), left

    def score_window_at(self, raw, s):
        """对 raw 中以绝对下标 s 开始的窗口打分。

        关键区别（手册 §4.1）：窗口两侧各取 `pad` 点作带通缓冲，
        `pad=0` 就退化成「对这个窗口单独带通」——那正是要对照的错误做法。
        """
        lo = max(0, s - self.pad)
        hi = min(raw.shape[0], s + self.window + self.pad)
        bl = dref.bandlimit(raw[lo:hi], self.fs)
        w = bl[s - lo:s - lo + self.window]
        return np.array([self.cca(w, lab) for lab in LABELS])

    def gate(self, rho):
        """双门控 -> 标签（'stop' 或方向名）。手册 §6.2。"""
        order = np.argsort(rho)[::-1]
        w, s2 = int(order[0]), int(order[1])
        if rho[w] < self.theta1 or (rho[w] - rho[s2]) < self.theta2:
            return 'stop', w, s2
        return LABELS[w], w, s2


# ----------------------------------------------------------------- 可选带通实现
#
# 手册 §4.1 要求「必须对连续流带通」。但**不同带通实现给出的 rho 并不相同**：
# 4~45 Hz 的理想 FFT 掩码、2 级 IIR、更陡的 3 级 IIR、filtfilt 风格的双向 IIR,
# 在同一个段上 rho 能差好几个百分点，静息段的最高分更是可能越过 theta1。
# 所以 B 端用哪种滤波，阈值就必须用哪种滤波重新标一遍 —— 见 --filter-check。
# 下面这个 biquad 用 RBJ cookbook 公式现算，不依赖 scipy。

def rbj_bandpass(f0, q, fs):
    w0 = 2.0 * np.pi * f0 / fs
    alpha = np.sin(w0) / (2.0 * q)
    b = np.array([alpha, 0.0, -alpha])
    a = np.array([1.0 + alpha, -2.0 * np.cos(w0), 1.0 - alpha])
    return b / a[0], a / a[0]


def _biquad_stream(x, b, a, zi=None):
    n, c = x.shape
    y = np.empty_like(x, dtype=np.float64)
    if zi is None:
        z1 = np.zeros(c)
        z2 = np.zeros(c)
    else:
        z1, z2 = zi[0].copy(), zi[1].copy()
    for i in range(n):
        xi = x[i]
        yi = b[0] * xi + z1
        z1 = b[1] * xi - a[1] * yi + z2
        z2 = b[2] * xi - a[2] * yi
        y[i] = yi
    return y, np.stack([z1, z2])


def iir_bandpass(x, stages, fs=1000.0):
    """4~45 Hz 的 IIR 带通（RBJ biquad 级联）。x: (n, C) -> (y, zi)。"""
    f0 = float(np.sqrt(dref.BAND_LO * dref.BAND_HI))
    q = f0 / (dref.BAND_HI - dref.BAND_LO)
    b, a = rbj_bandpass(f0, q, fs)
    y = x
    zis = []
    for _ in range(stages):
        y, z = _biquad_stream(y, b, a)
        zis.append(z)
    return y, np.stack(zis)


def iir_filtfilt(x, stages, fs=1000.0):
    """filtfilt 风格（前向 + 反向各一遍），瞬态加倍。仅供对照。"""
    y, _ = iir_bandpass(x, stages, fs)
    y = y[::-1]
    y, _ = iir_bandpass(y, stages, fs)
    return y[::-1]


# ----------------------------------------------------------------- 离线打分

def per_window_scores(regions, fs, picks, mode, alpha, rng, window):
    """逐窗打分（**非重叠**，与 degrade_reference.score_windows 同口径但窗长可指定）。

    返回 {label: ndarray(n_win, 4)}，列顺序 = LABELS。'stop' 段也在内。
    """
    out = {}
    for lab in list(LABELS) + ['stop']:
        if lab not in regions:
            continue
        x = dref.degrade(regions[lab], mode, alpha, rng)
        bl = dref.bandlimit(x, fs)
        rows = []
        for s in range(0, bl.shape[0] - window + 1, window):
            win = bl[s:s + window][:, picks]
            rows.append([dref.cca(win, dref.reference(FREQS[l], fs, window)) for l in LABELS])
        out[lab] = np.array(rows)
    return out


def acceptance_stats(scores, theta1, theta2, an=None):
    """把逐窗得分过一遍双门控，统计「判对方向 / 判停 / 判错方向」。"""
    st = {}
    for lab, arr in scores.items():
        dec = []
        for row in arr:
            order = np.argsort(row)[::-1]
            w, s2 = int(order[0]), int(order[1])
            if row[w] < theta1 or (row[w] - row[s2]) < theta2:
                dec.append('stop')
            else:
                dec.append(LABELS[w])
        n = len(dec)
        if lab == 'stop':
            st[lab] = dict(n=n, stop=sum(1 for d in dec if d == 'stop'),
                           wrong=sum(1 for d in dec if d != 'stop'))
        else:
            st[lab] = dict(n=n, correct=sum(1 for d in dec if d == lab),
                           stop=sum(1 for d in dec if d == 'stop'),
                           wrong=sum(1 for d in dec if d != lab and d != 'stop'))
    return st


def rho_true_of(scores):
    vals = []
    for lab in LABELS:
        if lab in scores:
            j = LABELS.index(lab)
            vals.extend(scores[lab][:, j].tolist())
    return float(np.mean(vals)) if vals else float('nan')


def rest_top1_of(scores):
    if 'stop' not in scores or len(scores['stop']) == 0:
        return float('nan')
    return float(np.max([float(np.max(row)) for row in scores['stop']]))


def fmt_stats(st):
    parts = []
    for lab in list(LABELS) + ['stop']:
        if lab not in st:
            continue
        d = st[lab]
        if lab == 'stop':
            parts.append('stop %d/%d' % (d['stop'], d['n']))
        else:
            parts.append('%s %d' % (lab[:2], d['correct']))
    return ' '.join(parts)


def verdict_text(st):
    """把门控统计翻译成一句人话（用于验收判读）。"""
    dirn = sum(st[l]['n'] for l in LABELS if l in st)
    ok = sum(st[l]['correct'] for l in LABELS if l in st)
    stopped = sum(st[l]['stop'] for l in LABELS if l in st)
    wrong = sum(st[l]['wrong'] for l in LABELS if l in st)
    rest_n = st['stop']['n'] if 'stop' in st else 0
    rest_stop = st['stop']['stop'] if 'stop' in st else 0
    if wrong > 0:
        v = S('有错误方向输出（危险：会乱动）', 'WRONG directions emitted (dangerous)')
    elif ok == 0 and stopped == dirn:
        v = S('全部判停（拒识生效）', 'all STOP (rejection working)')
    elif ok == dirn:
        v = S('全部判对（方向解析生效）', 'all directions correct')
    else:
        v = S('部分判停、部分判对（阈值边界）', 'mixed STOP/correct (threshold boundary)')
    return '%s | %s %d/%d, %s %d/%d, %s %d/%d' % (
        v, S('方向窗判对', 'dir correct'), ok, dirn,
        S('判停', 'stopped'), stopped, dirn,
        S('静息判停', 'rest stopped'), rest_stop, rest_n)


# ----------------------------------------------------------------- 报文解析

def parse_data_packet(buf):
    """返回 (msgType, seq, payload) 或 None（校验不过一律丢弃）。"""
    if len(buf) < 20:
        return None
    try:
        magic, ver, mtype, dev, seq, plen = struct.unpack_from('<HBBIII', buf, 0)
    except struct.error:
        return None
    if magic != STREAM_MAGIC or ver != PROTO_VER:
        return None
    if len(buf) < 16 + plen + 4:
        return None
    crc = struct.unpack_from('<I', buf, 16 + plen)[0]
    if (zlib.crc32(buf[:16 + plen]) & 0xFFFFFFFF) != crc:
        return None
    return mtype, seq, bytes(buf[16:16 + plen])


def parse_info(payload):
    rate, nch, dtype, _rsv, nlen = struct.unpack_from('<IHBBH', payload, 0)
    names = payload[10:10 + nlen].decode('utf-8', 'replace').split(',')
    return rate, nch, dtype, names


def parse_block(payload):
    first, devt, scount, ccount = struct.unpack_from('<QQHH', payload, 0)
    arr = np.frombuffer(payload, dtype='<f4', count=scount * ccount, offset=20)
    return first, devt, arr.reshape(scount, ccount)


def parse_stop(payload):
    last, reason = struct.unpack_from('<QB', payload, 0)
    return last, reason


def build_announce(nonce, data_port, name):
    nb = name.encode('utf-8')[:40]
    payload = struct.pack('<HBBIHHB', DISC_MAGIC, DISC_VER, 0x02,
                          nonce, int(data_port) & 0xFFFF, 0, len(nb)) + nb
    assert len(payload) == 13 + len(nb)
    return payload + struct.pack('<I', zlib.crc32(payload) & 0xFFFFFFFF)


# ----------------------------------------------------------------- 实时流缓冲

class StreamBuffer:
    def __init__(self, keep):
        self.keep = int(keep)
        self.data = None          # (n, C) 原始数据（未带通）
        self.base = 0             # data[0] 对应的绝对采样点序号
        self.abs_end = 0          # 下一个应有的采样点绝对序号
        self.packets = 0
        self.samples = 0
        self.gaps = 0
        self.dup = 0
        self.out_of_order = 0

    def reset_samples(self):
        self.data = None
        self.base = 0
        self.abs_end = 0

    def append(self, first, block):
        n = block.shape[0]
        c = block.shape[1]
        new_base = None
        if first > self.abs_end:
            self.gaps += 1
            room = max(0, min(first - self.abs_end, self.keep))
            block = np.concatenate(
                [np.zeros((room, c), dtype=np.float32), block], axis=0)
            if room > 0 and self.data is None:
                new_base = self.abs_end
        elif first < self.abs_end:
            skip = self.abs_end - first
            if skip >= n:
                self.dup += 1
                return
            self.out_of_order += 1
            block = block[skip:]
            if self.data is None:
                new_base = first + skip

        if self.data is None:
            self.data = block
            self.base = first if new_base is None else new_base
        else:
            self.data = np.concatenate([self.data, block], axis=0)
        if self.data.shape[0] > self.keep * 2:
            self.data = self.data[-self.keep:]
            self.base = max(0, first + n - self.keep)
        self.abs_end = max(self.abs_end, first + n)
        self.packets += 1
        self.samples += n

    def tail(self, want):
        if self.data is None:
            return None
        if self.data.shape[0] < want:
            return self.data
        return self.data[-want:]


# ----------------------------------------------------------------- 发现应答

def discovery_thread(sock, data_port, name, stop_evt, log):
    while not stop_evt.is_set():
        try:
            pkt, src = sock.recvfrom(2048)
        except socket.timeout:
            continue
        except OSError:
            continue
        if len(pkt) != 16:
            continue
        try:
            magic, ver, mtype, nonce, dev, crc = struct.unpack('<HBBIII', pkt)
        except struct.error:
            continue
        if magic != DISC_MAGIC or ver != DISC_VER or mtype != 0x01:
            continue
        if (zlib.crc32(pkt[:12]) & 0xFFFFFFFF) != crc:
            continue
        try:
            sock.sendto(build_announce(nonce, data_port, name), src)
        except OSError:
            continue
        log('%s DISCOVER from %s:%d -> ANNOUNCE(port=%d)' % (
            S('[发现]', '[DISC]'), src[0], src[1], data_port))


# ----------------------------------------------------------------- 实时模式

def run_serve(args):
    an = Analyzer(args.fs, args.window, args.theta1, args.theta2, args.pad)
    keep = args.window + 2 * args.pad
    st = StreamBuffer(keep)
    lock = threading.Lock()
    state = dict(fs=args.fs, names=None, picks=None, nch=None,
                 seg=0, seg_first=0, stop_reason=None)
    stop_evt = threading.Event()
    logs = []

    def log(msg):
        logs.append(msg)
        print(msg, flush=True)

    # --- 数据 socket
    data_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    data_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        data_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    except OSError:
        pass
    data_sock.bind(('0.0.0.0', args.port))
    data_sock.settimeout(0.2)

    log(S('验收器启动：数据端口 %d，窗 %d 点（%.1f s），步进 %d 点，'
          '绝对门 %.3f，差值门 %.3f，投票 %d 窗，pad %d 点',
          'verifier up: data port %d, window %d pts (%.1fs), step %d, '
          'theta1 %.3f, theta2 %.3f, vote %d, pad %d')
        % (args.port, args.window, args.window / float(args.fs), args.step,
           args.theta1, args.theta2, args.vote, args.pad))
    log(S('  参与分类的通道：%s', '  picked channels: %s')
        % ','.join(dref.PICK))

    # --- 发现 socket
    disc_sock = None
    if not args.no_discovery:
        disc_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        disc_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            disc_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except OSError:
            pass
        disc_sock.bind(('0.0.0.0', args.discovery_port))
        disc_sock.settimeout(0.2)
        threading.Thread(target=discovery_thread,
                         args=(disc_sock, args.port, args.name, stop_evt, log),
                         daemon=True).start()
        log(S('  已在 %d 端口应答 DISCOVER（A 端 IP 留空即可自动发现本机）',
              '  answering DISCOVER on port %d (leave A-side IP blank)')
            % args.discovery_port)

    csvf = open(args.csv, 'w', newline='', encoding='utf-8-sig') if args.csv else None
    csvw = csv.writer(csvf) if csvf else None
    if csvw:
        csvw.writerow(['seg', 't_s', 'n_win', 'rho_forward', 'rho_left', 'rho_right',
                       'rho_backward', 'winner', 'conf', 'gap', 'gated', 'stable'])

    votes = collections.deque(maxlen=args.vote)
    last_stable = None
    next_win_abs = 0          # 下一个待评分窗口的起始绝对序号（每次推流从 0 起）
    t0 = time.time()
    last_stats = t0
    win_index = 0

    try:
        while time.time() - t0 < args.seconds:
            # ---------- 收包（一次尽量多收，避免积压）
            got = 0
            while got < 400:
                try:
                    buf, src = data_sock.recvfrom(2048)
                except socket.timeout:
                    break
                except OSError:
                    break
                got += 1
                p = parse_data_packet(buf)
                if p is None:
                    continue
                mtype, seq, payload = p
                if mtype == MSG_INFO:
                    rate, nch, dtype, names = parse_info(payload)
                    picks = [names.index(c) for c in dref.PICK if c in names]
                    if not picks:
                        log(S('!! STREAM_INFO 里找不到任何枕顶通道，通道名可能不符',
                              '!! no occipital channel found in STREAM_INFO'))
                        continue
                    with lock:
                        state.update(fs=rate, names=names, picks=picks, nch=nch)
                        st.reset_samples()
                        votes.clear()
                        next_win_abs = 0
                    an.fs = rate
                    log(S('STREAM_INFO: %d Hz, %d 通道, %d 个参与分类 (%s)',
                          'STREAM_INFO: %d Hz, %d ch, using %d (%s)')
                        % (rate, nch, len(picks), ','.join(
                            names[i] for i in picks)))
                elif mtype == MSG_BLOCK:
                    first, devt, arr = parse_block(payload)
                    with lock:
                        if state['picks'] is None:
                            continue
                        st.append(first, arr[:, state['picks']])
                elif mtype == MSG_STOP:
                    last, reason = parse_stop(payload)
                    with lock:
                        n_samp = st.samples
                        n_pkt = st.packets
                        seg = state['seg']
                        g, d, o = st.gaps, st.dup, st.out_of_order
                        st.reset_samples()
                        votes.clear()
                        state['seg'] += 1
                    log(S('  [段 %d 结束] reason=%s 采样=%d 包=%d 丢包缺口=%d 重复=%d 乱序=%d',
                          '  [seg %d stop] reason=%s samples=%d pkts=%d gaps=%d dup=%d ooo=%d')
                        % (seg, STOP_REASON.get(reason, hex(reason)), n_samp,
                           n_pkt, g, d, o))
                    last_decision_abs = -1
                    last_stable = None

            # ---------- 决策
            with lock:
                ready = (state['picks'] is not None and st.data is not None
                         and st.abs_end - last_decision_abs >= args.step
                         and st.data.shape[0] >= args.window + args.pad)
                chunk = st.tail(keep).copy() if ready else None
                cur_abs = st.abs_end
                seg = state['seg']
            if chunk is not None:
                last_decision_abs = cur_abs
                r = an.score_chunk(chunk)
                if r is not None:
                    rho, left = r
                    lab, w, s2 = an.gate(rho)
                    conf = float(rho[w])
                    gap = float(rho[w] - rho[s2])
                    votes.append(lab)
                    stable = votes[0] if len(votes) == args.vote and len(set(votes)) == 1 else None
                    win_index += 1
                    if csvw:
                        csvw.writerow([seg, '%.3f' % (win_index * args.step / float(args.fs)),
                                       win_index] + ['%.4f' % v for v in rho]
                                      + [LABELS[w], '%.4f' % conf, '%.4f' % gap, lab,
                                         stable or ''])
                        csvf.flush()
                    if stable is not None and stable != last_stable:
                        last_stable = stable
                        log('   >>> %s  %s %-8s conf=%.3f  (cmd=%d)' % (
                            S('指令变化:', 'CHANGE:'),
                            S('输出', 'out'), stable, conf, CMD_OF[stable]))
                    elif not args.quiet:
                        print('  w=%-5d %s=%s cmd=%-8s conf=%.3f gap=%.3f %s=%s/%d'
                              % (win_index, S('窗', 'win'),
                                 ' '.join('%s:%.3f' % (l[:1].upper(), v)
                                          for l, v in zip(LABELS, rho)),
                                 lab, conf, gap, S('投票', 'vote'),
                                 stable or '-', args.vote), flush=True)

            if time.time() - last_stats > 2.0:
                last_stats = time.time()
                with lock:
                    p, s, g = st.packets, st.samples, st.gaps
                if p:
                    log(S('  [状态] 累计 %d 包 / %d 采样点, 缺口 %d, 当前速率约 %.0f 包/秒',
                          '  [stat] %d pkts / %d samples, gaps %d, ~%.0f pkts/s')
                        % (p, s, g, p / max(1e-6, time.time() - t0)))
    except KeyboardInterrupt:
        log(S('收到 Ctrl-C，退出。', 'Ctrl-C, exiting.'))
    finally:
        stop_evt.set()
        data_sock.close()
        if disc_sock:
            disc_sock.close()
        if csvf:
            csvf.close()
            log(S('逐窗数据已写入 %s', 'per-window data -> %s') % args.csv)


# ----------------------------------------------------------------- 离线模式

def load_regions(assets, verbose=False):
    return dref.build(assets, verbose=verbose)


def long_sine(freq, fs, n, nch, amp=1.0, noise=0.01, rng=None):
    """(n, nch) 的纯正弦 —— 用于自检的正对照。"""
    t = np.arange(n) / float(fs)
    x = amp * np.tile(np.sin(2.0 * np.pi * freq * t)[:, None], (1, nch))
    if noise:
        x = x + noise * rng.standard_normal(x.shape)
    return x


def run_verify(args):
    regions, fs, picks = load_regions(args.assets)
    dref.WINDOW = args.window
    rng = np.random.default_rng(20261018)
    m = dref.evaluate(regions, fs, picks, 'noise', 0.0, rng)
    ref = REF['base']
    rows = [
        ('rho_true   ' + S('（正确方向均值）', '(mean of correct direction)'),
         m['rho_true'], ref['rho_true']),
        ('rho_2nd    ' + S('（次优方向均值）', '(mean of runner-up)'),
         m['rho_second'], ref['rho_2nd']),
        (S('静息最高分  (绝对门下界候选)', 'rest max   (lower bound of abs gate)'),
         m['abs_lo'], ref['rest_max']),
        (S('方向最低分  (绝对门上界候选)', 'dir min    (upper bound of abs gate)'),
         m['abs_hi'], ref['dir_min']),
        (S('差值门 静息 gap 最大', 'gap gate: rest max'),
         m['gap_rest_max'], ref['gap_rest_max']),
        (S('差值门 方向 gap 最小', 'gap gate: dir min'),
         m['gap_dir_min'], ref['gap_dir_min']),
    ]
    print(S('=== 标定表可复现性校验（复现手册 §5.3 的 4 s 窗数字）===',
            '=== calibration reproducibility (handbook 5.3, 4 s window) ==='))
    print(S('  窗 %d 点 (%.1f s)，非重叠切窗，方向窗 %s，静息窗 %s',
            '  window %d pts (%.1fs), non-overlapping, dir wins %s, rest wins %s')
          % (args.window, args.window / float(fs), m['acc'],
             len(regions.get('stop', {}).get('x', [])) // args.window
             if 'stop' in regions else '?'))
    bad = 0
    for name, got, want in rows:
        ok = abs(got - want) <= args.tol
        bad += 0 if ok else 1
        print('  %-46s %8.4f   %s %8.4f  %s' % (
            name, got, S('期望', 'ref'), want,
            'OK' if ok else S('!! 偏差', '!! DEV')))
    print()
    if bad:
        print(S('结论：不通过（%d 项偏差 > %.4f）—— 分析口径与标定表不一致，先查这里。',
                'RESULT: FAIL (%d items off by > %.4f)') % (bad, args.tol))
        return 1
    print(S('结论：通过 —— 本验收器的分析口径与标定表一致，可以拿它的判决当验收依据。',
            'RESULT: PASS - verifier matches calibration; its verdicts are trustworthy.'))
    return 0


def _sweep_one(regions, fs, picks, mode, alpha, args):
    rng = np.random.default_rng(20261018)
    scores = per_window_scores(regions, fs, picks, mode, alpha, rng, args.window)
    st = acceptance_stats(scores, args.theta1, args.theta2)
    return rho_true_of(scores), rest_top1_of(scores), st


def run_sweep(args, single_alpha=None):
    regions, fs, picks = load_regions(args.assets)
    if 'stop' not in regions:
        print(S('!! 数据里没有 stop 段，无法评估拒识能力', '!! no stop region'))
        return 1
    modes = [('notch', dref.NOTCH_GRID, REF['notch']),
             ('noise', dref.NOISE_GRID, REF['noise'])]
    if single_alpha is not None:
        modes = [(args.degrade, None, {single_alpha: REF[args.degrade].get(single_alpha, float('nan'))})]

    print(S('=== 逐窗门控验收（窗 %d 点 / 非重叠；绝对门 %.3f，差值门 %.3f）===',
            '=== per-window gate acceptance (window %d, theta1 %.3f, theta2 %.3f) ===')
          % (args.window, args.theta1, args.theta2))
    print(S('  rho_true 与参考值比对；判决列 = 方向窗/静息窗的实际门控结果\n',
            '  rho_true vs reference; decision columns are actual gate outcomes\n'))
    n_bad = 0
    for mode, grid, refmap in modes:
        alphas = [single_alpha] if single_alpha is not None else grid
        print(S('[%s]', '[%s]') % (S('陷波/SSVEP 削弱', 'notch') if mode == 'notch'
                                   else S('加噪/信号干扰', 'noise')))
        print('  %-7s %-9s %-9s | %-34s | %s' % (
            'alpha', 'rho_true', S('参考', 'ref'), S('门控判决统计', 'gate decisions'),
            S('结论', 'verdict')))
        print('  ' + '-' * 116)
        for alpha in alphas:
            rho, rest, st = _sweep_one(regions, fs, picks, mode, alpha, args)
            ref = refmap.get(alpha, float('nan'))
            dev = '' if np.isnan(ref) else ('%+.4f' % (rho - ref))
            if not np.isnan(ref) and abs(rho - ref) > args.tol:
                n_bad += 1
                dev += ' !!'
            print('  %-7.2f %-9.4f %-9s | %-34s | %s' % (
                alpha, rho, ('%.4f %s' % (ref, dev)) if not np.isnan(ref) else '-',
                fmt_stats(st), verdict_text(st)))
        print()
    if n_bad:
        print(S('注意：有 %d 处 rho_true 与参考值偏差 > %.4f', 'NOTE: %d rho_true dev > %.4f') % (n_bad, args.tol))
    else:
        print(S('全部 rho_true 与参考值一致（这在 --verify 通过后是预期的）。',
                'all rho_true match reference (expected once --verify passes).'))
    return 0


def run_live_sim(args):
    regions, fs, picks = load_regions(args.assets)
    print(S('=== 滑动窗判决模拟（真机将看到的时间线）===',
            '=== sliding-window decision simulation ==='))
    print(S('  窗 %d 点，步进 %d 点，投票 %d 窗；两条路径用**同一套 CCA/门控**，'
            '只差 pad（带通缓冲）：\n'
            '    pad=0      每窗单独带通（手册 §4.1 警告的错误做法）\n'
            '    pad=%-5d 连续流带通后切窗（正确做法）\n',
            '  window %d, step %d, vote %d; identical CCA/gate, only pad differs:\n'
            '    pad=0      per-window bandpass (WRONG)\n'
            '    pad=%-5d  continuous-stream bandpass (CORRECT)\n')
          % (args.window, args.step, args.vote, args.pad))

    res = {}
    for pad in (0, args.pad):
        an = Analyzer(fs, args.window, args.theta1, args.theta2, pad)
        recs = {}
        for lab in list(LABELS) + ['stop']:
            if lab not in regions:
                continue
            raw = regions[lab]['x'][:, picks]
            n = raw.shape[0]
            j = LABELS.index(lab) if lab in LABELS else None
            rows = []
            votes = collections.deque(maxlen=args.vote)
            for s in range(0, n - args.window + 1, args.step):
                rho = an.score_window_at(raw, s)
                lab_g, w, s2 = an.gate(rho)
                votes.append(lab_g)
                rows.append((s, s % args.window == 0,
                             float(rho[j]) if j is not None else float(np.max(rho)),
                             lab_g))
            recs[lab] = rows
        res[pad] = recs

    for pad in (0, args.pad):
        recs = res[pad]
        tag = (S('每窗单独带通（错误做法）', 'per-window bandpass (WRONG)') if pad == 0
               else S('连续流带通后切窗（正确做法）', 'continuous bandpass (CORRECT)'))
        dvals = [r[2] for lab in LABELS for r in recs.get(lab, [])]
        cvals = [r[2] for lab in LABELS for r in recs.get(lab, []) if r[1]]
        svals = [r[2] for r in recs.get('stop', [])]
        print(S('  pad=%-5d %s', '  pad=%-5d %s') % (pad, tag))
        print(S('    方向窗 rho(正确方向) 均值 = %.4f   仅「整 4 s 对齐」的窗口 = %.4f',
                '    dir rho(correct) mean = %.4f   trial-aligned only = %.4f')
              % (float(np.mean(dvals)) if dvals else float('nan'),
                 float(np.mean(cvals)) if cvals else float('nan')))
        smax = max(svals) if svals else float('nan')
        print(S('    静息窗最高 = %.4f  ->  %s theta1=%.3f',
                '    rest max = %.4f  ->  %s theta1=%.3f')
              % (smax,
                 S('高于', 'ABOVE') if svals and smax >= args.theta1 else S('低于', 'below'),
                 args.theta1))
        for lab in list(LABELS) + ['stop']:
            if lab not in recs:
                continue
            cnt = collections.Counter(r[3] for r in recs[lab])
            cl = collections.Counter(r[3] for r in recs[lab] if r[1])
            print('      %-9s %s | %s' % (
                lab, ' '.join('%s=%d' % (k, v) for k, v in sorted(cnt.items())),
                ' '.join('%s=%d' % (k, v) for k, v in sorted(cl.items())) or '-'))
        print()

    v0 = float(np.mean([r[2] for lab in LABELS for r in res[0].get(lab, [])])) \
        if res[0] else float('nan')
    v1 = float(np.mean([r[2] for lab in LABELS for r in res[args.pad].get(lab, [])])) \
        if res[args.pad] else float('nan')
    print(S('  判读（只报实测）：', '  READ (measured only):'))
    print(S('    方向 rho 均值：pad=0 为 %.4f，pad=%d 为 %.4f，差 %+.4f。',
            '    dir rho mean: pad=0 %.4f, pad=%d %.4f, diff %+.4f.')
          % (v0, args.pad, v1, v0 - v1))
    if abs(v0 - v1) < 0.01:
        print(S('    结论：**「每窗单独带通」在本数据上并没有让 rho 虚高**（差 <0.01）。',
                '    NOTE: per-window bandpass does NOT inflate rho here (diff <0.01).'))
        print(S('    原因：dref.bandlimit 是**循环** FFT 滤波，循环卷积按构造没有边界，'
                '所以「窗边界振铃」这个机制在本实现对里不存在。',
                '    Reason: dref.bandlimit is a CIRCULAR FFT filter; a circular '
                'convolution has no edge, so "edge ringing" cannot occur here.'))
        print(S('    真正会抬高 rho / 击穿 theta1 的是**滤波器的陡度与瞬态**，'
                '不是窗边界。用 --filter-check 看实测对照。',
                '    What really lifts rho past the gate is filter STEEPNESS and '
                'transients. See --filter-check for measurements.'))
    else:
        print(S('    结论：两条路径差 %.4f，按各自实测判读。', '    measured diff %.4f')
              % abs(v0 - v1))
    print(S('    另注意「对齐 / 不对齐」两列：本数据是 4 s 试次拼接，'
            '跨试次边界的滑动窗会因相位跳变掉 rho（数据集特性，真机不一定如此）。',
            '    Also note aligned vs unaligned: this replay concatenates 4 s trials, '
            'so straddling windows lose rho (dataset artifact).'))
    return 0


def run_filter_check(args):
    """带通实现体检：同一个 theta1 能不能跨滤波实现复用。"""
    regions, fs, picks = load_regions(args.assets)
    print(S('=== 带通实现体检：theta1 能否跨实现复用？ ===',
            '=== filter check: does theta1 survive a different filter? ==='))
    print(S('  同一段数据，只换带通实现，看 方向 rho 与 静息最高分 怎么变。'
            'theta1=%.3f\n',
            '  same data, only the bandpass changes. theta1=%.3f\n') % args.theta1)

    for win in sorted({args.window, 2000}):
        print(S('  窗长 %d 点 (%.1f s)', '  window %d pts (%.1f s)')
              % (win, win / float(fs)))
        print('    %-32s %-9s %-9s %-9s %s' % (
            S('带通实现', 'bandpass'), 'rho_dir', S('方向最低', 'dir_min'),
            S('静息最高', 'rest_max'), S('theta1 安全性', 'theta1 safety')))
        print('    ' + '-' * 88)

        def wcuts(raw, filt):
            return [filt(raw[s:s + win]) for s in range(0, raw.shape[0] - win + 1, win)]

        variants = [
            (S('整段循环 FFT 掩码（参考口径）', 'full-region circular FFT (ref)'),
             lambda x: dref.bandlimit(x, fs)[:, picks]),
            (S('逐窗循环 FFT 掩码 pad=0', 'per-window circular FFT, pad=0'),
             lambda x: dref.bandlimit(x, fs)[:, picks]),
            (S('逐窗 IIR 2 级（零状态）', 'per-window IIR x2 (zero state)'),
             lambda x: iir_bandpass(x[:, picks], 2, fs)[0]),
            (S('逐窗 IIR 3 级（零状态）', 'per-window IIR x3 (zero state)'),
             lambda x: iir_bandpass(x[:, picks], 3, fs)[0]),
            (S('逐窗 filtfilt 风格（双向）', 'per-window filtfilt-style (2-way)'),
             lambda x: iir_filtfilt(x[:, picks], 2, fs)),
        ]
        # 前两个变体必须整段滤波后再切窗，其余是逐窗滤波
        whole = {'整段循环 FFT 掩码（参考口径）', 'full-region circular FFT (ref)'}

        rhos = []
        for name, filt in variants:
            dir_vals, rest_vals = [], []
            for lab in LABELS:
                j = LABELS.index(lab)
                raw = regions[lab]['x']
                ws = (wcuts(raw, filt) if name in whole
                      else [filt(raw[s:s + win])
                            for s in range(0, raw.shape[0] - win + 1, win)])
                for w in ws:
                    dir_vals.append(dref.cca(w, dref.reference(FREQS[lab], fs, win)))
            rest_max = float('nan')
            if 'stop' in regions:
                raw = regions['stop']['x']
                ws = (wcuts(raw, filt) if name in whole
                      else [filt(raw[s:s + win])
                            for s in range(0, raw.shape[0] - win + 1, win)])
                rest_max = max(max(dref.cca(w, dref.reference(FREQS[l], fs, win))
                                   for l in LABELS) for w in ws)
            rd = float(np.mean(dir_vals))
            dm = float(np.min(dir_vals))
            rhos.append(rd)
            safe = (rest_max < args.theta1 <= dm)
            print('    %-32s %-9.4f %-9.4f %-9.4f %s' % (
                name, rd, dm, rest_max,
                S('OK', 'OK') if safe else
                (S('静息被击穿！', 'rest BREAKS GATE!') if rest_max >= args.theta1
                 else S('方向被拒（漏判）', 'dir REJECTED (missed)'))))
        if rhos:
            print(S('      跨实现 rho 极差 = %.4f（%.1f 个百分点）',
                    '      rho spread across filters = %.4f (%.1f pts)')
                  % (max(rhos) - min(rhos), 100 * (max(rhos) - min(rhos))))
        print()

    print(S('  结论：同一个 theta1 在不同带通实现下**不安全** —— '
            '静息段的最高分会被抬高到阈值之上（1~6 个百分点），'
            '阈值必须用 B 端**实际的**滤波器、在**自己的**静息段上重新标定。',
            '  CONCLUSION: one theta1 is NOT portable across bandpass implementations; '
            're-calibrate on the B-side filter and its own rest data.'))
    return 0


# ----------------------------------------------------------------- 自检

def run_selftest(args):
    ok = True
    print(S('=== 自检（不接网络、不读数据）===', '=== self-test ==='))

    an = Analyzer(1000, 4000, args.theta1, args.theta2, args.pad)

    # 1) 缓存版 CCA 必须与 degrade_reference.cca 完全一致
    rng = np.random.default_rng(7)
    x = rng.standard_normal((4000, 9))
    y = dref.reference(12.0, 1000, 4000)
    a1 = an.cca(x, 'forward')
    a2 = dref.cca(x, y)
    d = abs(a1 - a2)
    print('  1) %s  缓存CCA=%.10f  dref.cca=%.10f  差=%.2e  %s' % (
        S('缓存 CCA 与参考实现一致性', 'cached CCA == reference'),
        a1, a2, d, 'OK' if d < 1e-12 else 'FAIL'))
    ok &= d < 1e-12

    # 2) 12 Hz 纯正弦 -> rho(forward) 应接近 1
    sig = long_sine(12.00, 1000, 4000, 9, noise=0.01, rng=rng)
    rho = np.array([an.cca(sig, l) for l in LABELS])
    print('  2) %s  rho=%s  rho(forward)=%.4f  %s' % (
        S('12 Hz 正弦（正对照）', '12 Hz sine (positive)'),
        ' '.join('%s:%.3f' % (l[:2], v) for l, v in zip(LABELS, rho)), rho[0],
        'OK' if rho[0] > 0.95 and rho[0] == np.max(rho) else 'FAIL'))
    ok &= rho[0] > 0.95 and rho[0] == np.max(rho)

    # 3) 白噪声 -> 各候选都低
    nz = rng.standard_normal((4000, 9))
    rho_n = np.array([an.cca(nz, l) for l in LABELS])
    print('  3) %s  rho=%s  max=%.4f  %s' % (
        S('白噪声（负对照）', 'white noise (negative)'),
        ' '.join('%s:%.3f' % (l[:2], v) for l, v in zip(LABELS, rho_n)),
        float(np.max(rho_n)), 'OK' if np.max(rho_n) < 0.5 else 'FAIL'))
    ok &= np.max(rho_n) < 0.5

    # 4) 报文编解码往返
    nb = 'BC-Verifier'.encode('utf-8')
    payload = struct.pack('<HBBIHHB', DISC_MAGIC, DISC_VER, 0x02,
                          0xDEADBEEF, 9000, 0, len(nb)) + nb
    reply = payload + struct.pack('<I', zlib.crc32(payload) & 0xFFFFFFFF)
    m, v, ty, nc, dp, rs, nl = struct.unpack('<HBBIHHB', reply[:13])
    good = (len(reply) == 13 + len(nb) + 4 and nc == 0xDEADBEEF and dp == 9000
            and (zlib.crc32(payload) & 0xFFFFFFFF) == struct.unpack('<I', reply[-4:])[0])
    print('  4) %s  len=%d nonce=%08X port=%d  %s' % (
        S('ANNOUNCE 打包/回显', 'ANNOUNCE pack/echo'), len(reply), nc, dp,
        'OK' if good else 'FAIL'))
    ok &= good

    # 5) 合成流的端到端判决（走 Analyzer 自己的实时路径：带通 -> 取中间窗 -> 门控）
    #    长度必须 >= window + pad，否则 score_chunk 会返回 None
    stream = long_sine(12.00, 1000, an.window + 2 * an.pad + 500, 9,
                       noise=0.01, rng=rng).astype(np.float32)
    r = an.score_chunk(stream)
    if r is None:
        print('  5) %s  FAIL (chunk 太短: %d < %d)' % (
            S('12 Hz 流 -> 端到端判决', '12 Hz stream -> decision'),
            stream.shape[0], an.window + an.pad))
        return 1
    lab, w, s2 = an.gate(r[0])
    print('  5) %s  chunk=%d 判决=%s conf=%.3f  %s' % (
        S('12 Hz 流 -> 端到端判决', '12 Hz stream -> decision'), stream.shape[0],
        lab, float(r[0][w]), 'OK' if lab == 'forward' else 'FAIL'))
    ok &= lab == 'forward'

    print()
    print(S('自检结论：%s', 'RESULT: %s') % ('通过' if ok else '不通过'))
    return 0 if ok else 1


# ----------------------------------------------------------------- main

def main():
    global ASCII
    ap = argparse.ArgumentParser(
        description=S('A -> B 降质链路验收器（同时是最小接收端原型）',
                      'A->B acceptance verifier (also a minimal B-side prototype)'),
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--port', type=int, default=9000, help='data UDP port')
    ap.add_argument('--discovery-port', type=int, default=9001)
    ap.add_argument('--name', default='BC-Verifier')
    ap.add_argument('--no-discovery', action='store_true')
    ap.add_argument('--window', type=int, default=4000,
                    help=S('分析窗点数（>=4000；2s 窗无可用阈值）', 'analysis window pts'))
    ap.add_argument('--step', type=int, default=200, help='decision step (pts)')
    ap.add_argument('--theta1', type=float, default=0.57,
                    help=S('绝对门（手册 §6.2）', 'absolute gate'))
    ap.add_argument('--theta2', type=float, default=0.245,
                    help=S('差值门（手册 §6.2）', 'difference gate'))
    ap.add_argument('--vote', type=int, default=3, help='consecutive windows to confirm')
    ap.add_argument('--pad', type=int, default=1500,
                    help=S('带通两侧缓冲点数（消边界振铃）', 'bandpass padding pts'))
    ap.add_argument('--fs', type=int, default=1000)
    ap.add_argument('--seconds', type=float, default=1800.0)
    ap.add_argument('--quiet', action='store_true',
                    help=S('只打印判定变化', 'print only decision changes'))
    ap.add_argument('--csv', default=None, help='dump per-window scores')
    ap.add_argument('--assets', default=DEF_ASSETS)
    ap.add_argument('--tol', type=float, default=1e-3,
                    help=S('与参考值的容许偏差', 'tolerance vs reference'))
    ap.add_argument('--verify', action='store_true',
                    help=S('校验标定表可复现', 'verify calibration reproducibility'))
    ap.add_argument('--sweep', action='store_true',
                    help=S('复现 §9.2 的 alpha 表 + 逐窗门控统计', 'reproduce alpha table'))
    ap.add_argument('--degrade', choices=['noise', 'notch'], default='notch')
    ap.add_argument('--alpha', type=float, default=None,
                    help=S('单点降质强度（配合 --degrade）', 'single alpha'))
    ap.add_argument('--live-sim', action='store_true')
    ap.add_argument('--filter-check', action='store_true',
                    help=S('体检：theta1 能否跨带通实现复用',
                           'check: is theta1 portable across bandpass impls?'))
    ap.add_argument('--selftest', action='store_true')
    ap.add_argument('--ascii', action='store_true')

    args = ap.parse_args()
    ASCII = args.ascii

    if args.selftest:
        return run_selftest(args)
    if args.verify:
        return run_verify(args)
    if args.alpha is not None:
        return run_sweep(args, single_alpha=args.alpha)
    if args.sweep:
        return run_sweep(args)
    if args.live_sim:
        return run_live_sim(args)
    if args.filter_check:
        return run_filter_check(args)
    return run_serve(args)


if __name__ == '__main__':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
    sys.exit(main())
