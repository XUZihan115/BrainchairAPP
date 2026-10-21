"""Independent sender verifies frozen hold/release contract on installed B.
This script is explicitly NOT the provided A APK and cannot establish A release behavior.
"""
import argparse,json,socket,struct,subprocess,threading,time,zipfile
from pathlib import Path
import numpy as np
import verify_stage2_android as base
import stage1_packets as wire

OUT=base.ROOT/'output/ab-integration/contract-sender'
AAPK=base.ROOT/'output/ab-integration/A-original.apk'
def main():
    global OUT
    p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5554');p.add_argument('--target-ip');p.add_argument('--port',type=int,default=9000);p.add_argument('--out',type=Path);args=p.parse_args()
    if not args.target_ip and not args.serial.startswith('emulator-'):p.error('A phone requires explicit --target-ip')
    if args.out:OUT=args.out
    elif args.target_ip:OUT=base.ROOT/'output/ab-integration/phone-contract'/time.strftime('%Y%m%d-%H%M%S')
    base.adb=lambda *a:subprocess.check_output([base.ADB,'-s',args.serial,*a],timeout=30)
    OUT.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(AAPK) as z:
        meta=json.loads(z.read('assets/ssvep/segments.json'));raw=np.frombuffer(z.read('assets/ssvep/segments.bin'),dtype='<f4').reshape(-1,62)
    assert meta['channelNames']==wire.NAMES
    adb=base.adb;adb('shell','am','force-stop','com.fifteen.eegsender');adb('shell','am','force-stop',base.PKG)
    adb('shell','am','start','-n',base.ACTIVITY);base.tap(base.screen(),'开始监听');time.sleep(.6)
    if not args.target_ip:
        adb('emu','redir','del','udp:19000');adb('emu','redir','add',f'udp:19000:{args.port}')
    rows=[];checks=[];cases=[]
    def check(name,ok,detail):
        checks.append(dict(test=name,passed=bool(ok),detail=detail));(OUT/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8');assert ok,name
    def waitStop(expected):
        began=time.perf_counter()
        while time.perf_counter()-began<1.5:
            r=base.status()
            if r['stopPackets']==expected:return r
            time.sleep(.03)
        raise AssertionError('STOP was not received')
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        target=(args.target_ip,args.port) if args.target_ip else ('127.0.0.1',19000)
        for label in ['forward','left','right','backward','stop']:
            group=[s for s in meta['segments'] if s['label']==label]
            indices=np.concatenate([np.arange(s['sampleOffset'],s['sampleOffset']+s['sampleCount']) for s in group])
            before=base.status();sock.sendto(wire.info(),target);done=threading.Event();begin=time.perf_counter()
            def transmit():
                try:
                    for off in range(0,len(indices),5):
                        wait=begin+off/1000-time.perf_counter()
                        if wait>0:time.sleep(wait)
                        payload=struct.pack('<QQHH',off,1_000_000+off*1000,5,62)+raw[indices[off:off+5]].tobytes()
                        sock.sendto(wire.packet(2,2+off//5,payload),target)
                finally:done.set()
            worker=threading.Thread(target=transmit);worker.start();observed=[]
            while not done.is_set():
                r=base.status();r['case']=label;observed.append(r);rows.append(r);time.sleep(.12)
            worker.join();sock.sendto(wire.stop(2+len(indices)//5,len(indices)),target)
            after=waitStop(before['stopPackets']+1);audit=after['stopAudits'][-1]
            check(label+' INFO and blocks',after['infoPackets']==before['infoPackets']+1 and after['blockPackets']==before['blockPackets']+len(indices)//5,dict(info=after['infoPackets']-before['infoPackets'],blocks=after['blockPackets']-before['blockPackets']))
            check(label+' STOP clears final and raw',after['controlOutput']=='STOP' and after['stable']=='STOP' and after['raw']=='STOP' and not after['scores'],after['reason'])
            check(label+' measured internal boundary',audit['listenersNotified']>=1 and audit['receiveToInvalidationMs']>=audit['processingMs']>=0,audit)
            outputs=sorted({r['controlOutput'] for r in observed if r['scores']})
            cases.append(dict(label=label,seconds=len(indices)/1000,outputs=outputs,matchedAtLeastOnce=label.upper() in outputs if label!='stop' else all(o=='STOP' for o in outputs),stopAudit=audit))
            (OUT/'cases.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2),encoding='utf-8')
            print(label,outputs,'socket->stop(ms)',audit['receiveToInvalidationMs'],flush=True)
        # Five short new streams: independent state-reset probe, not EEG recognition scoring.
        for k in range(5):
            before=base.status();sock.sendto(wire.info(),target)
            payload=struct.pack('<QQHH',0,1_000_000,5,62)+raw[:5].tobytes();sock.sendto(wire.packet(2,2,payload),target)
            sock.sendto(wire.stop(3,5),target);after=waitStop(before['stopPackets']+1)
            check('short restart '+str(k),after['infoPackets']==before['infoPackets']+1 and after['controlOutput']=='STOP' and not after['scores'],after['reason'])
        # Genuine motion first, then no STOP and no more samples: not merely an already-stopped timeout.
        before=base.status();sock.sendto(wire.info(),target)
        group=[s for s in meta['segments'] if s['label']=='forward'][:2]
        motion_indices=np.concatenate([np.arange(s['sampleOffset'],s['sampleOffset']+s['sampleCount']) for s in group])
        begin=time.perf_counter()
        for off in range(0,len(motion_indices),5):
            delay=begin+off/1000-time.perf_counter()
            if delay>0:time.sleep(delay)
            payload=struct.pack('<QQHH',off,1_000_000+off*1000,5,62)+raw[motion_indices[off:off+5]].tobytes()
            sock.sendto(wire.packet(2,2+off//5,payload),target)
        time.sleep(.25);active=base.status();check('active before silent loss',active['controlOutput']=='FORWARD',active['controlOutput'])
        time.sleep(1.2);after=base.status()
        check('silence timeout, no STOP',after['controlOutput']=='STOP' and after['stopPackets']==before['stopPackets'] and '超时' in after['reason'],after['reason'])
        sock.sendto(wire.info(),target);payload=struct.pack('<QQHH',0,1_000_000,5,62)+raw[:5].tobytes()
        sock.sendto(wire.packet(2,2,payload),target);sock.sendto(wire.stop(3,5),target);final=waitStop(after['stopPackets']+1)
        check('restart after timeout stays stopped',final['infoPackets']==after['infoPackets']+1 and final['controlOutput']=='STOP' and not final['scores'],final['reason'])
    (OUT/'observations.json').write_text(json.dumps(rows,ensure_ascii=False),encoding='utf-8')
    (OUT/'final-status.json').write_text(json.dumps(final,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'trace.csv').write_bytes(adb('exec-out','run-as',base.PKG,'cat','files/stage2-trace.csv'))
    qemu=adb('shell','getprop','ro.kernel.qemu').decode().strip()
    summary=dict(cases=cases,checks=len(checks),allChecksPassed=all(x['passed'] for x in checks),actualAAppUsed=False,physicalDevices=False if qemu=='1' else None,qemuProperty=qemu,serial=args.serial,target=target,
                 timingBoundary='B socket.receive returned -> all synchronous invalidation callbacks completed; local final STOP by then',
                 maxInternalStopMs=max(a['receiveToInvalidationMs'] for a in final['stopAudits']),stopSamples=len(final['stopAudits']))
    (OUT/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    base.tap(base.screen(),'停止监听');adb('shell','am','force-stop',base.PKG)
if __name__=='__main__':main()
