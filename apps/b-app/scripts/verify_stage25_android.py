"""APK EEG assets -> independent v2 sender -> installed B APK. NOT actual A/B device pairing."""
import argparse,json,re,socket,struct,threading,time
from pathlib import Path
import numpy as np
import verify_stage2_android as base
import stage1_packets as wire

DATA=base.ROOT/'output/stage25'
OUT=DATA/'android'
def main():
    global OUT
    parser=argparse.ArgumentParser();parser.add_argument('--pool',choices=['evaluation','mixed'],default='evaluation');parser.add_argument('--labels',nargs='+',default=['forward','left','right','backward','stop']);args=parser.parse_args()
    if args.pool=='mixed':OUT=DATA/'android-mixed'
    OUT.mkdir(parents=True,exist_ok=True)
    plan=json.loads((DATA/'data/inspection.json').read_text(encoding='utf-8'))
    assert plan['channels']==wire.NAMES, 'Fixture INFO names must match APK channel layout'
    raw=np.fromfile(DATA/'data/segments.bin',dtype='<f4').reshape(-1,62)
    cfg=json.loads((DATA/'selected.json').read_text())
    adb=base.adb;adb('shell','am','force-stop',base.PKG);adb('shell','am','start','-n',base.ACTIVITY)
    root=base.screen()
    edits=[n for n in root.iter('node') if n.attrib.get('class')=='android.widget.EditText']
    for n,value in zip(edits,[cfg['absolute'],cfg['difference']]):
        a,b,c,d=map(int,re.findall(r'\d+',n.attrib['bounds']));adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
        adb('shell','input','keyevent','123');adb('shell','input','keyevent',*(['67']*12));adb('shell','input','text',str(value));adb('shell','input','keyevent','4')
    base.tap(root,'应用调试阈值（未校准）');base.tap(root,'开始监听');time.sleep(1)
    for host,port in [(19000,9000),(19001,9001)]:
        adb('emu','redir','del',f'udp:{host}');adb('emu','redir','add',f'udp:{host}:{port}')
    start=base.status();assert start['absolute']==cfg['absolute'] and start['difference']==cfg['difference']
    (OUT/'mem-before.txt').write_bytes(adb('shell','dumpsys','meminfo',base.PKG))
    observations=[];checks=[];sent=0
    def check(name,value,detail):
        checks.append(dict(test=name,passed=bool(value),detail=detail));(OUT/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
        assert value,name
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.settimeout(2);wire.discover(sock,('127.0.0.1',19001));target=('127.0.0.1',19000)
        for label in args.labels:
            segs=[s for s in plan['plan'] if (args.pool=='mixed' or s['split']=='evaluation') and s['label']==label]
            # Evaluation-only pool repeated three times; never concatenated with calibration samples.
            indices=np.concatenate([np.arange(s['sampleOffset'],s['sampleOffset']+s['sampleCount']) for s in segs]*(3 if args.pool=='evaluation' else 2))
            sock.sendto(wire.info(),target);sent+=1;done=threading.Event();packets=[]
            def sender():
                begin=time.perf_counter()
                try:
                    for off in range(0,len(indices),5):
                        wait=begin+off/1000-time.perf_counter()
                        if wait>0:time.sleep(wait)
                        p=struct.pack('<QQHH',off,1_000_000+off*1000,5,62)+raw[indices[off:off+5]].tobytes()
                        sock.sendto(wire.packet(2,2+off//5,p),target);packets.append(off)
                finally:done.set()
            wall_start=time.perf_counter();th=threading.Thread(target=sender);th.start();case=[]
            while not done.is_set():
                r=base.status();r['truth']=label;r['observedSinceSendStartMs']=(time.perf_counter()-wall_start)*1000
                end=r['windowEnd'];r['segment']=segs[((max(1,end)-1)//4000)%len(segs)]['id']
                case.append(r);observations.append(r);time.sleep(.15)
            th.join();sent+=len(packets)
            # Keep classifier failures as observations, never assert perfect EEG classification.
            scored={r['analysisCount']:r for r in case if r['scores']}
            counts={x:sum(r['stable']==x for r in scored.values()) for x in ['FORWARD','LEFT','RIGHT','BACKWARD','STOP']}
            print(label,counts,flush=True)
            before=time.perf_counter();sock.sendto(wire.stop(2+len(packets),len(indices)),target);sent+=1
            while time.perf_counter()-before<1:
                r=base.status()
                if r['stable']=='STOP' and '结束' in r['reason']:break
                time.sleep(.03)
            check(label+' STREAM_STOP',r['stable']=='STOP',dict(observedUpperBoundMs=(time.perf_counter()-before)*1000,reason=r['reason']))
        # Real-time timeout, distinct from the explicit STOP above.
        sock.sendto(wire.info(),target);sent+=1
        p=struct.pack('<QQHH',0,1_000_000,5,62)+raw[:5].tobytes();sock.sendto(wire.packet(2,2,p),target);sent+=1
        time.sleep(1.3);final=base.status();check('timeout clears session',final['stable']=='STOP' and '超时' in final['reason'],final['reason'])
        check('all datagrams received',final['received']==sent,dict(sent=sent,received=final['received']))
    (OUT/'observations.json').write_text(json.dumps(observations,ensure_ascii=False),encoding='utf-8')
    (OUT/'trace.csv').write_bytes(adb('exec-out','run-as',base.PKG,'cat','files/stage2-trace.csv'))
    (OUT/'mem-after.txt').write_bytes(adb('shell','dumpsys','meminfo',base.PKG))
    scored={r['analysisCount']:r for r in observations if r['scores']};times=[r['computeMs'] for r in scored.values()];periods=[r['periodMs'] for r in scored.values() if r['periodMs']>0]
    report=dict(config=cfg,durationMs=final['elapsedMs']-start['elapsedMs'],processCpuPercentOneCore=100*(final['processCpuMs']-start['processCpuMs'])/(final['elapsedMs']-start['elapsedMs']),
                computeMeanMs=float(np.mean(times)),computeP95Ms=float(np.percentile(times,95)),computeMaxMs=max(times),periodMedianMs=float(np.median(periods)),periodP95Ms=float(np.percentile(periods,95)),periodMaxMs=max(periods),
                heapMinBytes=min(r['heapBytes'] for r in observations),heapMaxBytes=max(r['heapBytes'] for r in observations),sent=sent,received=final['received'],analysisSamples=len(scored),actualAAppUsed=False,physicalDeviceTested=False)
    (OUT/'performance.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2),flush=True)
    root=base.screen();base.tap(root,'停止监听');adb('shell','am','force-stop',base.PKG) # Candidate config is test-only, not persisted.

if __name__=='__main__':main()
