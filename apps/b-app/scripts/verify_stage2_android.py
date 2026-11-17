"""Real installed APK + UDP synthetic waveforms. Expected labels never go on wire.
Uses isolated test Python with numpy; run after build-stage2.ps1 and APK installation.
"""
import csv
import io
import json
import re
import socket
import struct
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
import stage1_packets as wire

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'output/stage2/android';OUT.mkdir(parents=True,exist_ok=True)
ADB='D:/BrainChair/sdk/platform-tools/adb.exe';PKG='com.yourteam.brainchair.receiver'
ACTIVITY=PKG+'/com.yourteam.brainchair.receiver.ReceiverActivity'
PICKS=['Pz','O1','Oz','O2','P1','P2','POz','PO3','PO4']
ROWS=[];CHECKS=[]

def adb(*args):
    return subprocess.check_output([ADB,'-s','emulator-5554',*args],timeout=30)

def status():
    return json.loads(adb('exec-out','run-as',PKG,'cat','files/stage2-status.json'))

def check(name,ok,detail):
    CHECKS.append(dict(test=name,passed=bool(ok),detail=detail))
    (OUT/'checks.json').write_text(json.dumps(CHECKS,ensure_ascii=False,indent=2),encoding='utf-8')
    print(name, 'PASS' if ok else 'FAIL',flush=True)
    assert ok,name

def screen():
    adb('shell','uiautomator','dump','/sdcard/stage2.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/stage2.xml'))

