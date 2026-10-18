#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""脑控轮椅 WiFi(UDP) 链路测试客户端（任务书 2.2）。

作用：在没有手机 App 的情况下，从电脑上验证 Unity 端的 WiFi 链路是否正常。

    Unity 端（监听 9001）  <--指令帧 0x01--  本脚本
    Unity 端（回发 9002）  --状态帧 0x03-->  本脚本

帧格式（任务书只规定了帧头/类型/长度/数据/校验/帧尾，校验算法与长度语义没写死，
所以两边都做成可配 + 自动识别）：

    AA 55 | 类型 | 长度 | 数据... | 校验 | 0D 0A

    类型    0x01 指令帧 / 0x02 保留 / 0x03 状态帧
    数据    指令帧：[0] 指令码，[1] 速度或转向强度 0~100（可选）
            指令码：0x01 前进 0x02 后退 0x03 左转 0x04 右转 0x05 停止 0x06 急停
    长度    默认 = 数据区字节数（可用 --length-mode 改成 body/total 试对端兼容性）
    校验    默认 sum8（可用 --checksum xor8/crc8 试），覆盖 类型+长度+数据

常用示例：

    # 让轮椅前进 2 秒（每 0.1 秒发一次，最后补一个停止）
    python wifi_udp_client.py --host 192.168.43.1 --cmd forward --speed 60 --repeat 20 --interval 0.1 --then-stop

    # 原地左转
    python wifi_udp_client.py --host 192.168.43.1 --cmd left --speed 50 --repeat 15 --interval 0.1

    # 停在原地看状态帧（顺便验证 9002 回传）
    python wifi_udp_client.py --host 192.168.43.1 --listen-only --seconds 10

    # 交互模式：w/s 前进后退、a/d 左右转、空格停车、x 急停、+/- 调速、q 退出
    python wifi_udp_client.py --host 192.168.43.1 --interactive

    # 只想看组出来的字节流（不联网），用来和 App 抓包比对
    python wifi_udp_client.py --cmd forward --speed 60 --print-only

