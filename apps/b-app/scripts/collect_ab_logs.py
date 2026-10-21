"""Read-only collection from explicitly named devices; no app data clearing or settings edits."""
import argparse,datetime,json,subprocess
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--b',required=True,help='B adb serial');p.add_argument('--a',help='A adb serial');p.add_argument('--label',default='manual')
    p.add_argument('--adb',default='D:/BrainChair/sdk/platform-tools/adb.exe');p.add_argument('--out',type=Path)
    args=p.parse_args();root=Path(__file__).resolve().parents[1]
    out=args.out or root/'output/ab-integration/phone'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    out.mkdir(parents=True,exist_ok=True);results=[]
    def capture(serial,role,name,*command):
        r=subprocess.run([args.adb,'-s',serial,*command],capture_output=True,timeout=30)
        (out/f'{role}-{name}').write_bytes(r.stdout)
        if r.stderr:(out/f'{role}-{name}.stderr.txt').write_bytes(r.stderr)
        results.append(dict(role=role,file=name,exitCode=r.returncode));return r
    for role,serial,pkg in [('b',args.b,'com.yourteam.brainchair.receiver'),('a',args.a,'com.fifteen.eegsender')]:
        if not serial:continue
        capture(serial,role,'package.txt','shell','dumpsys','package',pkg)
        capture(serial,role,'model.txt','shell','getprop','ro.product.model')
        capture(serial,role,'os.txt','shell','getprop','ro.build.version.release')
        pid=capture(serial,role,'pid.txt','shell','pidof',pkg).stdout.decode().strip().split()
        if pid:capture(serial,role,'logcat.txt','logcat','-d','--pid',pid[0],'-v','threadtime')
        if role=='b':
            for source,dest in [('stage2-status.json','status.json'),('stage2-trace.csv','trace.csv')]:
                capture(serial,role,dest,'exec-out','run-as',pkg,'cat','files/'+source)
    (out/'collection.json').write_text(json.dumps(dict(label=args.label,collectedAt=datetime.datetime.now().astimezone().isoformat(),aSerial=args.a,bSerial=args.b,
                      results=results,physicalDeviceTestClaimed=False,note='Collection is evidence only; fill manual test record before claiming device validation.'),ensure_ascii=False,indent=2),encoding='utf-8')
    print(out)
    if any(r['exitCode']!=0 for r in results):print('Some files unavailable; inspect collection.json. Debug APK and USB debugging are required.')
if __name__=='__main__':main()