def tap(root,text):
    node=next(n for n in root.iter('node') if n.attrib.get('text')==text)
    a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds']))
    adb('shell','input','tap',str((a+c)//2),str((b+d)//2))

def samples(case,seconds=8):
    # Independent RNG/phase profile from the offline oracle and Kotlin tests.
    rng=np.random.default_rng(89421)
    n=int(seconds*1000);t=np.arange(n)/1000
    raw=rng.normal(0,1,(n,62))
    frequencies={'forward':12.,'left':8.57,'right':6.67,'backward':5.45}
    for c,name in enumerate(PICKS):
        v=raw[:,wire.NAMES.index(name)]
        if case in frequencies:
            f=frequencies[case]
            v+=5*np.sin(2*np.pi*f*t+c*.37)+2*np.cos(2*np.pi*2*f*t+c*.17)+np.sin(2*np.pi*3*f*t+c*.23)
        elif case=='weak':v+=.02*np.sin(2*np.pi*12*t+c*.37)
        elif case=='ambiguous':v+=8*np.sin(2*np.pi*12*t+c*.37)+8*np.sin(2*np.pi*8.57*t+c*.81)
        elif case=='flat':v[:]=0
    return raw.astype('<f4')

def main():
    adb('shell','am','force-stop',PKG)
    adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
    adb('shell','am','start','-n',ACTIVITY)
    for host,port in [(19000,9000),(19001,9001)]:
        adb('emu','redir','del',f'udp:{host}');adb('emu','redir','add',f'udp:{host}:{port}')
    root=screen();tap(root,'开始监听');time.sleep(1)
    (OUT/'mem-before.txt').write_bytes(adb('shell','dumpsys','meminfo',PKG))
    start=status();target=('127.0.0.1',19000)
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.settimeout(2);wire.discover(sock,('127.0.0.1',19001))
        def run_case(case,raw,new=True,first=0,seq=2,skip=None):
            if new:sock.sendto(wire.info(),target)
            done=threading.Event();sent=[]
            def sender():
                try:
                    begin=time.perf_counter()
                    for off in range(0,len(raw),5):
                        delay=begin+off/1000-time.perf_counter()
                        if delay>0:time.sleep(delay)
                        if skip==off:continue
                        payload=struct.pack('<QQHH',first+off,10_000_000+(first+off)*1000,5,62)+raw[off:off+5].tobytes()
                        sock.sendto(wire.packet(2,seq+off//5,payload),target);sent.append(1)
                finally:done.set()
            th=threading.Thread(target=sender);th.start();observed=[]
            while not done.is_set():
                r=status();r['case']=case;observed.append(r);ROWS.append(r);time.sleep(.15)
            th.join();r=status();r['case']=case;observed.append(r);ROWS.append(r)
            return observed,len(sent),first+len(raw),seq+len(raw)//5
        total_sent=0
        for case in ['forward','left','right','backward','noise','weak','ambiguous','flat']:
            before=status()['received']
            rows,n,first,seq=run_case(case,samples(case));total_sent+=n+1
            tail=[r for r in rows if r['windowEnd']>=6500 and r['scores']]
            expected=case.upper() if case in ['forward','left','right','backward'] else 'STOP'
            check(case+' synthetic classification',bool(tail) and all(r['stable']==expected for r in tail),{'expected':expected,'tail':tail[-3:]})
            if case=='forward':
                tap(root,'暂停显示（接收继续）')
                (OUT/'recognition.png').write_bytes(adb('exec-out','screencap','-p'))
                frozen=screen();tap(frozen,'恢复显示')
                # Screenshot capture can exceed timeout: next case always sends fresh INFO.
            sock.sendto(wire.stop(seq,first),target);total_sent+=1;time.sleep(.35)
            r=status();check(case+' STOP clears decision',r['stable']=='STOP',r)
            check(case+' UDP packet accounting',r['received']-before==n+2,{'sent':n+2,'received':r['received']-before})

        # Change directions without INFO: filter remains continuous and uses the latest 4 seconds.
        rows,n,first,seq=run_case('switch-forward',samples('forward'));total_sent+=n+1
        rows,n,first,seq=run_case('switch-left',samples('left'),new=False,first=first,seq=seq);total_sent+=n
        tail=[r for r in rows if r['windowEnd']>=14500]
        check('continuous direction switch to LEFT',bool(tail) and all(r['stable']=='LEFT' for r in tail),tail[-3:])
        # Missing packet must invalidate immediately, then recover only after a full fresh window.
        rows,n,first,seq=run_case('gap',samples('left'),new=False,first=first,seq=seq,skip=50);total_sent+=n
        early=[r for r in rows if 16100<=r['windowEnd']<20000 or ('窗口' in r['reason'])]
        check('gap forces STOP before refilling',any(r['stable']=='STOP' for r in early),early[:4])
        check('gap recovery',rows[-1]['stable']=='LEFT',rows[-1])
        check('one gap detected',rows[-1]['missingPackets']==1,rows[-1])
        time.sleep(1.3);r=status();check('no data timeout STOP',r['stable']=='STOP' and ('超时' in r['reason'] or '中断' in r['reason']),r)
        rows,n,first,seq=run_case('restart-right',samples('right'));total_sent+=n+1
        check('new stream after timeout',rows[-1]['stable']=='RIGHT',rows[-1])
        sock.sendto(wire.stop(seq,first),target);total_sent+=1;time.sleep(.4)
        final=status();check('all sent datagrams received',final['received']==total_sent,{'sent':total_sent,'received':final['received'],'rejected':final['rejected']})
    (OUT/'mem-after.txt').write_bytes(adb('shell','dumpsys','meminfo',PKG))
    trace=adb('exec-out','run-as',PKG,'cat','files/stage2-trace.csv');(OUT/'trace.csv').write_bytes(trace)
    (OUT/'observations.json').write_text(json.dumps(ROWS,ensure_ascii=False),encoding='utf-8')
    distinct={r['analysisCount']:r for r in ROWS if r['analysisCount'] and r['scores']}
    computations=[r['computeMs'] for r in distinct.values()]
    cycles=[r['periodMs'] for r in distinct.values() if r['periodMs']>0]
    report=dict(durationMs=final['elapsedMs']-start['elapsedMs'],analysisSamples=len(distinct),
                processCpuPercentOneCore=100*(final['processCpuMs']-start['processCpuMs'])/(final['elapsedMs']-start['elapsedMs']),
                computeMeanMs=float(np.mean(computations)),computeP95Ms=float(np.percentile(computations,95)),computeMaxMs=max(computations),
                periodMedianMs=float(np.median(cycles)),periodP95Ms=float(np.percentile(cycles,95)),periodMaxMs=max(cycles),
                javaHeapMinBytes=min(r['heapBytes'] for r in ROWS),javaHeapMaxBytes=max(r['heapBytes'] for r in ROWS),
                sentDatagrams=total_sent,receivedDatagrams=final['received'],realEEGValidated=False)
    (OUT/'performance.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2),flush=True)
    root=screen();tap(root,'停止监听')

if __name__=='__main__':main()
