#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实时接收 EEG Sender（安卓发送端）推来的 UDP 报文，解码、校验并给出联调结论。

这是一份「接收端」的最小可执行实现，同时充当真机联调的验收工具。它只依赖
docs/udp-protocol.md 的文字描述，不引用发送端任何代码。

它会做的事：
  1. 逐包校验 magic / version / payloadLen / CRC-32，统计异常包数量与种类；
  2. 跟踪 STREAM_INFO -> SAMPLE_BLOCK* -> STREAM_STOP 的会话边界；
  3. 用 firstSampleIndex 检查采样点有无断档、重复、乱序；
  4. 统计包间隔抖动、实际包速率、实际带宽、deviceTimeUs 与采样序号的漂移；
  5. （--cca）对收到的数据跑 CCA，直接判出这一段属于哪个方向，
     从而端到端验证「按住左转键 -> 接收端判为左转」整条链路；
  6. 在发现端口（默认 UDP 9001）应答发送端的 DISCOVER 广播，
     这样手机 App 里 IP 留空也能自动找到本机 —— 免去手查 IP。

用法：
    py -3 tools/udp_probe.py                 # 监听 0.0.0.0:9000，并在 9001 上应答发现
    py -3 tools/udp_probe.py --port 9000
    py -3 tools/udp_probe.py --no-discovery  # 不应答发现广播（App 里就必须填 IP）
    py -3 tools/udp_probe.py --no-cca        # 跳过 CCA 判定
    py -3 tools/udp_probe.py --save out.npy  # 把收到的采样存成 float32 矩阵
