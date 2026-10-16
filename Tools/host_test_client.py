#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
脑控轮椅仿真 · PC 端测试客户端

用来在没有手机 App / 没有蓝牙硬件的阶段，验证 Unity 仿真端能不能正确吃指令。

用法：
    # 1) 交互模式（推荐先玩这个）
    python host_test_client.py --host 127.0.0.1 --port 8888

    # 2) 自动演示：连续动作一遍 + 步进模式一遍
    python host_test_client.py --demo

    # 3) 发二进制帧（验证 CRC 与帧解析），默认发 ASCII 行
    python host_test_client.py --binary --demo

    # 4) 故意不发心跳，验证 Unity 端的"心跳超时自动停车"
    python host_test_client.py --no-heartbeat --demo

交互模式里可以输入（大小写随意，回车发送）：
    F / 前进        B / 后退
    L / 左转        R / 右转
    RL / 原地左旋   RR / 原地右旋
    S / 停止        X / 急停        REL / 解除急停
    SPD:0.6        设定速度 m/s
    TURN:0.9       设定角速度 rad/s
    LIMIT:1.0      设定限速 m/s
    Q              请求回传一次状态
    quit           退出

步进模式（脑电语义：一条指令 = 走完就停）：
    MODE:STEP      本链路改成步进式（默认就是它）      MODE:LATCH  改回状态保持式
    SF / SB        前进一步 / 后退一步（默认 1.0 m）
    SL / SR        左转一步 / 右转一步（默认 30°）
    STEP:1.0       设定默认步长 m                      SETSTEPANGLE:30  设定默认步转角 °
    STEPMOVE:-0.5  走一段带符号位移 m                  STEPTURN:45      转一段带符号角度 °
