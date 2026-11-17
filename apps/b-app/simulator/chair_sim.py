"""Teaching lower-controller simulator. No external packages for self-test/TCP.

python chair_sim.py --self-test
python chair_sim.py --tcp 8765      # diagnostic client only; Android uses in-app/SPP
python chair_sim.py --serial COM5  # requires pyserial, paired SPP virtual COM port
"""
import argparse
import socket
import time


def frame(cmd, arg=0):
    return bytes((0xAA, 0x55, cmd, arg, cmd ^ arg, 13, 10))


class Parser:
    def __init__(self):
        self.buf = bytearray()

    def feed(self, data):
        result = []
        for byte in data:
            self.buf.append(byte)
            while self.buf:
                if self.buf[0] != 0xAA or (len(self.buf) >= 2 and self.buf[1] != 0x55):
                    del self.buf[0]
                    continue
                if len(self.buf) < 7:
                    break
                b = self.buf
                if b[4] == b[2] ^ b[3] and b[5:7] == b'\r\n':
                    result.append((b[2], b[3]))
                    del b[:7]
                else:
                    del b[0]
        return result


class Chair:
    def __init__(self):
        self.speed, self.limit, self.fault = 0, 30, 0
        self.heartbeat = self.motion = float('-inf')

    def status(self):
        return b''.join(frame(c, a) for c, a in [(0x81, 86), (0x84, self.speed), (0x85, 2 if self.fault else int(self.speed > 0)), (0x83, self.fault)])

    def receive(self, cmd, arg, now):
        if cmd in (1, 2, 3, 4):
            if self.fault or not 0 <= arg <= 100 or now - self.heartbeat >= .5:
                return b''
            self.speed, self.motion = min(arg, self.limit), now
        elif cmd == 5 and arg == 0:
            self.speed = 0
        elif cmd == 6 and 0 <= arg <= 100:
            self.limit = arg
            self.speed = min(self.speed, arg)
        elif cmd == 7 and arg == 0:
            self.speed, self.fault = 0, 1
        elif cmd == 8 and arg == 0:
            self.heartbeat = now
        elif cmd == 9 and arg == 0 and self.speed == 0 and now - self.heartbeat < .5:
            self.fault = 0
        else:
            return b''
        return frame(0x82, cmd) + self.status()

    def tick(self, now):
        if self.speed and (now - self.heartbeat >= .5 or now - self.motion >= .5):
            self.speed, self.fault = 0, 2


def serve(read, write):
    chair, parser = Chair(), Parser()
    last_status = 0
    while True:
        data = read()
        now = time.monotonic()
        for cmd, arg in parser.feed(data):
            response = chair.receive(cmd, arg, now)
            if response:
                write(response)
                print(f'cmd={cmd:02x} speed={chair.speed} fault={chair.fault}', flush=True)
        chair.tick(now)
        if now - last_status >= .2:
            write(chair.status())
            last_status = now


def self_test():
    import unittest

    class Safety(unittest.TestCase):
        def test_stream(self):
            p = Parser()
            stream = b'noise' + bytes.fromhex('AA55011E000D0A') + frame(1, 30) + frame(5)
            got = []
            for b in stream:
                got.extend(p.feed(bytes([b])))
            self.assertEqual([(1, 30), (5, 0)], got)

        def test_timeout(self):
            c = Chair(); c.receive(8, 0, 0); c.receive(1, 90, 0)
            self.assertEqual(c.speed, 30)
            c.tick(.499); self.assertEqual(c.speed, 30)
            c.tick(.5); self.assertEqual((c.speed, c.fault), (0, 2))
            c.receive(8, 0, .6); c.receive(1, 30, .6); self.assertEqual(c.speed, 0)
            c.receive(9, 0, .6); self.assertEqual((c.speed, c.fault), (0, 0))

        def test_motion_lease(self):
            c = Chair(); c.receive(8, 0, 0); c.receive(1, 30, 0)
            c.receive(8, 0, .4); c.tick(.5); self.assertEqual(c.speed, 0)

        def test_estop(self):
            c = Chair(); c.receive(8, 0, 0); c.receive(1, 30, 0); c.receive(7, 0, .1)
            c.receive(1, 30, .2); self.assertEqual((c.speed, c.fault), (0, 1))
            c.receive(5, 0, .2); self.assertEqual(c.fault, 1)

    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Safety))
    raise SystemExit(not result.wasSuccessful())


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--self-test', action='store_true')
    ap.add_argument('--tcp', type=int)
    ap.add_argument('--serial')
    args = ap.parse_args()
    if args.self_test:
        self_test()
    elif args.tcp:
        with socket.socket() as server:
            server.bind(('127.0.0.1', args.tcp)); server.listen(1)
            print('Local diagnostic server:', args.tcp, flush=True)
            conn, address = server.accept()
            with conn:
                conn.settimeout(.02)
                def read():
                    try:
                        data = conn.recv(1024)
                        if not data: raise EOFError('Client disconnected')
                        return data
                    except socket.timeout:
                        return b''
                try: serve(read, conn.sendall)
                except (EOFError, ConnectionError, KeyboardInterrupt): pass
    elif args.serial:
        import serial
        with serial.Serial(args.serial, 115200, timeout=.02) as port:
            try: serve(lambda: port.read(1024), port.write)
            except KeyboardInterrupt: pass
    else:
        ap.print_help()
