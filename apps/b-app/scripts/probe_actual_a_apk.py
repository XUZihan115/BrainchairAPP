"""Exercise unmodified A APK against B on ONE emulator. Never an A/B phone result.
Only A UI chooses test cases; B receives raw EEG, never expected labels.
"""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
import verify_stage2_android as base

OUT=base.ROOT/'output/ab-integration/actual-a';APKG='com.fifteen.eegsender'
ADB=base.adb
ROWS=[];CASES=[]
def ui(name):
    for _ in range(3):
        result=ADB('shell','uiautomator','dump','/sdcard/actual-a.xml')
        if b'dumped' in result:
            raw=ADB('shell','cat','/sdcard/actual-a.xml');(OUT/f'{name}.xml').write_bytes(raw);return ET.fromstring(raw)
        time.sleep(.4)
    raise RuntimeError('UI dump unavailable')
def node(root,suffix):
    return next(n for n in root.iter('node') if n.attrib.get('resource-id','').endswith('/'+suffix))
def center(n):
    a,b,c,d=map(int,re.findall(r'\d+',n.attrib['bounds']));return str((a+c)//2),str((b+d)//2)
def tap(n):ADB('shell','input','tap',*center(n))
def configure(ip):
    ADB('shell','input','swipe','980','650','980','2050','500')
    root=ui('a-settings');tap(node(root,'etIp'))
    ADB('shell','input','keyevent','123');ADB('shell','input','keyevent',*(['67']*40))
    if ip:ADB('shell','input','text',ip)
    ADB('shell','input','keyevent','4')
    ADB('shell','input','swipe','980','2100','980','750','600')
    return ui('a-buttons')
def observe(label,seconds):
    begin=time.perf_counter();rows=[]
    while time.perf_counter()-begin<seconds:
        r=base.status();r.update(case=label,observedSinceActionMs=(time.perf_counter()-begin)*1000);rows.append(r);ROWS.append(r);time.sleep(.12)
    (OUT/'observations.json').write_text(json.dumps(ROWS,ensure_ascii=False),encoding='utf-8');return rows
def case(label,before,rows):
    end=rows[-1];v=dict(case=label,infoDelta=end['infoPackets']-before['infoPackets'],blocksDelta=end['blockPackets']-before['blockPackets'],
       stopDelta=end['stopPackets']-before['stopPackets'],peakSampleIndex=max(r['nextIndex'] for r in rows),
       computedWindows=max(r['analysisCount'] for r in rows)-before['analysisCount'],outputs=sorted({r['controlOutput'] for r in rows}),
       finalReason=end['reason'],rejected=end['rejected'],sources=sorted({r.get('source','') for r in rows if r.get('source')}))
    CASES.append(v);(OUT/'cases.json').write_text(json.dumps(CASES,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(v,ensure_ascii=True),flush=True)
def main():
    global OUT
    parser=argparse.ArgumentParser();parser.add_argument('--focused',action='store_true',help='Only rapid restart and early touch release');args=parser.parse_args()
    if args.focused:OUT=base.ROOT/'output/ab-integration/actual-a-fixed'
    OUT.mkdir(parents=True,exist_ok=True)
    ADB('shell','am','force-stop',APKG);ADB('shell','am','force-stop',base.PKG)
    ADB('shell','am','start','-n',base.ACTIVITY);base.tap(base.screen(),'开始监听');time.sleep(.7)
    ADB('shell','am','start','-n',APKG+'/.MainActivity');time.sleep(.5)
    # A rejects loopback as outside its network. Both apps share this emulator's actual NIC IP.
    nic=ADB('shell','ip','-o','-4','addr','show','eth0').decode()
    target_ip=re.search(r'inet\s+([\d.]+)/',nic).group(1)
    root=configure(target_ip)
    (OUT/'a-screen.png').write_bytes(ADB('exec-out','screencap','-p'))
    if args.focused:
        before=base.status();tap(node(root,'btnForward'));observe('rapid-before',.8);tap(node(root,'btnLeft'))
        case('rapid-direction-new-stream',before,observe('rapid-after',6.1))
        before=base.status();x,y=center(node(root,'btnForward'))
        proc=subprocess.Popen([base.ADB,'-s','emulator-5554','shell','input','swipe',x,y,x,y,'1200'],stdout=subprocess.DEVNULL)
        rows=observe('early-touch-release',6.2);proc.wait(timeout=5);case('early-touch-release',before,rows)
        (OUT/'b-trace.csv').write_bytes(ADB('exec-out','run-as',base.PKG,'cat','files/stage2-trace.csv'))
        ADB('shell','am','force-stop',APKG);ADB('shell','am','force-stop',base.PKG)
        (OUT/'scope.json').write_text(json.dumps(dict(actualAApk=True,actualBApk=True,sameEmulator=True,physicalPhones=False,AUnmodified=True,thresholdsChanged=False),indent=2),encoding='utf-8')
        return
    for name,suffix in [('forward','btnForward'),('left','btnLeft'),('right','btnRight'),('backward','btnBackward'),('rest-button','btnStop')]:
        before=base.status();tap(node(root,suffix));case('single-click-'+name,before,observe(name,6.1))
    # A owns stream lifecycle; force-stop emulates abrupt application loss, NOT actual network loss.
    before=base.status();tap(node(root,'btnForward'));observe('rapid-before',.8);tap(node(root,'btnLeft'))
    case('rapid-direction-new-stream',before,observe('rapid-after',6.1))
    # A 7s touch: observe whether packets begin on DOWN or UP. Same coordinates => no swipe.
    before=base.status();x,y=center(node(root,'btnForward'))
    proc=subprocess.Popen([base.ADB,'-s','emulator-5554','shell','input','swipe',x,y,x,y,'7000'],stdout=subprocess.DEVNULL)
    held=observe('hold-seven-seconds',7.5);proc.wait(timeout=5)
    later=observe('after-seven-second-release',5.8);case('hold-and-release',before,held+later)
    # Disconnect A while it is sending. B must expire even without STOP.
    before=base.status();tap(node(root,'btnForward'));observe('before-a-process-loss',.8);ADB('shell','am','force-stop',APKG)
    case('a-process-loss',before,observe('a-process-loss',1.6))
    ADB('shell','am','start','-n',APKG+'/.MainActivity');root=configure('')
    before=base.status();tap(node(root,'btnForward'));rows=observe('a-auto-discovery',7)
    case('automatic-discovery',before,rows);ui('a-after-discovery')
    (OUT/'b-trace.csv').write_bytes(ADB('exec-out','run-as',base.PKG,'cat','files/stage2-trace.csv'))
    for pkg,label in [(APKG,'a'),(base.PKG,'b')]:
        pid=ADB('shell','pidof',pkg).decode().strip().split()[0]
        (OUT/f'{label}-logcat.txt').write_bytes(ADB('logcat','-d','--pid',pid,'-v','threadtime'))
    ADB('shell','am','force-stop',APKG);ADB('shell','am','force-stop',base.PKG)
    (OUT/'scope.json').write_text(json.dumps(dict(actualAApk=True,actualBApk=True,sameEmulator=True,physicalPhones=False,AUnmodified=True,thresholdsChanged=False),indent=2),encoding='utf-8')
if __name__=='__main__':
    # Temporary emulator viewport makes all original A buttons accessible; restore on every exit.
    display=ADB('shell','wm','size').decode();override=re.search(r'Override size:\s*(\d+x\d+)',display)
    try:
        ADB('shell','wm','size','1080x3000');main()
    finally:ADB('shell','wm','size',override.group(1) if override else 'reset')