"""

import argparse
import socket
import struct
import sys
import threading
import time

# 英文 Windows 的控制台默认是 cp437 / cp1252，直接打印中文会抛
# UnicodeEncodeError 把客户端整个弄崩。这里统一退化成替换字符，保证只影响观感、不影响功能。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")
    except Exception:
        pass

HEADER1 = 0xAA
HEADER2 = 0x55
ARG_SCALE = 100.0
STATUS_CMD = 0x80

# 与 WheelchairCommand.cs 保持一致
COMMAND_IDS = {
    "NONE": 0x00,
    "FORWARD": 0x01, "F": 0x01,
    "BACKWARD": 0x02, "B": 0x02,
    "STEERLEFT": 0x03, "L": 0x03,
    "STEERRIGHT": 0x04, "R": 0x04,
    "ROTATELEFT": 0x05, "RL": 0x05,
    "ROTATERIGHT": 0x06, "RR": 0x06,
    "STOP": 0x07, "S": 0x07,
    "EMERGENCYSTOP": 0x08, "X": 0x08,
    "RELEASEEMERGENCY": 0x09, "REL": 0x09,
    "SETSPEED": 0x0A, "SPD": 0x0A,
    "SETSPEEDLIMIT": 0x0B, "LIMIT": 0x0B,
    "SETTURNRATE": 0x0C, "TURN": 0x0C,
    "HEARTBEAT": 0x0D, "HB": 0x0D,
    "REQUESTSTATUS": 0x0E, "Q": 0x0E,
    # 步进相关（脑电链路）
    "SETCOMMANDMODE": 0x0F,
    "STEPMOVE": 0x10,
    "STEPTURN": 0x11,
    "STEP": 0x12, "STEPDISTANCE": 0x12, "SETSTEP": 0x12,
    "SETSTEPANGLE": 0x13, "SETSTEPTURN": 0x13,
    "STEPFORWARD": 0x14, "SF": 0x14,
    "STEPBACKWARD": 0x15, "SB": 0x15,
    "STEPLEFT": 0x16, "SL": 0x16,
    "STEPRIGHT": 0x17, "SR": 0x17,
}

# MODE:xxx 的参数是文字而不是数字，单独列一张表
# （二进制下发时转成 SetCommandMode 的 ARG：1 = 步进式，0 = 状态保持式）
MODE_ARGS = {
    "STEP": 1.0, "S": 1.0, "1": 1.0,
    "LATCH": 0.0, "HOLD": 0.0, "L": 0.0, "0": 0.0,
}

ACTIVE_COMMAND_NAMES = {
    0x00: "None", 0x01: "Forward", 0x02: "Backward",
    0x03: "SteerLeft", 0x04: "SteerRight",
    0x05: "RotateLeft", 0x06: "RotateRight",
    0x07: "Stop", 0x08: "EmergencyStop",
}


def crc8(data: bytes) -> int:
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if (crc & 0x80) else (crc << 1) & 0xFF
    return crc


def encode_frame(command_id: int, arg: float = 0.0, seq: int = 0) -> bytes:
    raw = int(round(arg * ARG_SCALE))
    raw = max(-32768, min(32767, raw))
    body = bytes([4, seq & 0xFF, command_id & 0xFF]) + struct.pack("<h", raw)
    return bytes([HEADER1, HEADER2]) + body + bytes([crc8(body)])


def parse_line(line: str):
    """把一行文本翻译成 (command_id, arg)。"""
    text = line.strip().upper()
    if not text:
        return None

    arg = 0.0
    key = text
    if ":" in text:
        key, _, arg_text = text.partition(":")
        key = key.strip()
        arg_text = arg_text.strip()

        # MODE:STEP / MODE:LATCH 的参数是文字，不能按 float 解析
        if key in ("MODE", "CMDMODE"):
            if arg_text not in MODE_ARGS:
                return None
            return COMMAND_IDS["SETCOMMANDMODE"], MODE_ARGS[arg_text]

        try:
            arg = float(arg_text)
        except ValueError:
            arg = 0.0

    command_id = COMMAND_IDS.get(key)
    if command_id is None:
        return None
    return command_id, arg


def decode_status_stream(buffer: bytearray):
    """从字节流里解析出状态帧，返回 (状态列表, 剩余缓冲)。"""
    states = []
    while True:
        if len(buffer) < 3:
            break
        if buffer[0] != HEADER1 or buffer[1] != HEADER2:
            del buffer[0]
            continue

        payload_len = buffer[2]
        total = 3 + payload_len + 1
        if len(buffer) < total:
            break

        body = buffer[2:3 + payload_len]
        if crc8(bytes(body)) != buffer[3 + payload_len]:
            del buffer[0]
            continue

        seq = buffer[3]
        cmd = buffer[4]
        if cmd == STATUS_CMD and payload_len == 10:
            battery = buffer[5]
            linear = struct.unpack("<h", bytes(buffer[6:8]))[0] / ARG_SCALE
            angular = struct.unpack("<h", bytes(buffer[8:10]))[0] / ARG_SCALE
            heading = struct.unpack("<H", bytes(buffer[10:12]))[0] / 10.0
            flags = buffer[12]
            states.append({
                "seq": seq,
                "battery": battery,
                "linear": linear,
                "angular": angular,
                "heading": heading,
                "emergency": bool(flags & 0x01),
                "fault": bool(flags & 0x02),
                # bit2：步进执行中（busy）。
                # 脑电上位机的节奏就是：发一步 -> 等它由 1 跳回 0 -> 再判下一次意图。
                "stepping": bool(flags & 0x04),
            })

        del buffer[:total]

    return states, buffer


class WheelchairClient:
    def __init__(self, host, port, use_binary, send_heartbeat):
        self.host = host
        self.port = port
        self.use_binary = use_binary
        self.send_heartbeat = send_heartbeat
        self.sock = None
        self.seq = 0
        self.rx_buffer = bytearray()
        self.running = False
        self.lock = threading.Lock()
        self.last_sent_name = "-"

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=5.0)
        self.sock.settimeout(0.2)
        self.running = True
        threading.Thread(target=self._reader, daemon=True).start()
        if self.send_heartbeat:
            threading.Thread(target=self._heartbeat, daemon=True).start()
        print("[client] 已连接 {}:{}".format(self.host, self.port))

    def _reader(self):
        while self.running:
            try:
                chunk = self.sock.recv(1024)
                if not chunk:
                    print("[client] 对端关闭了连接")
                    self.running = False
                    break
                self.rx_buffer.extend(chunk)
                states, self.rx_buffer = decode_status_stream(self.rx_buffer)
                for state in states:
                    flags = ""
                    if state["emergency"]:
                        flags += "  !!急停!!"
                    if state["fault"]:
                        flags += "  !!故障!!"
                    if state["stepping"]:
                        flags += "  [步进执行中]"
                    print("[status] v={:+.2f} m/s  w={:+.2f} rad/s  heading={:5.1f}°  "
                          "battery={}%  last_sent={}{}".format(
                              state["linear"], state["angular"], state["heading"],
                              state["battery"], self.last_sent_name, flags))
            except socket.timeout:
                continue
            except OSError:
                break

    def _heartbeat(self):
        while self.running:
            # 二进制模式下必须发二进制心跳帧：否则 "HB\n" 的原始字节会被
            # Unity 端的帧解析器当成脏数据（甚至误判成帧头），污染整条指令流。
            if self.use_binary:
                self.send_command(COMMAND_IDS["HEARTBEAT"], 0.0, "HB")
            else:
                self.send_text("HB\n")
            time.sleep(0.5)


    def send_text(self, text: str):
        if not self.sock:
            return
        try:
            with self.lock:
                self.sock.sendall(text.encode("ascii", errors="ignore"))
        except OSError:
            self.running = False

    def send_command(self, command_id: int, arg: float, name: str = "-"):
        self.last_sent_name = name
        if self.sock is None:
            return
        if self.use_binary:
            frame = encode_frame(command_id, arg, self.seq)
            self.seq = (self.seq + 1) & 0xFF
            try:
                with self.lock:
                    self.sock.sendall(frame)
            except OSError:
                self.running = False
        else:
            self.send_text("{}\n".format(name))

    def send_line(self, line: str):
        parsed = parse_line(line)
        if parsed is None:
            print("[client] 无法识别的指令：{}".format(line.strip()))
            return False
        command_id, arg = parsed
        self.send_command(command_id, arg, line.strip())
        return True

    def close(self):
        self.running = False
        try:
            if self.sock:
                self.sock.close()
        except OSError:
            pass


def run_demo(client):
    # 远程链路默认是"步进式"（脑电语义）：发一条 F 只走 1 米就停。
    # 前半段要演示的是连续动作，所以先声明本链路为状态保持式；
    # 后半段再切回步进式，演示"一条指令 = 走完就停"的脑电节奏。
    script = [
        (0.0, "MODE:LATCH"),
        (0.3, "LIMIT:1.0"), (0.5, "SPD:0.5"), (0.9, "F"),
        (2.9, "L"), (4.9, "F"), (6.3, "S"),
        (7.3, "RR"), (9.3, "RL"), (10.8, "S"),
        (11.8, "B"), (13.3, "S"),
        (14.3, "X"), (16.3, "REL"), (17.3, "S"),
        # ↓ 脑电步进演示
        (18.3, "MODE:STEP"),
        (18.6, "STEP:1.0"), (18.9, "SETSTEPANGLE:30"),
        (19.4, "SF"),    # A. 单独一步：前进 1 m，走完自动停
        (24.0, "SL"),    # B. 转向 / 后退各一步
        (25.6, "SR"),
        (27.0, "SB"),
        # C. 无缝衔接：第二条 SF 在第一条还没走完时发出（间隔 1.6 s > 不应期 1.0 s），
        #    会被并进当前动作 —— 一口气走 2 m 中间不刹车
        (31.5, "SF"),
        (33.1, "SF"),
        # D. 安全阀：连发三条，只允许合并成 2 m，第三条必须等这一段走完再走
        #    （MaxChainExtraSteps = 1 就是在这里起作用）
        (40.0, "SF"),
        (41.6, "SF"),
        (43.2, "SF"),
        (52.0, "MODE:LATCH"),
        (52.3, "S"),
    ]
    print("[demo] 开始自动演示，请观察 Unity 里的轮椅……")
    print("[demo] 0~18 s 连续动作（状态保持式）；18 s 之后是步进式（脑电语义：走完就停）")
    print("[demo] 19.4 s 一条 SF = 走 1 米就停；31.5 + 33.1 s 两条 SF = 一口气 2 米不停")
    print("[demo] 40/41.6/43.2 s 三条 SF = 先 2 米不停，停一下，再走 1 米（合并上限）")
    print("[demo] 注意看状态回传里的 [步进执行中]（flags bit2），它的 1->0 跳变就是「这一步走完了」")
    start = time.time()
    for offset, line in script:
        time.sleep(max(0.0, start + offset - time.time()))
        print("[demo] -> {}".format(line))
        client.send_line(line)
    time.sleep(2.0)
    print("[demo] 演示结束")


def run_interactive(client):
    print("[info] 输入指令回车发送，输入 help 看帮助，输入 quit 退出")
    while client.running:
        try:
            line = input("> ")
        except (EOFError, KeyboardInterrupt):
            break
        text = line.strip()
        if not text:
            continue
        if text.lower() in ("quit", "exit"):
            break
        if text.lower() == "help":
            print(__doc__)
            continue
        client.send_line(text)


def main():
    parser = argparse.ArgumentParser(description="脑控轮椅仿真 PC 端测试客户端")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8888)
    parser.add_argument("--binary", action="store_true", help="发送二进制帧（默认发 ASCII 文本行）")
    parser.add_argument("--demo", action="store_true", help="自动演示一遍全部动作")
    parser.add_argument("--no-heartbeat", action="store_true", help="不发心跳，用于验证看门狗")
    args = parser.parse_args()

    client = WheelchairClient(args.host, args.port, args.binary, not args.no_heartbeat)
    try:
        client.connect()
    except OSError as error:
        print("[client] 连接失败：{}".format(error))
        print("         请确认 Unity 场景里 WheelchairBootstrap 的 enableTcp 已勾选，并已按 Play。")
        return 1

    try:
        if args.demo:
            run_demo(client)
        else:
            run_interactive(client)
    finally:
        client.close()
        print("[client] 已退出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
