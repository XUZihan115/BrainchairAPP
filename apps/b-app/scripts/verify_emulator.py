"""Exercise the installed APK on emulator-5554 and save actual evidence."""
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output'
SHOTS = OUT / 'screenshots'
SHOTS.mkdir(parents=True, exist_ok=True)
ADB = ['D:/BrainChair/sdk/platform-tools/adb.exe', '-s', 'emulator-5554']
checks = []


def adb(*args):
    return subprocess.check_output(ADB + list(args), timeout=30)


def dump():
    for attempt in range(3):
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/brainchair-ui.xml')
        if b'UI hierchary dumped' in result:
            return ET.fromstring(adb('shell', 'cat', '/sdcard/brainchair-ui.xml'))
        time.sleep(1)
    raise RuntimeError('UI dump failed; refuse to read stale XML')


def texts():
    return '\n'.join(n.get('text', '') for n in dump().iter('node'))


def find(label):
    for n in dump().iter('node'):
        if n.get('text') == label:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            return str((x1+x2)//2), str((y1+y2)//2)
    raise AssertionError('Missing element: ' + label)


def tap(label):
    adb('shell', 'input', 'tap', *find(label))
    time.sleep(.4)


def shot(name):
    (SHOTS / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def check(name, expected):
    actual = texts()
    success = expected in actual
    checks.append({'test': name, 'passed': success, 'expected': expected, 'observed': actual})
    print(name, 'PASS' if success else 'FAIL', flush=True)
    assert success, (name, actual)


if __name__ == '__main__':
    adb('shell', 'am', 'force-stop', 'com.yourteam.brainchair')
    adb('shell', 'am', 'start', '-n', 'com.yourteam.brainchair/.MainActivity')
    find('连接模拟轮椅')
    recorder = subprocess.Popen(ADB + ['shell', 'screenrecord', '--time-limit', '150', '--bit-rate', '2500000', '/sdcard/BrainChair-demo.mp4'])
    try:
        shot('connection')
        tap('连接模拟轮椅'); check('模拟连接', '已连接 · 模拟轮椅')
        tap('控制'); check('初始停止', '当前速度 0%'); shot('control')
        x, y = find('↑ 前进')
        adb('shell', 'input', 'motionevent', 'DOWN', x, y)
        time.sleep(1)
        check('按住前进', '当前速度 30%'); shot('moving')
        adb('shell', 'input', 'motionevent', 'UP', x, y)
        time.sleep(.3); check('松手停止', '当前速度 0%')
        for label in ['↓ 后退', '← 左转', '右转 →']:
            x, y = find(label)
            adb('shell', 'input', 'motionevent', 'DOWN', x, y)
            time.sleep(.3); check(label, '当前速度 30%')
            adb('shell', 'input', 'motionevent', 'UP', x, y)
        tap('急停'); check('急停锁定', '已锁定'); shot('emergency')
        tap('↑ 前进'); check('锁定拒绝移动', '当前速度 0%')
        tap('解除急停 / 故障锁定'); tap('解除锁定'); check('明确解除后仍停止', '当前速度 0%')
        tap('状态'); check('状态电量', '86%'); shot('status')
        tap('注入模拟故障'); check('故障反馈', '故障码  3'); shot('fault')
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(2)
        adb('shell', 'am', 'start', '-n', 'com.yourteam.brainchair/.MainActivity')
        time.sleep(.4); check('后台断开', '已断开')
        tap('连接'); tap('连接模拟轮椅'); tap('控制'); check('重连不自动移动', '当前速度 0%')
    finally:
        adb('shell', 'input', 'motionevent', 'UP', '540', '900')
        adb('shell', 'pkill', '-2', 'screenrecord')
        try: recorder.wait(timeout=15)
        except subprocess.TimeoutExpired: recorder.terminate()
        adb('pull', '/sdcard/BrainChair-demo.mp4', str(OUT / 'BrainChair-demo.mp4'))
        (OUT / 'emulator-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
