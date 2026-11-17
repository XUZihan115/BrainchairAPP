"""Installed APK checks through adb + real UDP forwarding. No access to App internals.
Run after installing output/stage1/BrainChair-B-stage1.apk on emulator-5554.
Creates only stage1 evidence; uses emulator UDP forwards 19000/19001.
"""
import json
import re
import socket
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import stage1_packets as wire

ADB = 'D:/BrainChair/sdk/platform-tools/adb.exe'
PKG = 'com.yourteam.brainchair.receiver'
OUT = Path(__file__).resolve().parents[1] / 'output/stage1/android'
OUT.mkdir(parents=True, exist_ok=True)
RESULTS = []

def adb(*args):
    return subprocess.check_output([ADB, '-s', 'emulator-5554', *args], timeout=30)

def screen(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/brainchair-stage1.xml')
    raw = adb('shell', 'cat', '/sdcard/brainchair-stage1.xml')
    (OUT / (name + '.xml')).write_bytes(raw)
    root = ET.fromstring(raw)
    return root, '\n'.join(n.attrib.get('text', '') for n in root.iter('node'))

def tap_text(root, label):
    node = next(n for n in root.iter('node') if n.attrib.get('text') == label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def record(name, observed, expected):
    ok = all(s in observed for s in expected)
    RESULTS.append({'test': name, 'passed': ok, 'expected': expected, 'observed': observed})
    (OUT / 'checks.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding='utf-8')
    print(name, 'PASS' if ok else 'FAIL', flush=True)
    assert ok, name

def screenshot(name):
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))

def main():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-n', PKG + '/com.yourteam.brainchair.receiver.ReceiverActivity')
    for host, device in [(19000,9000),(19001,9001)]:
        adb('emu','redir','del',f'udp:{host}')
        adb('emu','redir','add',f'udp:{host}:{device}')
    root, _ = screen('initial')
    tap_text(root, '开始监听')
    time.sleep(1)
    root, text = screen('listening')
    record('foreground service starts', text, ['监听中', '收包 0'])
    service = adb('shell', 'dumpsys', 'activity', 'services', PKG).decode('utf-8', errors='replace')
    (OUT / 'service-running.txt').write_text(service, encoding='utf-8')
    assert 'isForeground=true' in service, 'Service is not foreground'
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.settimeout(2)
        discovery=wire.discover(sock,('127.0.0.1',19001))
        record('unicast DISCOVER and nonce echo',json.dumps(discovery),['2271560481','9000','BrainChair B'])
        target=('127.0.0.1',19000)
        outcome=[]
        thread=threading.Thread(target=lambda:outcome.append(wire.stream(sock,target,15)))
        thread.start(); time.sleep(6)
        tap_text(root,'暂停显示（接收继续）')
        frozen, text=screen('receiving')
        record('raw stream metadata and bounded window',text,['接收中','采样率 1000 Hz / 通道 62','连续缓冲 4000 点','拒收 0'])
        screenshot('receiving')
        thread.join(timeout=20); assert not thread.is_alive() and outcome
        tap_text(frozen,'恢复显示'); time.sleep(2)
        root,text=screen('normal-stop')
        record('15s 200pps reception and STOP reset',text,['收包 3002 / 接受 3002 / 拒收 0','连续缓冲 0 点 / 下一样本 0','流已结束 reason=1','序号缺口 0'])

        # Independent malformed datagrams; corrupted payload, truncated and extra byte.
        bad=bytearray(wire.block(2,0)); bad[40]^=1
        for raw in (bad,wire.block(2,0)[:-1],wire.block(2,0)+b'x'):
            sock.sendto(raw,target)
        # One missing block followed by its late arrival and one exact duplicate.
        for raw in (wire.info(),wire.block(2,0),wire.block(2,0),wire.block(4,10),wire.block(3,5),wire.stop(5,15)):
            sock.sendto(raw,target)
        time.sleep(2)
        root,text=screen('malformed-and-order')
        record('CRC length duplicate late and gap handling',text,['crc=1','length=2','重复 1 / 迟到乱序 1','序号缺口 1 / 样本缺口 5','流已结束 reason=1'])

        sock.sendto(wire.info(),target); sock.sendto(wire.block(2,0),target)
        time.sleep(2)
        root,text=screen('timeout')
        record('missing STOP timeout resets window',text,['断流超时','连续缓冲 0 点 / 下一样本 0'])

        before=int(re.search(r'接受 (\d+)',text).group(1))
        adb('shell','input','keyevent','KEYCODE_HOME')
        adb('shell','input','keyevent','KEYCODE_SLEEP')
        wire.stream(sock,target,10)
        adb('shell','input','keyevent','KEYCODE_WAKEUP')
        adb('shell','wm','dismiss-keyguard')
        adb('shell','am','start','-n',PKG+'/com.yourteam.brainchair.receiver.ReceiverActivity')
        time.sleep(2)
        root,text=screen('background')
        record('emulator background screen-off 10s receive',text,[f'接受 {before+2002}', '流已结束 reason=1'])
        screenshot('final')
        tap_text(root,'停止监听'); time.sleep(1)
        root,text=screen('service-stop')
        record('stop service',text,['未监听 · 监听已停止'])
        tap_text(root,'开始监听'); time.sleep(1)
        root,text=screen('restart')
        record('restart rebinds sockets and resets counters',text,['监听中','收包 0 / 接受 0'])
        wire.discover(sock,('127.0.0.1',19001))
        tap_text(root,'停止监听')
    (OUT/'service.txt').write_bytes(adb('shell','dumpsys','activity','services',PKG))
    (OUT/'logcat.txt').write_bytes(adb('logcat','-d','-s','BrainChairReceiver:E','AndroidRuntime:E'))
    print('Evidence:',OUT,flush=True)

if __name__=='__main__':
    main()
