"""Installed B APK + independently packetized raw EEG from read-only A APK.
B->C is the explicit in-process simulated transport, NOT UDP/actual Unity.
Run after scripts/build-stage27.ps1 and installing output/stage27/BrainChair-B-stage27.apk.
"""
import json
import math
import re
import socket
import struct
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import stage1_packets as wire
from stage27_sender import Replay,run

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'output/stage27/android'
ADB='D:/BrainChair/sdk/platform-tools/adb.exe';PKG='com.yourteam.brainchair.receiver'
ACTIVITY=PKG+'/com.yourteam.brainchair.receiver.ReceiverActivity'
CHECKS=[];ROWS=[];EVENTS=[]

def adb(*args):return subprocess.check_output([ADB,'-s','emulator-5554',*args],timeout=30)
def status():return json.loads(adb('exec-out','run-as',PKG,'cat','files/stage2-status.json'))
def check(name,ok,detail):
    CHECKS.append(dict(test=name,passed=bool(ok),detail=detail));save()
    print(name,'PASS' if ok else 'FAIL',flush=True);assert ok,name
def save():
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'checks.json').write_text(json.dumps(CHECKS,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'observations.json').write_text(json.dumps(ROWS,ensure_ascii=False),encoding='utf-8')
    (OUT/'sender-events.json').write_text(json.dumps(EVENTS,ensure_ascii=False,indent=2),encoding='utf-8')
def tap(text):
    adb('shell','uiautomator','dump','/sdcard/stage27-test.xml')
    nodes=ET.fromstring(adb('shell','cat','/sdcard/stage27-test.xml'))
    node=next(n for n in nodes.iter('node') if n.attrib.get('text')==text)
    a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds']))
    adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
def wait(label,predicate,seconds=3):
    end=time.perf_counter()+seconds
    while True:
        s=status()
        if predicate(s):return s
        if time.perf_counter()>end:raise AssertionError((label,s))
        time.sleep(.1)

def case(replay,name,clicks,**options):
    before=status();case_rows=[];errors=[]
    def emit(e):EVENTS.append(dict(case=name,**e))
    def work():
        try:run(replay,('127.0.0.1',19000),clicks,emit,**options)
        except BaseException as e:errors.append(e)
    thread=threading.Thread(target=work);thread.start()
    while thread.is_alive():
        s=status();s['case']=name;ROWS.append(s);case_rows.append(s);time.sleep(.13)
    thread.join()
    if errors:raise errors[0]
    if options.get('final_stop',True):s=wait(name+' STOP',lambda s:s['stopPackets']>before['stopPackets'])
    else:time.sleep(1.3);s=status()
    s['case']=name;ROWS.append(s);case_rows.append(s)
    check(name+' no analysis spans streams',s['analysisCount']==before['analysisCount'],s)
    expected='ESTOP' if s['emergencyLocked'] else 'STOP'
    check(name+' final and mock received',s['finalCommand']==expected and s['sentCommand']==expected and s['mockApplied']==expected,s)
    check(name+' no motion delivered',all(r['command'] in ['STOP','ESTOP'] for row in case_rows for r in row['mockReceipts']),{'rows':len(case_rows)})
    return s

def long_diagnostic(replay):
    # Explicit 6s synthetic diagnostic, not a 4s click. Shows actual Android first decision timing.
    before=status();origin=time.time_ns()//1000;target=('127.0.0.1',19000);errors=[]
    picks=['Pz','O1','Oz','O2','P1','P2','POz','PO3','PO4'];indices={replay.names.index(n):i for i,n in enumerate(picks)}
    blocks=[]
    for k in range(1200):
        values=[]
        for row in range(5):
            t=(k*5+row)/1000
            for ch in range(62):
                c=indices.get(ch)
                values.append(0. if c is None else 5*math.sin(2*math.pi*12*t+c*.37)+2*math.cos(4*math.pi*12*t+c*.17)+math.sin(6*math.pi*12*t+c*.23)+.25*math.sin(2*math.pi*(17.19+c*1.71)*t+c))
        blocks.append(wire.packet(2,k+2,struct.pack('<QQHH',k*5,origin+k*5000,5,62)+struct.pack('<310f',*values)))
    done=threading.Event()
    def sender():
        try:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
                sock.sendto(replay.info(),target);begin=time.perf_counter();EVENTS.append(dict(case='6s-diagnostic',event='INFO',hostNs=time.perf_counter_ns()))
                for k,p in enumerate(blocks):
                    delay=begin+k*.005-time.perf_counter()
                    if delay>0:time.sleep(delay)
                    sock.sendto(p,target)
                delay=begin+6-time.perf_counter()
                if delay>0:time.sleep(delay)
                sock.sendto(wire.stop(1202,6000),target);EVENTS.append(dict(case='6s-diagnostic',event='STOP',hostNs=time.perf_counter_ns()))
        except BaseException as e:errors.append(e)
        finally:done.set()
    thread=threading.Thread(target=sender);thread.start();diagnostic=[]
    while not done.is_set():
        s=status();s['case']='6s-diagnostic';ROWS.append(s);diagnostic.append(s);time.sleep(.1)
    thread.join()
    if errors:raise errors[0]
    final=wait('diagnostic STOP',lambda s:s['stopPackets']>before['stopPackets'])
    check('6s diagnostic real Android CCA and mock motion',any(s['stable']=='FORWARD' and s['finalCommand']=='FORWARD' and s['mockApplied']=='FORWARD' for s in diagnostic),{'observations':len(diagnostic),'last':final})
    check('STOP after observed motion reaches mock receiver',final['raw']=='STOP' and final['stable']=='STOP' and final['finalCommand']=='STOP' and final['mockApplied']=='STOP',final)
    active=[s for s in diagnostic if s.get('streamStartedNs') and s['streamId']>before['streamId']]
    start_ns=active[0]['streamStartedNs']
    decisions=[s for s in active if s.get('decidedMs') and s['raw']=='FORWARD']
    motions={r['serial']:r for s in diagnostic+[final] for r in s['mockReceipts'] if r['command']=='FORWARD' and r['sentNs']>=start_ns}
    first_motion=min(motions.values(),key=lambda r:r['sentNs'])
    metrics=dict(scope='Android B INFO accept to decision/delivery; B-C in-process simulation, no UDP',
        firstObservedRawMs=decisions[0]['decidedMs']-start_ns/1e6,firstObservedRawAnalysisCount=decisions[0]['analysisCount'],
        firstMockMotionMs=(first_motion['receivedNs']-start_ns)/1e6,
        computeMs=[s['computeMs'] for s in decisions],periodMs=[s['periodMs'] for s in decisions],
        heapMinBytes=min(s['heapBytes'] for s in active),heapMaxBytes=max(s['heapBytes'] for s in active),
        processCpuPercentOneCore=100*(active[-1]['processCpuMs']-active[0]['processCpuMs'])/max(1,active[-1]['elapsedMs']-active[0]['elapsedMs']))
    audit=final['stopAudits'][-1]
    stop_receipt=min((r for r in final['mockReceipts'] if r['revision']==final['finalRevision'] and r['sentNs']>=audit['socketReceivedNs']),key=lambda r:r['sentNs'])
    metrics['stopSocketToFinalMs']=(final['finalChangedNs']-audit['socketReceivedNs'])/1e6
    metrics['stopSocketToMockSendMs']=(stop_receipt['sentNs']-audit['socketReceivedNs'])/1e6
    metrics['mockSendToReceiveMs']=(stop_receipt['receivedNs']-stop_receipt['sentNs'])/1e6
    (OUT/'diagnostic-timing.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2),encoding='utf-8')
    return diagnostic

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    adb('shell','am','force-stop',PKG);adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
    adb('shell','am','start','-n',ACTIVITY)
    # Inactive-screen emergency action must also persist before any receiver exists.
    tap('急停锁定');tap('开始监听');time.sleep(.4)
    s=wait('inactive emergency applied on start',lambda s:s['emergencyLocked'] and s['mockApplied']=='ESTOP')
    check('emergency works before listening starts',s['finalCommand']=='ESTOP',s)
    # No data clearing: emergency latch persists across process death. Explicitly unlock if needed.
    if status().get('emergencyLocked'):
        tap('人工解除急停');wait('explicit unlock',lambda s:not s['emergencyLocked'])
    for host,device in [(19000,9000),(19001,9001)]:
        adb('emu','redir','del',f'udp:{host}');adb('emu','redir','add',f'udp:{host}:{device}')
    replay=Replay()
    case(replay,'single forward 4s',[(0,'forward')])
    case(replay,'forward to left before 4s',[(0,'forward'),(1.,'left')],late=True)
    case(replay,'rapid directions INFO-only',[(0,'forward'),(.35,'right'),(.7,'backward'),(1.05,'left')])
    case(replay,'switch includes explicit SWITCHED STOP',[(0,'forward'),(.5,'left')],switch_stop=True)
    # Consecutive calls have no intentional pause: fresh INFO/sequence0 after accepted STOP.
    case(replay,'immediate restart right',[(0,'right')])
    case(replay,'CRC and missing packet',[(0,'backward')],corrupt=50,skip=100)
    s=case(replay,'4s without STOP then timeout',[(0,'forward')],final_stop=False)
    check('no STOP timeout leaves invalid window',s.get('channelCount') is None and s['buffered']==0,s)
    tap('急停锁定');wait('latch',lambda s:s['emergencyLocked'] and s['mockApplied']=='ESTOP')
    case(replay,'new flow cannot release ESTOP',[(0,'left')])
    tap('普通停止');s=wait('ordinary stop preserves latch',lambda s:s['emergencyLocked'])
    check('ordinary STOP cannot unlock',s['finalCommand']=='ESTOP' and s['mockApplied']=='ESTOP',s)
    adb('shell','am','force-stop',PKG);adb('shell','am','start','-n',ACTIVITY);tap('开始监听')
    s=wait('persisted emergency',lambda s:s['emergencyLocked'] and s['mockApplied']=='ESTOP')
    check('emergency persists across B process restart',s['emergencyLocked'],s)
    tap('人工解除急停');s=wait('manual unlock',lambda s:not s['emergencyLocked'] and s['mockApplied']=='STOP')
    check('manual unlock stays stopped',s['finalCommand']=='STOP',s)
    long_diagnostic(replay)
    final=status()
    check('Unity transport explicitly simulated',final['transportMode']=='SIMULATED_IN_PROCESS_NO_UDP' and not final['actualUnityUdpVerified'],final)
    (OUT/'final-status.json').write_text(json.dumps(final,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'trace.csv').write_bytes(adb('exec-out','run-as',PKG,'cat','files/stage2-trace.csv'))
    (OUT/'screenshot.png').write_bytes(adb('exec-out','screencap','-p'))
    (OUT/'device.txt').write_bytes(adb('shell','getprop','ro.build.fingerprint'))
    save();tap('停止监听')

if __name__=='__main__':main()
