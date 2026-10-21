"""Independent Sum8 Unity wire test peer, not the Unity physics/controller implementation.

Normal PC use: python stage3_udp_peer.py --port 9001 --out output/stage3/peer
Android emulator: add --feedback-host 127.0.0.1 --feedback-port 19002
Write {"z": true} or {"fault": true} to OUT/action.json to simulate operator/telemetry.
"""
import argparse
import json
import select
import socket
import struct
import time
from pathlib import Path


def parse_command(raw):
    if len(raw) not in (8, 9) or raw[:2] != b'\xaa\x55' or raw[-2:] != b'\r\n':
        raise ValueError('length/delimiter')
    kind, size = raw[2:4]
    if kind != 1 or size not in (1, 2) or len(raw) != size + 7:
        raise ValueError('type/length')
    if sum(raw[2:-3]) & 255 != raw[-3]:
        raise ValueError('sum8')
    command = raw[4]
    speed = raw[5] if size == 2 else None
    if command not in range(1, 7) or speed is not None and speed not in range(101):
        raise ValueError('fields')
    return command, speed


def status_frame(flags, action, linear, angular, speed, timestamp):
    data = struct.pack('<BBhhhhBI', flags, action, linear, angular, 0, 0, speed, timestamp & 0xffffffff)
    body = bytes([3, len(data)]) + data
    return b'\xaa\x55' + body + bytes([sum(body) & 255]) + b'\r\n'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bind', default='0.0.0.0'); p.add_argument('--port', type=int, default=9001)
    p.add_argument('--feedback-host'); p.add_argument('--feedback-port', type=int, default=9002)
    p.add_argument('--lease-ms', type=int, default=1000); p.add_argument('--out', type=Path, required=True)
    a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); sock.bind((a.bind, a.port)); sock.setblocking(False)
    start = time.monotonic_ns(); action = 5; speed = 30; emergency = False; fault = False
    last_motion = 0; last_peer = None; next_status = 0; action_mtime = 0; count = 0; invalid = 0
    def save(name, obj):
        temporary = a.out / (name + '.tmp'); temporary.write_text(json.dumps(obj, indent=2), encoding='utf-8')
        temporary.replace(a.out / name)
    save('ready.json', dict(port=sock.getsockname()[1], leaseMs=a.lease_ms, realUnity=False))
    with (a.out / 'datagrams.jsonl').open('w', encoding='utf-8', buffering=1) as log:
        try:
            while True:
                ready, _, _ = select.select([sock], [], [], .01)
                if ready:
                    raw, peer = sock.recvfrom(65535); now = time.monotonic_ns()
                    try:
                        command, level = parse_command(raw); count += 1; last_peer = peer
                        if command == 6:
                            emergency = True; action = 6
                        elif not emergency:
                            action = command
                            if level is not None and level > 0: speed = level
                            if command in range(1, 5): last_motion = now
                        if count <= 30000:
                            log.write(json.dumps(dict(ns=now, command=command, speed=level, source=peer,
                                                      hex=raw.hex(' ').upper(), applied=action, emergency=emergency)) + '\n')
                    except ValueError as e:
                        invalid += 1; log.write(json.dumps(dict(ns=now, invalid=str(e), hex=raw.hex(' ').upper())) + '\n')
                now = time.monotonic_ns()
                if action in range(1, 5) and now-last_motion >= a.lease_ms*1_000_000: action = 5
                control = a.out / 'action.json'
                if control.exists() and control.stat().st_mtime_ns != action_mtime:
                    try:
                        request = json.loads(control.read_text(encoding='utf-8')); action_mtime = control.stat().st_mtime_ns
                        if request.get('z'): emergency = False; action = 5
                        if 'fault' in request: fault = bool(request['fault'])
                        save('action-applied.json', dict(**request, appliedNs=now))
                    except (ValueError, OSError): pass
                if now >= next_status:
                    flags = int(emergency) | (int(fault) << 1) | (int(action in range(1, 5)) << 3)
                    frame = status_frame(flags, action, 100 if action in (1, 2) else 0,
                                         100 if action in (3, 4) else 0, speed, (now-start)//1_000_000)
                    if last_peer:
                        sock.sendto(frame, (a.feedback_host or last_peer[0], a.feedback_port))
                    save('state.json', dict(ns=now, command=action, emergency=emergency, fault=fault,
                                           packets=count, invalid=invalid, lastMotionNs=last_motion, realUnity=False))
                    next_status = now + 200_000_000
        except KeyboardInterrupt:
            pass
        finally:
            sock.close()


if __name__ == '__main__': main()
