"""Run the built Stage 3 APK against an independent host UDP peer on an EXISTING emulator.
Does not start/create an AVD, install software, change team repo, or operate real phones/Unity.
Requires emulator UDP redirections 19000->9000, 19001->9001, 19002->9002.
"""
import argparse
import json
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.yourteam.brainchair.receiver'


def packet(kind, seq, payload):
    body = struct.pack('<HBBIII', 0x47ee, 2, kind, 0x45454731, seq, len(payload)) + payload
    return body + struct.pack('<I', zlib.crc32(body))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', default='D:/BrainChair/sdk/platform-tools/adb.exe')
    p.add_argument('--serial', default='emulator-5554'); p.add_argument('--data', type=Path, default=ROOT/'output/stage25/data')
    p.add_argument('--out', type=Path, default=ROOT/'output/stage3/emulator')
    p.add_argument('--apk', type=Path, default=ROOT/'output/stage3/BrainChair-B-stage3.apk')
    p.add_argument('--quick-restart', action='store_true', help='Only verify configuration persistence and fresh service restart')
    a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=True); checks = []; samples = []; events = []
    def adb(*args, binary=False):
        result = subprocess.run([a.adb, '-s', a.serial, *map(str,args)], capture_output=True, timeout=30)
        if result.returncode: raise RuntimeError(result.stderr.decode('utf-8', 'replace'))
        return result.stdout if binary else result.stdout.decode('utf-8', 'replace').strip()
    def event(name, **kw):
        events.append(dict(event=name, ns=time.monotonic_ns(), **kw));print(name,kw,flush=True)
    def wait(name, predicate, seconds=5):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            if predicate(): checks.append(dict(check=name, passed=True)); return
            time.sleep(.05)
        raise AssertionError(name)
    def trace():
        try:
            value=json.loads(adb('exec-out','run-as',PACKAGE,'cat','files/stage2-status.json'))
            samples.append(dict(hostNs=time.monotonic_ns(), **value)); return value
        except (ValueError, RuntimeError): return {}
    def dump():
        adb('shell','uiautomator','dump','/sdcard/brainchair-stage3-ui.xml')
        return ET.fromstring(adb('exec-out','cat','/sdcard/brainchair-stage3-ui.xml'))
    cached={}
    def node(desc):
        if desc in cached:return cached[desc]
        for _ in range(4):
            tree=dump()
            for n in tree.iter('node'):
                bounds=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
                if n.get('content-desc') and len(bounds)==4 and bounds[2]>bounds[0] and bounds[3]>bounds[1]:
                    cached[n.get('content-desc')]=(n,bounds)
            if desc in cached:return cached[desc]
            adb('shell','input','swipe','500','1500','500','500','200')
            cached.clear()
        raise AssertionError('UI control missing: '+desc)
    def tap(desc):
        n,b=node(desc);adb('shell','input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2);event('tap',control=desc)
    def field(desc,value):
        n,b=node(desc);adb('shell','input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2)
        adb('shell','input','keyevent',123,*([67]*(len(n.get('text',''))+2)))
        adb('shell','input','text',value);adb('shell','input','keyevent',4)
        cached.clear()
    peer_out=a.out/'peer';peer_out.mkdir(exist_ok=True)
    peer_log=(a.out/'peer-process.log').open('w',encoding='utf-8')
    peer=subprocess.Popen([sys.executable,str(ROOT/'scripts/stage3_udp_peer.py'),'--port','9001',
                           '--feedback-host','127.0.0.1','--feedback-port','19002','--lease-ms','1000','--out',str(peer_out)],
                          stdout=peer_log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    def records():
        path=peer_out/'datagrams.jsonl'
        if not path.exists(): return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    try:
        wait('independent UDP peer ready',lambda: (peer_out/'ready.json').exists() and peer.poll() is None)
        result=adb('install','-r',str(a.apk));assert 'Success' in result;checks.append(dict(check='install APK',passed=True))
        adb('shell','am','force-stop',PACKAGE)
        adb('shell','am','start','-n',PACKAGE+'/com.yourteam.brainchair.receiver.ReceiverActivity')
        adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
        time.sleep(1)
        field('unity-ip','10.0.2.2');field('unity-speed','35');tap('unity-save');tap('开始监听')
        wait('UDP status 9002 reaches Android',lambda:trace().get('unityFeedbackOnline') is True,12)
        if trace().get('emergencyLocked'):
            wait('persisted emergency burst',lambda:len([r for r in records() if r.get('command')==6])>=3)
            time.sleep(.5);(peer_out/'action.json').write_text(json.dumps(dict(z=True)))
            wait('peer cleared Z',lambda:trace().get('unityFeedback',{}).get('emergency') is False)
            tap('确认Unity Z解除');wait('B explicit release',lambda:not trace().get('emergencyLocked',True))
        if a.quick_restart:
            old=trace()
            adb('shell','am','force-stop',PACKAGE)
            adb('shell','am','start','-n',PACKAGE+'/com.yourteam.brainchair.receiver.ReceiverActivity');cached.clear();time.sleep(.5)
            n,_=node('unity-ip');assert n.get('text')=='10.0.2.2'
            n,_=node('unity-speed');assert n.get('text')=='35'
            checks.append(dict(check='IP and speed persist across process restart',passed=True))
            tap('开始监听')
            def fresh_restart():
                current=trace()
                return current.get('elapsedMs',0)>old.get('elapsedMs',0) and current.get('unitySentPackets',0)>0 and current.get('unityFeedbackOnline') is True and current.get('finalCommand')=='STOP'
            wait('fresh restarted service STOP and actual UDP feedback (not stale file)',fresh_restart,12)
            (a.out/'screen.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
            print('Quick restart checks passed',flush=True)
            return
        tap('mode-MANUAL')
        wait('manual transition completed',lambda:trace().get('controlMode')=='MANUAL' and trace().get('transitioning') is False)
        for desc,code in [('FORWARD',1),('BACKWARD',2),('LEFT',3),('RIGHT',4)]:
            before=len(records());tap('manual-'+desc)
            wait('manual '+desc+' actual UDP',lambda:any(r.get('command')==code for r in records()[before:]))
        before=len(records());time.sleep(1.1)
        moves=[r for r in records()[before:] if r.get('command')==4]
        assert len(moves)>=4;checks.append(dict(check='manual 200ms renewals',passed=True,packets=len(moves)))
        tap('普通停止');wait('manual STOP datagram',lambda:records()[-1].get('command')==5)
        before=len(records());tap('急停锁定')
        wait('three actual ESTOP datagrams',lambda:len([r for r in records()[before:] if r.get('command')==6])==3)
        wait('Android displays C emergency',lambda:trace().get('unityFeedback',{}).get('emergency') is True)
        tap('manual-FORWARD');assert trace().get('emergencyLocked');assert records()[-1].get('command')==5
        (peer_out/'action.json').write_text(json.dumps(dict(z=True)))
        wait('Unity Z simulation reports clear',lambda:trace().get('unityFeedback',{}).get('emergency') is False)
        tap('确认Unity Z解除');wait('B release after new stationary status',lambda:trace().get('emergencyLocked') is False)
        tap('mode-EEG');wait('EEG transition completed',lambda:trace().get('controlMode')=='EEG' and trace().get('transitioning') is False)
        assert trace().get('windowSeconds')==2.5
        metadata=json.loads((a.data/'segments.json').read_text(encoding='utf-8'))
        raw=(a.data/'segments.bin').read_bytes();names=','.join(metadata['channelNames']).encode();channels=metadata['channelCount']
        def flow(segment,stop=True,seconds=4):
            # Evaluator chooses a labeled clip; only its raw sample bytes reach the Android decoder.
            source=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);target=('127.0.0.1',19000)
            source.sendto(packet(1,1,struct.pack('<IHBBH',1000,channels,1,0,len(names))+names),target)
            offset=segment['sampleOffset'];origin=time.time_ns()//1000;begin=time.monotonic_ns();seq=2
            for first in range(0,int(seconds*1000),5):
                deadline=begin+first*1_000_000
                remaining=(deadline-time.monotonic_ns())/1e9
                if remaining>0:time.sleep(remaining)
                pos=(offset+first%segment['sampleCount'])*channels*4
                payload=struct.pack('<QQHH',first,origin+first*1000,5,channels)+raw[pos:pos+5*channels*4]
                source.sendto(packet(2,seq,payload),target);seq+=1
            remaining=(begin+int(seconds*1e9)-time.monotonic_ns())/1e9
            if remaining>0:time.sleep(remaining)
            event('EEG source end',label=segment['label'],stop=stop)
            if stop:source.sendto(packet(3,seq,struct.pack('<QB',int(seconds*1000),1)),target)
            source.close()
        for segment,code in [(metadata['segments'][0],1),(metadata['segments'][4],3)]:
            before=len(records());event('EEG flow start',label=segment['label']);t=threading.Thread(target=flow,args=(segment,));t.start()
            while t.is_alive():trace();time.sleep(.05)
            t.join();wait('STREAM_STOP actual wire',lambda:trace().get('finalCommand')=='STOP' and records()[-1].get('command')==5)
            observed=records()[before:];assert any(r.get('command')==code for r in observed),f"No correct EEG command for {segment['label']}"
            assert not any(r.get('command') in (set(range(1,5))-{code}) for r in observed)
            checks.append(dict(check='real EEG clip -> actual UDP '+segment['label'],passed=True,packets=len(observed)))
        before=len(records());event('EEG no-STOP test start');flow(metadata['segments'][0],stop=False)
        wait('EEG silence stops output',lambda:trace().get('finalCommand')=='STOP' and records()[-1].get('command')==5,4)
        # Settings survive service/process restart; preserve all app data (no pm clear).
        old=trace();adb('shell','am','force-stop',PACKAGE)
        adb('shell','am','start','-n',PACKAGE+'/com.yourteam.brainchair.receiver.ReceiverActivity');cached.clear();time.sleep(.5)
        n,_=node('unity-ip');assert n.get('text')=='10.0.2.2'
        n,_=node('unity-speed');assert n.get('text')=='35'
        checks.append(dict(check='IP and speed persist across process restart',passed=True))
        tap('开始监听');wait('fresh restart safe STOP',lambda:trace().get('elapsedMs',0)>old.get('elapsedMs',0) and trace().get('infoPackets')==0 and trace().get('unityFeedbackOnline') is True and trace().get('finalCommand')=='STOP',12)
        (a.out/'screen.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
        (a.out/'meminfo.txt').write_text(adb('shell','dumpsys','meminfo',PACKAGE),encoding='utf-8')
        (a.out/'logcat.txt').write_text(adb('logcat','-d','-t','1000'),encoding='utf-8')
        wait('actual UDP uses delivery thread',lambda:trace().get('unitySenderThread')=='control-delivery')
    except Exception as e:
        checks.append(dict(check='run completion',passed=False,error=repr(e)))
        try:(a.out/'failure-screen.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
        except Exception:pass
        raise
    finally:
        (a.out/'report.json').write_text(json.dumps(dict(realPhones=False,realUnity=False,checks=checks,events=events),ensure_ascii=False,indent=2),encoding='utf-8')
        (a.out/'samples.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2),encoding='utf-8')
        try:
            (a.out/'final-status.json').write_text(adb('exec-out','run-as',PACKAGE,'cat','files/stage2-status.json'),encoding='utf-8')
            adb('shell','am','stopservice','-n',PACKAGE+'/com.yourteam.brainchair.receiver.ReceiverService')
        except Exception:pass
        peer.terminate();peer.wait(timeout=5);peer_log.close()
    print(json.dumps(dict(checks=len(checks),passed=all(c['passed'] for c in checks),out=str(a.out)),ensure_ascii=False))


if __name__=='__main__':main()
