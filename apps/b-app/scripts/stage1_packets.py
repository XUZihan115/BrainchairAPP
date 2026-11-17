"""Independent A-like UDP fixture sender; standard library only, no acceptance_probe imports.
Examples:
  python scripts/stage1_packets.py --host 192.168.1.20 --seconds 30
  python scripts/stage1_packets.py --host 127.0.0.1 --port 19000 --discovery-port 19001
Synthetic ramp samples verify layout/transport only; they are NOT EEG classification evidence.
"""
import argparse
import json
import socket
import struct
import time
import zlib

NAMES = 'Fp1,Fp2,F7,F3,Fz,F4,F8,FC5,FC1,FC2,FC6,T7,C3,Cz,C4,T8,TP9,CP5,CP1,CP2,CP6,TP10,P7,P3,Pz,P4,P8,PO9,O1,Oz,O2,PO10,FC3,FC4,C5,C1,C2,C6,CP3,CPz,CP4,P1,P2,POz,FT9,FTT9h,TTP7h,TP7,TPP9h,FT10,FTT10h,TPP8h,TP8,TPP10h,F9,F10,AF7,AF3,AF4,AF8,PO3,PO4'.split(',')

def seal(data):
    return data + struct.pack('<I', zlib.crc32(data) & 0xffffffff)

def packet(kind, seq, payload):
    return seal(struct.pack('<HBBIII', 0x47ee, 2, kind, 0x45454731, seq, len(payload)) + payload)

def info():
    names = ','.join(NAMES).encode()
    return packet(1, 1, struct.pack('<IHBBH', 1000, len(NAMES), 1, 0, len(names)) + names)

def block(seq, first, origin=1_000_000):
    values = [float(t * 100 + c) for t in range(5) for c in range(62)]
    return packet(2, seq, struct.pack('<QQHH', first, origin + first * 1000, 5, 62) + struct.pack('<310f', *values))

def stop(seq, next_index):
    return packet(3, seq, struct.pack('<QB', next_index, 1))

def discover(sock, target):
    nonce = 0x87654321
    sock.sendto(seal(struct.pack('<HBBII', 0x47ef, 1, 1, nonce, 0x45454731)), target)
    raw, source = sock.recvfrom(256)
    assert len(raw) >= 17
    magic, version, kind, echoed, port, reserved, n = struct.unpack_from('<HBBIHHB', raw)
    assert (magic, version, kind, echoed, reserved) == (0x47ef, 1, 2, nonce, 0)
    assert n <= 40 and len(raw) == 17 + n
    assert struct.unpack_from('<I', raw, len(raw)-4)[0] == zlib.crc32(raw[:-4]) & 0xffffffff
    return {'source': source, 'dataPort': port, 'nonce': echoed, 'name': raw[13:13+n].decode('utf-8')}

def stream(sock, target, seconds, send_stop=True):
    sock.sendto(info(), target)
    count = round(seconds * 200)
    begin = time.perf_counter()
    for i in range(count):
        delay = begin + i / 200 - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        sock.sendto(block(i + 2, i * 5), target)
    if send_stop:
        sock.sendto(stop(count + 2, count * 5), target)
    return {'sentBlocks': count, 'elapsedSeconds': time.perf_counter() - begin,
            'note': 'Sender counts only; verify receiver counters separately.'}

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--host', required=True)
    ap.add_argument('--port', type=int, default=9000)
    ap.add_argument('--discovery-port', type=int, default=9001)
    ap.add_argument('--seconds', type=float, default=30)
    ap.add_argument('--no-stop', action='store_true')
    args = ap.parse_args()
    if not 0 < args.seconds <= 1800:
        ap.error('seconds must be in (0,1800]')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(2)
        print(json.dumps({'discovery': discover(sock, (args.host, args.discovery_port))}, ensure_ascii=False))
        print(json.dumps(stream(sock, (args.host, args.port), args.seconds, not args.no_stop)))

if __name__ == '__main__':
    main()
