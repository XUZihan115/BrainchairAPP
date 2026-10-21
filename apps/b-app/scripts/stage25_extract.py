"""Read-only APK extraction/validation. No classifier and no A acceptance-probe dependency."""
import argparse, hashlib, json, zipfile
from pathlib import Path
import numpy as np

def main():
    p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    before=hashlib.sha256(a.apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(a.apk) as z:
        meta_bytes=z.read('assets/ssvep/segments.json');raw=z.read('assets/ssvep/segments.bin')
    m=json.loads(meta_bytes); names=m['channelNames']; segments=m['segments']
    assert m['sampleRate']==1000 and m['channelCount']==62 and len(names)==len(set(names))==62
    assert len(raw)==m['totalSamples']*62*4==17856000
    x=np.frombuffer(raw,dtype='<f4').reshape(-1,62);assert np.isfinite(x).all()
    counts={};end=0; plan=[]
    for i,s in enumerate(segments):
        assert s['sampleOffset']==end and s['sampleCount']==4000 and s['channelCount']==62
        label=s['label'];k=counts.get(label,0);counts[label]=k+1
        split='calibration' if k<(1 if label=='stop' else 2) else 'evaluation'
        plan.append(dict(id=i,split=split,**s));end+=s['sampleCount']
    assert end==72000 and counts==dict(forward=4,left=4,right=4,backward=4,stop=2)
    a.out.mkdir(parents=True,exist_ok=True)
    (a.out/'segments.bin').write_bytes(raw);(a.out/'segments.json').write_bytes(meta_bytes)
    (a.out/'names.txt').write_text(','.join(names),encoding='utf-8')
    (a.out/'plan.tsv').write_text('\n'.join('\t'.join(str(s[k]) for k in ['id','split','label','source','sampleOffset','sampleCount']) for s in plan)+'\n',encoding='utf-8')
    report=dict(apk=str(a.apk),apkSha256=before,metadataSha256=hashlib.sha256(meta_bytes).hexdigest(),binarySha256=hashlib.sha256(raw).hexdigest(),
                byteCount=len(raw),shape=list(x.shape),seconds=len(x)/1000,format='little-endian float32 time-major per supplied protocol',
                allFinite=True,min=float(x.min()),max=float(x.max()),mean=float(x.mean()),std=float(x.std()),
                declaredSource=m['source'],originalMatVerified=False,counts=counts,channels=names,plan=plan,
                splitRule='Before score inspection: first 2 trials per motion + first rest for calibration; remaining trials for evaluation. No cross-split filter state.',
                caveat='One preselected subject/session, 4s rest per split. Repeated windows are not independent observations.')
    assert hashlib.sha256(a.apk.read_bytes()).hexdigest()==before
    (a.out/'inspection.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['plan','channels']},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