"""

import argparse
import binascii
import select
import socket
import struct
import sys
import time
from collections import deque

import numpy as np

MAGIC = 0x47EE
VERSION = 2
MSG_NAMES = {0x01: 'STREAM_INFO', 0x02: 'SAMPLE_BLOCK', 0x03: 'STREAM_STOP'}
STOP_REASONS = {0x01: 'RELEASED', 0x02: 'ERROR', 0x03: 'SWITCHED'}

NOMINAL_INTERVAL_US = 5000          # 期望包间隔 5 ms
DATAGRAM_LIMIT = 1472

# 发现子协议：与数据流完全解耦（不同端口、不同 magic），详见 docs/udp-protocol.md §11
DISC_MAGIC = 0x47EF
DISC_VERSION = 1
DISC_DISCOVER = 0x01
DISC_ANNOUNCE = 0x02
DISC_DISCOVER_SIZE = 16
DISC_HEADER_SIZE = 13
DISC_MAX_NAME = 40
DISC_DEFAULT_PORT = 9001

# 已确认的方向 <-> 频率映射（与 EegFrame.kt / docs/udp-protocol.md §7 一致）
DIRECTION_FREQS = {'forward': 12.00, 'left': 8.57, 'right': 6.67, 'backward': 5.45}
OCCIPITAL = ['POz', 'PO3', 'PO4', 'Oz', 'O1', 'O2', 'Pz', 'P1', 'P2']
HARMONICS = 4


# ----------------------------------------------------------------- 报文解析

def read_u16(buf, off):
    return struct.unpack_from('<H', buf, off)[0]


def read_u32(buf, off):
    return struct.unpack_from('<I', buf, off)[0]


def read_u64(buf, off):
    return struct.unpack_from('<Q', buf, off)[0]


def parse_datagram(data):
    """解析单个 UDP 报文。返回 dict；格式非法时抛 ValueError。"""
    if len(data) < 20:
        raise ValueError('长度不足 %d 字节' % len(data))
    if len(data) > DATAGRAM_LIMIT:
        raise ValueError('超过 MTU 上限：%d > %d' % (len(data), DATAGRAM_LIMIT))

    magic = read_u16(data, 0)
    if magic != MAGIC:
        raise ValueError('magic=0x%04X，期望 0x%04X' % (magic, MAGIC))

    version = data[2]
    if version != VERSION:
        raise ValueError('version=%d，期望 %d' % (version, VERSION))

    msg_type = data[3]
    payload_len = read_u32(data, 12)
    body_len = 16 + payload_len
    total_len = body_len + 4

    if total_len != len(data):
        raise ValueError('payloadLen=%d 推出总长 %d，实收 %d'
                         % (payload_len, total_len, len(data)))

    crc_expected = read_u32(data, body_len)
    crc_actual = binascii.crc32(data[:body_len]) & 0xFFFFFFFF
    if crc_expected != crc_actual:
        raise ValueError('CRC-32 不符：报文内 0x%08X，实算 0x%08X'
                         % (crc_expected, crc_actual))

    return {
        'seq': read_u32(data, 8),
        'type': msg_type,
        'device_id': read_u32(data, 4),
        'payload': data[16:body_len],
        'total_len': total_len,
    }


# ----------------------------------------------------------------- 发现子协议

def parse_discover(data):
    """校验一条 DISCOVER；通过则返回 (nonce, deviceId)，否则返回 None。

    无关的广播流量（例如别的程序在局域网里喊话）会在这里被静默丢弃。
    """
    if len(data) < DISC_DISCOVER_SIZE:
        return None
    if read_u16(data, 0) != DISC_MAGIC:
        return None
    if data[2] != DISC_VERSION:
        return None
    if data[3] != DISC_DISCOVER:
        return None
    expected = read_u32(data, 12)
    actual = binascii.crc32(data[:DISC_DISCOVER_SIZE - 4]) & 0xFFFFFFFF
    if expected != actual:
        return None
    return read_u32(data, 4), read_u32(data, 8)


def clamp_name(name, limit=DISC_MAX_NAME):
    """按字符边界把名称截到 limit 字节以内，避免截出半个 UTF-8 字符。"""
    while len(name.encode('utf-8')) > limit and name:
        name = name[:-1]
    return name.encode('utf-8')


def build_announce(nonce, data_port, name):
    """构造一条 ANNOUNCE 应答。

    头部固定 13 字节：magic(H2) version(B1) msgType(B1) nonce(I4) dataPort(H2)
    reserved(H2) nameLen(B1)，随后是 name，最后 4 字节 CRC。
    """
    raw = clamp_name(name)
    body = struct.pack('<HBBIHHB', DISC_MAGIC, DISC_VERSION, DISC_ANNOUNCE,
                       nonce, data_port, 0, len(raw)) + raw
    return body + struct.pack('<I', binascii.crc32(body) & 0xFFFFFFFF)


def answer_discover(sock, data, addr, data_port, name):
    """收到 DISCOVER 就单播回一条 ANNOUNCE，告诉对方「我在，数据发到这个端口」。

    返回是否确实应答了。无关的广播流量在这里被静默丢弃。
    """
    parsed = parse_discover(data)
    if parsed is None:
        return False
    nonce = parsed[0]
    try:
        sock.sendto(build_announce(nonce, data_port, name), addr)
    except OSError as err:
        print('\n  !! ANNOUNCE 应答失败：%s' % err)
        return False
    print('\n  [DISCOVER] %s:%d 发来发现请求，已应答 ANNOUNCE'
          '（数据端口 %d，声称名称 %s）' % (addr[0], addr[1], data_port, name))
    return True


# ----------------------------------------------------------------- 会话状态

class Session:
    """一次推流（按下 -> 松开）的接收统计。"""

    def __init__(self, started_at):
        self.started_at = started_at
        self.sample_rate = None
        self.channel_count = None
        self.data_type = None
        self.names = None

        self.packets = 0
        self.bytes = 0
        self.samples = 0
        self.blocks = 0

        self.seq_expected = 1
        self.seq_gaps = 0
        self.seq_dupes = 0
        self.seq_reorder = 0

        self.index_expected = 0
        self.index_gaps = 0
        self.index_dupes = 0
        self.index_reorder = 0

        self.intervals_us = deque(maxlen=200000)
        self.first_device_us = None
        self.last_device_us = None
        self.last_wall = started_at

        self.chunks = []
        self.last_sample_index = 0
        self.stop_reason = None

    # -- 事件 --------------------------------------------------------------

    def on_info(self, payload):
        self.sample_rate = read_u32(payload, 0)
        self.channel_count = read_u16(payload, 4)
        self.data_type = payload[6]
        names_len = read_u16(payload, 8)
        self.names = payload[10:10 + names_len].decode('utf-8').split(',')

    def on_block(self, payload, now):
        first_index = read_u64(payload, 0)
        device_us = read_u64(payload, 8)
        sample_count = read_u16(payload, 16)
        channel_count = read_u16(payload, 18)

        if self.channel_count is not None and channel_count != self.channel_count:
            raise ValueError('SAMPLE_BLOCK 通道数 %d 与 STREAM_INFO 的 %d 不一致'
                             % (channel_count, self.channel_count))

        if first_index == self.index_expected:
            pass
        elif first_index > self.index_expected:
            self.index_gaps += 1
            print('\n  !! 采样点断档：期望 #%d，收到 #%d（丢 %d 点）'
                  % (self.index_expected, first_index, first_index - self.index_expected))
        elif first_index == self.last_sample_index:
            self.index_dupes += 1
        else:
            self.index_reorder += 1

        self.index_expected = first_index + sample_count
        self.last_sample_index = first_index

        need = sample_count * channel_count
        if 20 + need * 4 > len(payload):
            raise ValueError('SAMPLE_BLOCK 负载过短，容纳不下 %d 个 float32' % need)

        samples = np.frombuffer(payload, dtype='<f4', count=need, offset=20)
        self.chunks.append(samples.reshape(sample_count, channel_count))
        self.samples += sample_count
        self.blocks += 1

        if self.first_device_us is None:
            self.first_device_us = device_us
        self.last_device_us = device_us

        if self.last_wall is not None:
            self.intervals_us.append(int((now - self.last_wall) * 1e6))
        self.last_wall = now

    def on_stop(self, payload, now):
        self.last_sample_index = read_u64(payload, 0)
        self.stop_reason = payload[8]

    # -- 派生指标 ----------------------------------------------------------

    @property
    def duration_s(self):
        if not self.sample_rate:
            return 0.0
        return self.samples / float(self.sample_rate)

    def matrix(self):
        if not self.chunks:
            return np.zeros((0, 0), dtype=np.float32)
        return np.concatenate(self.chunks, axis=0)


# ----------------------------------------------------------------- CCA 判定

def reference(freq, fs, n, harmonics=HARMONICS):
    t = np.arange(n, dtype=np.float64) / float(fs)
    cols = []
    for k in range(1, harmonics + 1):
        w = 2.0 * np.pi * freq * k * t
        cols.append(np.sin(w))
        cols.append(np.cos(w))
    return np.column_stack(cols)


def cca(x, y):
    """最大典型相关系数（去均值 + QR + SVD）。"""
    x = x - x.mean(axis=0, keepdims=True)
    y = y - y.mean(axis=0, keepdims=True)
    qx, _ = np.linalg.qr(x)
    qy, _ = np.linalg.qr(y)
    s = np.linalg.svd(qx.T @ qy, compute_uv=False)
    return float(s[0])


def estimate_direction(matrix, fs, names):
    """对收到的数据判方向，返回按相关系数降序的 (方向, 相关系数) 列表。"""
    if names:
        picks = [names.index(c) for c in OCCIPITAL if c in names]
    else:
        picks = []
    if not picks:
        picks = list(range(matrix.shape[1]))
    x = matrix[:, picks].astype(np.float64)

    scores = {}
    for label, freq in DIRECTION_FREQS.items():
        scores[label] = cca(x, reference(freq, fs, x.shape[0]))
    return sorted(scores.items(), key=lambda kv: -kv[1])


# ----------------------------------------------------------------- 输出格式

def fmt_intervals(intervals):
    if len(intervals) < 2:
        return '样本不足'
    arr = np.asarray(intervals, dtype=np.float64)
    return ('均值 %.0f us｜中位 %.0f｜标准差 %.0f｜最小 %.0f｜最大 %.0f｜'
            'P99 %.0f' % (arr.mean(), np.median(arr), arr.std(),
                          arr.min(), arr.max(), np.percentile(arr, 99)))


def report(session, wall_seconds, want_cca, save_path):
    print('\n' + '=' * 74)
    print('会话小结')
    print('=' * 74)
    print('  流参数      : %s Hz, %s 通道, dataType=0x%02X'
          % (session.sample_rate, session.channel_count,
             session.data_type if session.data_type is not None else 0))
    if session.names:
        shown = session.names if len(session.names) <= 8 else session.names[:8] + ['...']
        print('  通道名      : %s（共 %d 个）' % (', '.join(shown), len(session.names)))
    print('  报文        : %d 个（含 1 个 STREAM_INFO、%d 个 SAMPLE_BLOCK、'
          '1 个 STREAM_STOP）' % (session.packets, session.blocks))
    print('  采样点      : %d（%.2f s @ %s Hz）'
          % (session.samples, session.duration_s, session.sample_rate))
    print('  字节        : %.2f MB' % (session.bytes / 1048576.0))
    print('  墙钟时长    : %.2f s' % wall_seconds)
    if wall_seconds > 0:
        print('  实际带宽    : %.0f kB/s（标称约 256 kB/s）'
              % (session.bytes / wall_seconds / 1024.0))
        print('  实际包速率  : %.0f 包/秒（标称 200）'
              % (session.blocks / wall_seconds))
    print('  包间隔      : %s' % fmt_intervals(session.intervals_us))
    print('  标称间隔    : %d us' % NOMINAL_INTERVAL_US)

    drift = None
    if session.first_device_us is not None and session.sample_rate:
        span_us = session.last_device_us - session.first_device_us
        expect_us = (session.samples - 1) * 1e6 / session.sample_rate
        drift = (span_us - expect_us) / 1e6
        print('  设备时戳跨度: %.3f s；按采样率应有 %.3f s；偏差 %+.1f ms'
              % (span_us / 1e6, expect_us / 1e6, drift * 1e3))

    print('  序号连续性  : 采样点断档 %d 次、重复 %d 次、乱序 %d 次'
          % (session.index_gaps, session.index_dupes, session.index_reorder))
    if session.stop_reason is not None:
        print('  STREAM_STOP : reason=0x%02X（%s）｜lastSampleIndex=%d'
              % (session.stop_reason,
                 STOP_REASONS.get(session.stop_reason, '未知'),
                 session.last_sample_index))
    else:
        print('  STREAM_STOP : 未收到（会话未正常收尾）')

    matrix = session.matrix()

    if want_cca and matrix.shape[0] > 0:
        print('\n' + '-' * 74)
        print('CCA 方向判定（用收到的真实数据反推场景）')
        print('-' * 74)
        ranked = estimate_direction(matrix, session.sample_rate, session.names)
        best, best_r = ranked[0]
        second, second_r = ranked[1]
        for label, r in ranked:
            bar = '#' * int(round(max(r, 0.0) * 40))
            print('    %-9s %5.3f  %s' % (label, r, bar))
        print('    => 判定为「%s」，领先次优「%s」%.3f'
              % (best, second, best_r - second_r))
        if best == 'forward':
            expect = '前进键'
        else:
            expect = {'left': '左转键', 'right': '右转键', 'backward': '后退键'}[best]
        print('    请核对你刚才按住的是不是「%s」。' % expect)
        print('    静息（停键）不应出现明显领先，否则说明阈值需要调整。')

    if save_path and matrix.shape[0] > 0:
        np.save(save_path, matrix)
        print('\n  已保存 %d x %d 采样矩阵 -> %s'
              % (matrix.shape[0], matrix.shape[1], save_path))

    print('=' * 74)


# ----------------------------------------------------------------- 主循环

def local_ipv4_hints():
    """列出本机可能用于接收的局域网 IPv4 地址。

    顺序：默认出口地址在最前（它一定属于当前真实联网/热点的那块网卡），
    随后是主机名解析出的其它地址。回环与 169.254 自分配地址被剔除。
    """
    addrs = []

    # 默认出口地址：连一个外部地址来问内核「你会走哪块网卡」，不会真的发包
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(('8.8.8.8', 80))
        addrs.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in addrs:
                addrs.append(ip)
    except OSError:
        pass

    return [a for a in addrs
            if not a.startswith('127.') and not a.startswith('169.254.')]


def describe_net(ip):
    """给出网段的经验提示，帮用户挑对网卡。"""
    if ip.startswith('192.168.137.'):
        return 'Windows 移动热点（你电脑当热点）'
    if ip.startswith('192.168.43.') or ip.startswith('192.168.42.'):
        return '安卓手机热点常见网段'
    return ''


def main():
    ap = argparse.ArgumentParser(description='EEG Sender UDP 联调抓包工具')
    ap.add_argument('--port', type=int, default=9000, help='数据监听端口，默认 9000')
    ap.add_argument('--discovery-port', type=int, default=DISC_DEFAULT_PORT,
                    help='应答发现的端口，默认 9001')
    ap.add_argument('--no-discovery', action='store_true',
                    help='不应答发现广播（此时手机 App 里必须填 IP）')
    ap.add_argument('--name', default=None, help='应答时自报的名称，默认取本机主机名')
    ap.add_argument('--seconds', type=float, default=0, help='自动退出秒数，0 表示一直跑')
    ap.add_argument('--no-cca', action='store_true', help='跳过 CCA 方向判定')
    ap.add_argument('--save', default=None, help='把最后一段采样存成 .npy')
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('0.0.0.0', args.port))

    # 发现端口单独开一个 socket：广播流量与数据流互不干扰，也便于分别排错
    disc = None
    if not args.no_discovery:
        try:
            disc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            disc.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            disc.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            disc.bind(('0.0.0.0', args.discovery_port))
        except OSError as err:
            print('!! 无法绑定发现端口 %d：%s' % (args.discovery_port, err))
            print('   （本工具仍可正常抓包，但手机 App 里需要手动填 IP）')
            disc = None

    announce_name = args.name or socket.gethostname()

    print('=' * 74)
    print('EEG Sender 联调抓包工具')
    print('=' * 74)
    print('  数据监听  : 0.0.0.0:%d/udp（已监听全部网卡，含热点）' % args.port)
    if disc is not None:
        print('  发现应答  : 0.0.0.0:%d/udp，自报名称「%s」' % (args.discovery_port, announce_name))
    else:
        print('  发现应答  : 已关闭')
    print('  期望带宽  : 约 256 kB/s / 200 包每秒 / 每包 1280 字节')

    hints = local_ipv4_hints()
    if hints:
        print('  本机地址  :')
        for i, ip in enumerate(hints):
            tag = describe_net(ip)
            mark = '  <== 首选' if i == 0 else ''
            print('              %-15s %s%s' % (ip, tag, mark))
        if disc is not None:
            print('  推荐用法  : 手机 App 里把 IP **留空**，它会自行广播发现本机，无需手查 IP。')
        print('  备选用法  : 在 App 里填 %s，端口 %d（定向直发）。' % (hints[0], args.port))
        print('              填别的地址前请确认它与手机处在同一个热点网络里。')
    else:
        print('  本机地址  : 未能自动识别，请手动运行 ipconfig 查看')

    print('  然后按住某个方向键 2~5 秒。按 Ctrl+C 结束。')
    print('=' * 74)

    bad = {'magic': 0, 'version': 0, 'length': 0, 'crc': 0, 'other': 0}
    session = None
    session_started = None
    total_packets = 0
    disc_answered = 0
    t_start = time.time()
    last_tick = t_start
    last_session = None

    try:
        while True:
            now = time.time()
            if args.seconds > 0 and now - t_start >= args.seconds:
                break

            watched = [sock] if disc is None else [sock, disc]
            try:
                ready, _, _ = select.select(watched, [], [], 0.5)
            except (OSError, ValueError):
                ready = []

            if not ready:
                if session is not None and now - session.last_wall > 1.5:
                    # 手机可能异常退出，没发 STREAM_STOP；这里先收尾再继续监听
                    wall = session.last_wall - session.started_at
                    report(session, wall, not args.no_cca, args.save)
                    last_session = session
                    session = None
                if session is None and now - last_tick > 2.0:
                    print('  ... 等待数据（%s）' % time.strftime('%H:%M:%S'))
                    last_tick = now
                continue

            # 发现请求优先处理：它决定了手机接下来会把数据发到哪个端口
            if disc is not None and disc in ready:
                try:
                    disco_data, disco_addr = disc.recvfrom(65535)
                except OSError:
                    # Windows 特性：先前的 sendto 打到无人监听的端口会回 ICMP
                    # 端口不可达，并把假的 WSAECONNRESET 挂在本 socket 上，
                    # 让下一次 recvfrom 抛错。这里忽略，继续监听即可。
                    continue
                if answer_discover(disc, disco_data, disco_addr, args.port, announce_name):
                    disc_answered += 1
                if sock not in ready:
                    continue

            try:
                data, addr = sock.recvfrom(65535)
            except OSError:
                # 同上：丢包/ICMP 引起的假错误，不应中断抓包
                continue

            total_packets += 1
            try:
                pkt = parse_datagram(data)
            except ValueError as err:
                msg = str(err)
                if 'magic' in msg:
                    bad['magic'] += 1
                elif 'version' in msg:
                    bad['version'] += 1
                elif 'payloadLen' in msg or '长度' in msg or 'MTU' in msg:
                    bad['length'] += 1
                elif 'CRC' in msg:
                    bad['crc'] += 1
                else:
                    bad['other'] += 1
                print('\n  !! 丢弃异常报文（来自 %s:%d）：%s' % (addr[0], addr[1], msg))
                continue

            kind = pkt['type']

            if kind == 0x01:
                if session is not None:
                    wall = session.last_wall - session.started_at
                    report(session, wall, not args.no_cca, args.save)
                    last_session = session
                session = Session(now)
                session_started = now
                session.packets += 1
                session.bytes += pkt['total_len']
                session.on_info(pkt['payload'])
                # STREAM_INFO 恒为 seq=1，采样块从 seq=2 开始，故期望序号推进到 2
                session.seq_expected = 2
                print('\n' + '-' * 74)
                print('  STREAM_INFO  seq=1  deviceId=0x%08X  %d Hz / %d ch / dataType=0x%02X'
                      % (pkt['device_id'], session.sample_rate,
                         session.channel_count, session.data_type))
                print('-' * 74)
                continue

            if session is None:
                print('\n  !! 收到 %s 但没有 STREAM_INFO，忽略（来自 %s）'
                      % (MSG_NAMES.get(kind, kind), addr[0]))
                continue

            session.packets += 1
            session.bytes += pkt['total_len']

            seq = pkt['seq']
            if seq == session.seq_expected:
                session.seq_expected += 1
            elif seq > session.seq_expected:
                session.seq_gaps += 1
                print('\n  !! 报文序号跳变：期望 %d，收到 %d'
                      % (session.seq_expected, seq))
                session.seq_expected = seq + 1
            elif seq == session.seq_expected - 1:
                session.seq_dupes += 1
            else:
                session.seq_reorder += 1

            if kind == 0x02:
                try:
                    session.on_block(pkt['payload'], now)
                except ValueError as err:
                    print('\n  !! SAMPLE_BLOCK 解析失败：%s' % err)
                if session.blocks % 200 == 0 and session.blocks > 0:
                    elapsed = now - session.started_at
                    print('    ... 已收 %d 包｜%d 采样点｜%.2f s｜%.0f kB/s'
                          % (session.blocks, session.samples,
                             session.samples / float(session.sample_rate or 1),
                             session.bytes / max(elapsed, 1e-6) / 1024.0))
            elif kind == 0x03:
                session.on_stop(pkt['payload'], now)
                wall = session.last_wall - session.started_at
                report(session, wall, not args.no_cca, args.save)
                last_session = session
                session = None
            else:
                print('\n  !! 未知 msgType=0x%02X，已跳过' % kind)

    except KeyboardInterrupt:
        print('\n\n收到 Ctrl+C，准备收尾…')
        if session is not None and session.samples > 0:
            wall = (session.last_wall or time.time()) - session.started_at
            report(session, wall, not args.no_cca, args.save)
            last_session = session

    print('\n' + '=' * 74)
    print('总统计')
    print('=' * 74)
    print('  运行时长      : %.1f s' % (time.time() - t_start))
    print('  收到数据报    : %d 个' % total_packets)
    if disc is not None:
        print('  应答发现请求  : %d 次' % disc_answered)
    print('  异常报文      : magic %d｜version %d｜长度 %d｜CRC %d｜其它 %d'
          % (bad['magic'], bad['version'], bad['length'], bad['crc'], bad['other']))
    if total_packets == 0:
        print('\n  [FAIL] 一个包都没收到。请检查：')
        print('     1) 手机与电脑是否处在同一个热点/局域网（同一网段）；')
        if disc_answered > 0:
            print('     2) 发现是通的（已应答 %d 次），说明手机没把数据发到本机 %d 端口；'
                  % (disc_answered, args.port))
            print('        请确认接收端应答中声明的数据端口，与 App 实际发送的端口一致。')
        elif disc is not None:
            print('     2) 连发现广播都没收到：App 里的 IP 是不是填了值？')
            print('        填了 IP 就走定向直发、不做发现；要自动发现请把 IP 清空。')
        else:
            print('     2) 本次以 --no-discovery 启动，App 里必须手动填 IP；')
            print('        本机地址见上方「本机地址」。')
        print('     3) Windows 防火墙是否放行了本脚本的 UDP %d（数据）与 %d（发现）入站。'
              % (args.port, args.discovery_port))
    elif sum(bad.values()) == 0:
        print('\n  [OK] 所有收到的报文均通过 magic / version / payloadLen / CRC-32 校验')
    else:
        print('\n  [WARN] 存在异常报文，见上方逐条提示')

    if last_session is not None:
        print('  最后一次会话  : 判定为 %s'
              % (estimate_direction(last_session.matrix(), last_session.sample_rate,
                                    last_session.names)[0][0]
                 if last_session.matrix().shape[0] > 0 and not args.no_cca
                 else '（未做判定）'))
    print('=' * 74)


if __name__ == '__main__':
    main()