参数里的 --host 就是 Unity 那台电脑在手机热点里的 IP（Windows 上用 ipconfig 看
"无线局域网适配器"或热点网卡的地址，一般是 192.168.43.x / 192.168.137.x）。
"""

import argparse
import binascii
import socket
import struct
import sys
import threading
import time

HEADER1 = 0xAA
HEADER2 = 0x55
TRAILER = b"\x0D\x0A"

TYPE_COMMAND = 0x01
TYPE_RESERVED = 0x02
TYPE_STATUS = 0x03

MIN_FRAME = 7
MAX_DATA = 60
MAX_FRAME = MAX_DATA + 7
STATUS_DATA_LEN = 15

DEFAULT_COMMAND_PORT = 9001
DEFAULT_STATUS_PORT = 9002

COMMANDS = {
    "forward": (0x01, "前进"),
    "backward": (0x02, "后退"),
    "left": (0x03, "左转"),
    "right": (0x04, "右转"),
    "stop": (0x05, "停止"),
    "estop": (0x06, "急停"),
    "emergency": (0x06, "急停"),
}

# 中文/大写别名也收，省得记英文
COMMAND_ALIASES = {
    "前进": "forward", "前": "forward", "f": "forward",
    "后退": "backward", "后": "backward", "b": "backward",
    "左转": "left", "左": "left", "l": "left",
    "右转": "right", "右": "right", "r": "right",
    "停止": "stop", "停": "stop", "s": "stop",
    "急停": "estop", "x": "estop",
}

ACTION_NAMES = {
    0x00: "其它",
    0x01: "前进",
    0x02: "后退",
    0x03: "左转",
    0x04: "右转",
    0x05: "停止",
    0x06: "急停",
}

CHECKSUMS = ("sum8", "xor8", "crc8")


# --------------------------------------------------------------------- 校验

def crc8(data):
    """CRC-8：多项式 0x07 / 初值 0x00（与 Unity 工程其它链路一致）。"""
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def checksum(kind, data, offset, count):
    chunk = data[offset:offset + count]
    if kind == "sum8":
        return sum(chunk) & 0xFF
    if kind == "xor8":
        value = 0
        for byte in chunk:
            value ^= byte
        return value
    if kind == "crc8":
        return crc8(chunk)
    raise ValueError("未知校验算法：%s" % kind)


# ------------------------------------------------------------------- 组帧

def build_frame(frame_type, payload, kind="sum8", length_mode="data"):
    """组一帧：长度字段按 length_mode 填，校验覆盖 类型+长度+数据。"""
    payload = bytes(payload)
    if len(payload) > MAX_DATA:
        raise ValueError("数据区超过 %d 字节：%d" % (MAX_DATA, len(payload)))

    if length_mode == "data":
        length_field = len(payload)
    elif length_mode == "body":
        length_field = len(payload) + 3
    elif length_mode == "total":
        length_field = len(payload) + 7
    else:
        raise ValueError("未知长度语义：%s" % length_mode)

    body = bytes([frame_type, length_field & 0xFF]) + payload
    return bytes([HEADER1, HEADER2]) + body + bytes([checksum(kind, body, 0, len(body))]) + TRAILER


def build_command_frame(code, speed, kind="sum8", length_mode="data"):
    payload = bytes([code & 0xFF])
    if speed is not None:
        payload += bytes([max(0, min(100, int(speed)))])
    return build_frame(TYPE_COMMAND, payload, kind, length_mode)


def classify_length(length_field, data_length):
    if length_field == data_length:
        return "数据区长度"
    if length_field == data_length + 3:
        return "类型+长度+数据+校验"
    if length_field == data_length + 7:
        return "整帧长度"
    return "与三种约定都不符"


def plausible_length(length_field, data_length):
    """长度字段与候选帧尾推出的数据长度是否自洽（三种未知语义任取其一）。

    数据区里也可能出现 0x0D 0x0A，长度语义对不上的候选是假帧尾，必须跳过而不是判成校验失败，
    否则真帧尾还没到的半包会被误丢。
    """
    return length_field in (data_length, data_length + 3, data_length + 7)


# ----------------------------------------------------------------- 收帧解析

class FrameParser(object):
    """帧尾定界 + 校验自动识别（与 Unity 端 WifiUdpFrameParser 同一个思路）。"""

    def __init__(self):
        self.buffer = bytearray()
        self.detected = None      # (算法, 覆盖范围)
        self.checksum_failures = 0
        self.noise_bytes = 0
        self.frames = 0

    def feed(self, data):
        self.buffer += data
        out = []
        while True:
            frame = self._read_one()
            if frame is None:
                break
            out.append(frame)
        return out

    def _read_one(self):
        while True:
            index = self.buffer.find(bytes([HEADER1, HEADER2]))
            if index < 0:
                if self.buffer[-1:] == bytes([HEADER1]):
                    self.noise_bytes += len(self.buffer) - 1
                    self.buffer = bytearray([HEADER1])
                else:
                    self.noise_bytes += len(self.buffer)
                    self.buffer = bytearray()
                return None

            if index:
                self.noise_bytes += index
                del self.buffer[:index]

            if len(self.buffer) < MIN_FRAME:
                return None

            frame_type = self.buffer[2]
            length_field = self.buffer[3]
            rejected_candidate = False

            # 两趟扫候选帧尾：第一趟只认"长度字段自洽"的候选
            # （真帧尾几乎总在这一趟），第二趟兜底认长度语义完全对不上的候选
            # （对端可能用三种之外的写法）。数据区里的假帧尾几乎不会满足长度自洽，
            # 所以它落不到第一趟，也就不会把"真帧尾还没到的半包"判死丢掉。
            for only_plausible in (True, False):
                pos = 4
                while True:
                    trailer = self.buffer.find(TRAILER, pos)
                    if trailer < 0:
                        break
                    checksum_index = trailer - 1
                    if checksum_index < 4:
                        pos = trailer + 1
                        continue

                    data_length = checksum_index - 4
                    if data_length > MAX_DATA:
                        break

                    plausible = plausible_length(length_field, data_length)
                    if plausible != only_plausible:
                        pos = trailer + 1
                        continue
                    if not plausible and rejected_candidate:
                        pos = trailer + 1
                        continue

                    match = self._match(checksum_index)
                    if match is None:
                        if plausible:
                            rejected_candidate = True
                        pos = trailer + 1
                        continue

                    algo, rng = match
                    frame = {
                        "type": frame_type,
                        "length_field": length_field,
                        "checksum": self.buffer[checksum_index],
                        "data": bytes(self.buffer[4:checksum_index]),
                        "algo": algo,
                        "range": rng,
                        "length_semantics": classify_length(length_field, data_length),
                    }
                    del self.buffer[:trailer + 2]
                    self.frames += 1
                    return frame

            if not rejected_candidate:
                # 还没有长度自洽的帧尾：多半是这一帧还没收完，等下一包
                if len(self.buffer) >= MAX_FRAME:
                    self.noise_bytes += 1
                    del self.buffer[:1]
                    continue
                return None

            self.checksum_failures += 1
            del self.buffer[:1]

    def _match(self, checksum_index):
        actual = self.buffer[checksum_index]

        if self.detected is not None:
            algo, rng = self.detected
            if self._try(algo, rng, checksum_index) == actual:
                return self.detected

        for algo in CHECKSUMS:
            for rng in ("type", "data"):
                if self._try(algo, rng, checksum_index) == actual:
                    self.detected = (algo, rng)
                    return self.detected
        return None

    def _try(self, algo, rng, checksum_index):
        start = 2 if rng == "type" else 4
        count = checksum_index - start
        if count <= 0:
            return None
        return checksum(algo, self.buffer, start, count)


def format_status(data):
    """状态帧数据区（15 字节，仿真端自定字段顺序，见 Unity 侧 WifiUdpProtocol.cs）。"""
    if len(data) < STATUS_DATA_LEN:
        return "状态帧数据区只有 %d 字节（期望 %d）：%s" % (
            len(data), STATUS_DATA_LEN, binascii.hexlify(data).decode("ascii"))

    flags = data[0]
    action = data[1]
    linear_mm = struct.unpack_from("<h", data, 2)[0]
    angular_mrad = struct.unpack_from("<h", data, 4)[0]
    x_cm = struct.unpack_from("<h", data, 6)[0]
    z_cm = struct.unpack_from("<h", data, 8)[0]
    percent = data[10]
    stamp = struct.unpack_from("<I", data, 11)[0]

    marks = []
    if flags & 0x01:
        marks.append("急停锁存")
    if flags & 0x02:
        marks.append("故障")
    if flags & 0x04:
        marks.append("步进中")
    if flags & 0x08:
        marks.append("运动中")

    return ("[状态] 动作={0} 线速度={1:+.2f} m/s 角速度={2:+.2f} rad/s "
            "位置=({3:+.2f}, {4:+.2f}) m 速度百分比={5}% 时间戳={6} ms 标志={7}").format(
        ACTION_NAMES.get(action, "0x%02X" % action),
        linear_mm / 1000.0, angular_mrad / 1000.0, x_cm / 100.0, z_cm / 100.0,
        percent, stamp, "/".join(marks) if marks else "无")


# ------------------------------------------------------------------- 运行

def resolve_command(text):
    key = text.strip().lower()
    if key in COMMANDS:
        return key
    if text.strip() in COMMAND_ALIASES:
        return COMMAND_ALIASES[text.strip()]
    if key in COMMAND_ALIASES:
        return COMMAND_ALIASES[key]
    raise argparse.ArgumentTypeError(
        "未知指令 %r，可用：%s（也接受中文：前进/后退/左转/右转/停止/急停）"
        % (text, "/".join(sorted(set(COMMANDS)))))


def status_listener(sock, stop_event, quiet):
    """后台线程：收并打印状态帧。"""
    while not stop_event.is_set():
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            continue
        except OSError:
            return

        parser = FrameParser()
        for frame in parser.feed(data):
            if frame["type"] != TYPE_STATUS:
                if not quiet:
                    print("[收到] 非状态帧：类型=0x%02X 数据=%s 校验=%s（%.2f 覆盖 %s）" % (
                        frame["type"], binascii.hexlify(frame["data"]).decode("ascii"),
                        frame["algo"], frame["range"]))
                continue
            print("%s  <- %s:%d（校验 %s / 长度 %s）" % (
                format_status(frame["data"]), addr[0], addr[1],
                frame["algo"], frame["length_semantics"]))
            sys.stdout.flush()


def send(sock, target, frame, quiet):
    sock.sendto(frame, target)
    if not quiet:
        print("[发送] %s -> %s:%d" % (binascii.hexlify(frame).decode("ascii").upper(), target[0], target[1]))
        sys.stdout.flush()


def run_interactive(sock, target, args):
    import msvcrt   # Windows

    speed = args.speed
    print("交互模式：w 前进 / s 后退 / a 左转 / d 右转 / 空格 停止 / x 急停 / +/- 调速 / q 退出")
    print("当前速度百分比：%d%%" % speed)
    while True:
        if not msvcrt.kbhit():
            time.sleep(0.03)
            continue

        key = msvcrt.getch()
        try:
            char = key.decode("ascii").lower()
        except UnicodeDecodeError:
            continue

        if char == "q":
            break
        if char in ("+", "="):
            speed = min(100, speed + 10)
            print("速度百分比 -> %d%%" % speed)
            continue
        if char in ("-", "_"):
            speed = max(10, speed - 10)
            print("速度百分比 -> %d%%" % speed)
            continue

        name = {"w": "forward", "s": "backward", "a": "left", "d": "right",
                " ": "stop", "x": "estop"}.get(char)
        if name is None:
            continue

        if name in ("stop", "estop"):
            code = COMMANDS[name][0]
            frame = build_command_frame(code, None, args.checksum, args.length_mode)
            send(sock, target, frame, args.quiet)
            print("%s" % COMMANDS[name][1])
            continue

        # 按住式：每次按键发 1 秒（10 x 100ms），最后补停止，避免"键一松就停"的手感落空
        code = COMMANDS[name][0]
        for _ in range(10):
            send(sock, target, build_command_frame(code, speed, args.checksum, args.length_mode), args.quiet)
            time.sleep(0.1)
        send(sock, target, build_command_frame(COMMANDS["stop"][0], None, args.checksum, args.length_mode), args.quiet)

    print("退出交互模式")


def main():
    parser = argparse.ArgumentParser(
        description="脑控轮椅 WiFi(UDP) 链路测试客户端（对应 Unity 端 enableWifiUdp）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("常用示例：")[-1] if "常用示例：" in __doc__ else None)

    parser.add_argument("--host", default="127.0.0.1",
                        help="Unity 那台电脑的 IP（默认 127.0.0.1，本机自测用）")
    parser.add_argument("--port", type=int, default=DEFAULT_COMMAND_PORT, help="指令端口（默认 9001）")
    parser.add_argument("--status-port", type=int, default=DEFAULT_STATUS_PORT, help="状态帧端口（默认 9002）")
    parser.add_argument("--cmd", type=resolve_command, default=None,
                        help="要发的指令：forward/backward/left/right/stop/estop（或中文）")
    parser.add_argument("--speed", type=int, default=60, help="速度或转向强度 0~100（默认 60）")
    parser.add_argument("--repeat", type=int, default=1, help="重复发送次数（默认 1）")
    parser.add_argument("--interval", type=float, default=0.1, help="重复发送间隔秒（默认 0.1）")
    parser.add_argument("--then-stop", action="store_true", help="发送结束后补一条停止")
    parser.add_argument("--checksum", choices=CHECKSUMS, default="sum8",
                        help="校验算法（默认 sum8；任务书没写，可逐个试）")
    parser.add_argument("--length-mode", choices=("data", "body", "total"), default="data",
                        help="长度字段语义（默认 data = 数据区字节数）")
    parser.add_argument("--broadcast", action="store_true", help="发到 255.255.255.255 广播地址")
    parser.add_argument("--listen-only", action="store_true", help="只收状态帧，不发指令")
    parser.add_argument("--seconds", type=float, default=0.0, help="--listen-only 的持续秒数（0 = 一直收到 Ctrl+C）")
    parser.add_argument("--interactive", action="store_true", help="键盘交互模式")
    parser.add_argument("--print-only", action="store_true", help="只打印组好的字节流，不联网")
    parser.add_argument("--quiet", action="store_true", help="不打印每个发送的帧")

    args = parser.parse_args()

    target = ("255.255.255.255" if args.broadcast else args.host, args.port)

    if args.print_only:
        code, name = COMMANDS[args.cmd] if args.cmd else (0x01, "前进")
        frame = build_command_frame(code, args.speed, args.checksum, args.length_mode)
        print("%s（0x%02X）速度 %d%% 校验 %s 长度语义 %s" % (name, code, args.speed, args.checksum, args.length_mode))
        print(binascii.hexlify(frame).decode("ascii").upper())
        return 0

    # 收状态帧的套接字（顺便就是"本机端口"，无需设备 firewall 例外）
    status_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    status_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    status_sock.bind(("0.0.0.0", args.status_port))
    status_sock.settimeout(0.3)

    stop_event = threading.Event()
    listener = threading.Thread(target=status_listener, args=(status_sock, stop_event, args.quiet))
    listener.daemon = True
    listener.start()
    print("监听状态帧端口 %d（0x03 帧会打印成 [状态] 行）" % args.status_port)

    send_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    send_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

    try:
        if args.listen_only:
            deadline = None if args.seconds <= 0 else time.time() + args.seconds
            print("只收不发：Ctrl+C 结束" if deadline is None else "只收不发 %.1f 秒" % args.seconds)
            while deadline is None or time.time() < deadline:
                time.sleep(0.1)
            return 0

        if args.interactive:
            run_interactive(send_sock, target, args)
            return 0

        if not args.cmd:
            parser.error("需要 --cmd（或用 --listen-only / --interactive / --print-only）")

        code, name = COMMANDS[args.cmd]
        speed = None if args.cmd in ("stop", "estop") else args.speed
        print("向 %s:%d 发送 %s（0x%02X）速度 %s，共 %d 次，间隔 %.2f 秒，校验 %s" % (
            target[0], target[1], name, code, "无" if speed is None else "%d%%" % speed,
            args.repeat, args.interval, args.checksum))

        for index in range(args.repeat):
            send(send_sock, target, build_command_frame(code, speed, args.checksum, args.length_mode), args.quiet)
            if index + 1 < args.repeat and args.interval > 0:
                time.sleep(args.interval)

        if args.then_stop:
            send(send_sock, target, build_command_frame(COMMANDS["stop"][0], None, args.checksum, args.length_mode), args.quiet)
            print("已补发停止")

        # 等一下状态帧，好让使用者看到回传确实通了
        print("等待状态帧 2 秒…（没打印 [状态] 行说明 9002 回传没通）")
        time.sleep(2.0)
        return 0
    except KeyboardInterrupt:
        print("已中断")
        return 0
    finally:
        stop_event.set()
        time.sleep(0.35)
        status_sock.close()
        send_sock.close()


if __name__ == "__main__":
    sys.exit(main())
