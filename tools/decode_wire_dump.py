#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立实现的协议解析器，用于交叉验证 Android 发送端的报文格式。

它完全不引用 Kotlin 侧的任何常量，只按 docs/udp-protocol.md 的文字描述编写，
因此可以当作「接收端 App 该如何解析」的可执行范本。

用法:
    py -3 tools/decode_wire_dump.py [wire-dump.hex 路径]
"""

import binascii
import struct
import sys
from pathlib import Path

MAGIC = 0x47EE
VERSION = 2

MSG_NAMES = {0x01: "STREAM_INFO", 0x02: "SAMPLE_BLOCK", 0x03: "STREAM_STOP"}
STOP_REASONS = {0x01: "RELEASED", 0x02: "ERROR", 0x03: "SWITCHED"}


def read_u16(buf, off):
    return struct.unpack_from("<H", buf, off)[0]


def read_u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


def read_u64(buf, off):
    return struct.unpack_from("<Q", buf, off)[0]


def parse_stream(blob: bytes):
    """把整段字节流切成报文，逐条解析并自校验。"""
    packets = []
    offset = 0
    expected_seq = 1

    while offset < len(blob):
        if len(blob) - offset < 16:
            raise ValueError(f"偏移 {offset} 处剩余 {len(blob) - offset} 字节，不足以构成包头")

        magic = read_u16(blob, offset)
        if magic != MAGIC:
            raise ValueError(f"偏移 {offset} 处 magic=0x{magic:04X}，期望 0x{MAGIC:04X}")

        version = blob[offset + 2]
        if version != VERSION:
            raise ValueError(f"偏移 {offset} 处 version={version}，期望 {VERSION}")

        msg_type = blob[offset + 3]
        device_id = read_u32(blob, offset + 4)
        seq = read_u32(blob, offset + 8)
        payload_len = read_u32(blob, offset + 12)

        if seq != expected_seq:
            raise ValueError(f"seq={seq} 不连续，期望 {expected_seq}")
        expected_seq += 1

        body_len = 16 + payload_len
        total_len = body_len + 4
        if offset + total_len > len(blob):
            raise ValueError(f"偏移 {offset} 处报文被截断")

        crc_expected = read_u32(blob, offset + body_len)
        crc_actual = binascii.crc32(blob[offset:offset + body_len]) & 0xFFFFFFFF
        if crc_expected != crc_actual:
            raise ValueError(
                f"seq={seq} CRC 校验失败: 报文内 0x{crc_expected:08X}，实算 0x{crc_actual:08X}"
            )

        payload = blob[offset + 16:offset + body_len]
        packets.append(
            {
                "seq": seq,
                "type": msg_type,
                "device_id": device_id,
                "payload_len": payload_len,
                "total_len": total_len,
                "payload": payload,
            }
        )
        offset += total_len

    return packets


def describe(packet):
    payload = packet["payload"]
    name = MSG_NAMES.get(packet["type"], f"UNKNOWN(0x{packet['type']:02X})")
    head = (
        f"seq={packet['seq']:<3} {name:<12} "
        f"deviceId=0x{packet['device_id']:08X} payloadLen={packet['payload_len']:<5} "
        f"totalLen={packet['total_len']:<5}"
    )

    if packet["type"] == 0x01:
        sample_rate = read_u32(payload, 0)
        channel_count = read_u16(payload, 4)
        data_type = payload[6]
        reserved = payload[7]
        names_len = read_u16(payload, 8)
        names = payload[10:10 + names_len].decode("utf-8").split(",")
        return (
            head + f" | {sample_rate} Hz, {channel_count} ch, "
            f"dataType=0x{data_type:02X}(readable {'float32' if data_type == 1 else 'int16'}), "
            f"reserved={reserved}, names={names}"
        )

    if packet["type"] == 0x02:
        first_index = read_u64(payload, 0)
        time_us = read_u64(payload, 8)
        sample_count = read_u16(payload, 16)
        channel_count = read_u16(payload, 18)
        floats = struct.unpack_from(f"<{sample_count * channel_count}f", payload, 20)
        samples = [
            list(floats[i * channel_count:(i + 1) * channel_count])
            for i in range(sample_count)
        ]
        return head + f" | #{first_index}@{time_us}us {sample_count}×{channel_count} {samples}"

    if packet["type"] == 0x03:
        last_index = read_u64(payload, 0)
        reason = payload[8]
        return head + f" | lastSampleIndex={last_index} reason=0x{reason:02X}({STOP_REASONS.get(reason, '?')})"

    return head


def main():
    dump = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("app/build/wire-dump.hex")
    if not dump.exists():
        raise SystemExit(f"找不到 {dump}，请先运行: gradlew :app:testDebugUnitTest")

    blob = bytes(int(tok, 16) for tok in dump.read_text().split())
    print(f"输入 {dump}：{len(blob)} 字节\n")

    packets = parse_stream(blob)
    for packet in packets:
        print("  " + describe(packet))

    # 端到端还原：把各 SAMPLE_BLOCK 拼回连续采样序列
    stream = []
    expected_channel_count = None
    for packet in packets:
        if packet["type"] == 0x01:
            expected_channel_count = read_u16(packet["payload"], 4)
        elif packet["type"] == 0x02:
            payload = packet["payload"]
            first_index = read_u64(payload, 0)
            sample_count = read_u16(payload, 16)
            channel_count = read_u16(payload, 18)
            if channel_count != expected_channel_count:
                raise ValueError("SAMPLE_BLOCK 的 channelCount 与 STREAM_INFO 不一致")
            if first_index != len(stream):
                raise ValueError(f"采样序号断档：期望 {len(stream)}，收到 {first_index}")
            floats = struct.unpack_from(f"<{sample_count * channel_count}f", payload, 20)
            stream.extend(
                list(floats[i * channel_count:(i + 1) * channel_count])
                for i in range(sample_count)
            )

    expected = [
        [0.0, 1.0], [10.0, 11.0], [20.0, 21.0], [30.0, 31.0],
        [0.0, 1.0], [10.0, 11.0], [20.0, 21.0], [30.0, 31.0],
        [0.0, 1.0], [10.0, 11.0], [20.0, 21.0], [30.0, 31.0],
    ] * 1
    # 区块取样顺序：起始 0 → 3 → 2 → 1，每块 3 个采样点
    expected = (
        [[0.0, 1.0], [10.0, 11.0], [20.0, 21.0]]
        + [[30.0, 31.0], [0.0, 1.0], [10.0, 11.0]]
        + [[20.0, 21.0], [30.0, 31.0], [0.0, 1.0]]
        + [[10.0, 11.0], [20.0, 21.0], [30.0, 31.0]]
    )

    print(f"\n还原出 {len(stream)} 个采样点，{len(stream[0])} 通道：")
    for i, sample in enumerate(stream):
        print(f"  #{i:<3} {sample}")

    if stream != expected:
        raise SystemExit(f"\n❌ 采样序列与期望不符\n期望 {expected}")
    print("\n✅ 采样序列与硬回绕期望完全一致")
    print(f"✅ {len(packets)} 个报文全部通过 magic / version / seq / CRC-32 校验")


if __name__ == "__main__":
    main()
