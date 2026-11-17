"""Independent SciPy/NumPy oracle; never imported by Android production code.
Generate deterministic synthetic input + reference SOS output checkpoints and SVD CCA scores.
"""
import argparse
import json
from pathlib import Path
import numpy as np
import scipy
from scipy import signal, linalg

FREQS = [12.00, 8.57, 6.67, 5.45]
LABELS = ['forward', 'left', 'right', 'backward']

def cca(x, y):
    x = x - x.mean(axis=0)
    y = y - y.mean(axis=0)
    qx = linalg.orth(x, rcond=1e-10)
    qy = linalg.orth(y, rcond=1e-10)
    return float(linalg.svdvals(qx.T @ qy)[0]) if qx.shape[1] else 0.0

def generate(out):
    out.mkdir(parents=True, exist_ok=True)
    fs = 1000
    n = 6000
    t = np.arange(n)/fs
    rng = np.random.default_rng(20261019)
    sos = signal.butter(2, [4.,45.], btype='bandpass', fs=fs, output='sos')
    refs = [np.column_stack([fn(2*np.pi*f*h*np.arange(4000)/fs) for h in (1,2,3) for fn in (np.sin,np.cos)]) for f in FREQS]
    rows = []
    for case in LABELS + ['noise','weak','ambiguous','flat']:
        raw = rng.normal(0, 1, (n,9))
        for c in range(9):
            if case in LABELS:
                f=FREQS[LABELS.index(case)]
                raw[:,c] += 5*np.sin(2*np.pi*f*t+c*.31) + 2*np.cos(2*np.pi*2*f*t+c*.22) + np.sin(2*np.pi*3*f*t+c*.19)
            elif case=='weak':
                raw[:,c] += .02*np.sin(2*np.pi*12*t+c*.31)
            elif case=='ambiguous':
                raw[:,c] += 8*np.sin(2*np.pi*12*t+c*.31) + 8*np.sin(2*np.pi*8.57*t+c*.79)
            elif case=='flat':
                raw[:,c] = 0
        raw = raw.astype('<f4')
        raw.tofile(out/(case+'.f32'))
        filtered = signal.sosfilt(sos,raw.astype(float),axis=0)
        scores=[cca(filtered[-4000:],y) for y in refs]
        top=sorted(scores,reverse=True)
        decision=LABELS[int(np.argmax(scores))] if top[0]>.65 and top[0]-top[1]>.15 else 'stop'
        checkpoints=filtered[[0,1,4,199,499,999,3999,5999],:].ravel().tolist()
        (out/(case+'.txt')).write_text(','.join(map(repr,scores))+'\n'+','.join(map(repr,checkpoints))+'\n',encoding='ascii')
        rows.append(dict(case=case,scores=scores,expected=decision))
    for fs in [100,250,500,1000,2000,4000]:
        x=np.zeros((1024,1));x[0]=1
        filt=signal.sosfilt(signal.butter(2,[4.,45.],btype='bandpass',fs=fs,output='sos'),x,axis=0)
        filt.astype('<f8').tofile(out/f'impulse-{fs}.f64')
    report={'numpy':np.__version__,'scipy':scipy.__version__,'filter':'butter(2,[4,45],bandpass): final order 4, two SOS',
            'cca':'centered SVD orthogonal bases, largest singular value','seed':20261019,'realEEGValidated':False,'cases':rows}
    (out/'oracle.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True)
    generate(ap.parse_args().output)
