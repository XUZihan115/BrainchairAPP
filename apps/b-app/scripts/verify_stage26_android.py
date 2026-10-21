"""Installed B APK safety smoke test using actual EEG assets; no A phone involved."""
import json,socket,struct,threading,time
import numpy as np
import verify_stage2_android as base
import stage1_packets as wire
OUT=base.ROOT/'output/stage26/android'
def main():
    OUT.mkdir(parents=True,exist_ok=True);meta=json.loads((OUT.parent/'data/inspection.json').read_text(encoding='utf-8'))
    assert meta['channels']==wire.NAMES
    raw=np.fromfile(OUT.parent/'data/segments.bin',dtype='<f4').reshape(-1,62)
    adb=base.adb;adb('shell','am','force-stop',base.PKG);adb('shell','am','start','-n',base.ACTIVITY)
    base.tap(base.screen(),'开始监听');time.sleep(.6)
    for host,port in [(19000,9000),(19001,9001)]:
        adb('emu','redir','del',f'udp:{host}');adb('emu','redir','add',f'udp:{host}:{port}')
    checks=[];rows=[];sent=0;seq=2;first=0;start=base.status()
    assert start['absolute']==.65 and start['difference']==.15
    def check(name,ok,detail):
        checks.append(dict(test=name,passed=bool(ok),detail=detail));(OUT/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8');assert ok,name
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        target=('127.0.0.1',19000)
        def send(p):
            nonlocal sent;sock.sendto(p,target);sent+=1
        def new():
            nonlocal seq,first;send(wire.info());seq=2;first=0
        def stream(label):
            nonlocal seq,first
            segs=[s for s in meta['plan'] if s['split']=='evaluation' and s['label']==label]
            indices=np.concatenate([np.arange(s['sampleOffset'],s['sampleOffset']+4000) for s in segs]*(2 if label=='stop' else 1))
            done=threading.Event();begin=time.perf_counter();initial=first;initial_seq=seq
            def transmit():
                try:
                    for off in range(0,len(indices),5):
                        wait=begin+off/1000-time.perf_counter()
                        if wait>0:time.sleep(wait)
                        payload=struct.pack('<QQHH',initial+off,1_000_000+(initial+off)*1000,5,62)+raw[indices[off:off+5]].tobytes()
                        send(wire.packet(2,initial_seq+off//5,payload))
                finally:done.set()
            thread=threading.Thread(target=transmit);thread.start()
            while not done.is_set():
                r=base.status();r.update(truth=label,episode=episode,sinceStartMs=(time.perf_counter()-begin)*1000);rows.append(r);time.sleep(.1)
            thread.join();first+=len(indices);seq+=len(indices)//5
            return base.status()
        def awaitStop(name,reasonToken='结束'):
            began=time.perf_counter()
            while time.perf_counter()-began<1:
                r=base.status()
                if r['controlOutput']=='STOP' and r['stable']=='STOP' and reasonToken in r['reason']:break
                time.sleep(.02)
            check(name,r['controlOutput']=='STOP' and r['stable']=='STOP' and reasonToken in r['reason'],dict(observedMs=(time.perf_counter()-began)*1000,reason=r['reason'],controlReason=r['controlReason']))
        episode='no-stop-to-rest';new();r=stream('forward');check('active before rest',r['controlOutput']=='FORWARD',r['controlOutput'])
        stream('stop');send(wire.stop(seq,first));awaitStop('valid STREAM_STOP')
        episode='crc';new();r=stream('forward');check('active before CRC',r['controlOutput']=='FORWARD',r['controlOutput'])
        p=struct.pack('<QQHH',first,1_000_000+first*1000,5,62)+raw[:5].tobytes();bad=bytearray(wire.packet(2,seq,p));bad[35]^=1;send(bad);awaitStop('current source CRC final output STOP','')
        check('CRC counted',base.status()['rejected'].get('crc')==1,base.status()['rejected'])
        episode='restart-timeout';new();r=stream('forward');check('restart can classify fresh window',r['controlOutput']=='FORWARD',r['controlOutput'])
        time.sleep(1.3);r=base.status();check('timeout final output STOP',r['controlOutput']=='STOP' and '超时' in r['reason'],r['reason'])
        episode='restart-stop';new();r=stream('forward');check('restart after timeout',r['controlOutput']=='FORWARD',r['controlOutput'])
        send(wire.stop(seq,first));awaitStop('STOP after restart')
        final=base.status();check('all datagrams accounted',final['received']==sent,dict(sent=sent,received=final['received']))
    (OUT/'observations.json').write_text(json.dumps(rows,ensure_ascii=False),encoding='utf-8')
    (OUT/'trace.csv').write_bytes(adb('exec-out','run-as',base.PKG,'cat','files/stage2-trace.csv'))
    (OUT/'meminfo.txt').write_bytes(adb('shell','dumpsys','meminfo',base.PKG))
    scored={r['analysisCount']:r for r in rows if r['scores']};cycles=[r['periodMs'] for r in scored.values() if r['periodMs']>0]
    rest=[r for r in rows if r['episode']=='no-stop-to-rest' and r['truth']=='stop'];wrong=[r for r in rest if r['controlOutput']!='STOP']
    perf=dict(durationMs=final['elapsedMs']-start['elapsedMs'],cpuPercentOneCore=100*(final['processCpuMs']-start['processCpuMs'])/(final['elapsedMs']-start['elapsedMs']),
              medianPeriodMs=float(np.median(cycles)),p95PeriodMs=float(np.percentile(cycles,95)),maxPeriodMs=max(cycles),
              maxComputeMs=max(r['computeMs'] for r in scored.values()),sent=sent,received=final['received'],
              noStopRestWrongObservedRows=len(wrong),lastRestMotionObservedMs=max([r['sinceStartMs'] for r in wrong] or [0]),
              physicalDeviceValidated=False,safetyAccepted=not bool(wrong))
    (OUT/'performance.json').write_text(json.dumps(perf,indent=2),encoding='utf-8');print(json.dumps(perf,indent=2),flush=True)
    base.tap(base.screen(),'停止监听');adb('shell','am','force-stop',base.PKG)
if __name__=='__main__':main()
