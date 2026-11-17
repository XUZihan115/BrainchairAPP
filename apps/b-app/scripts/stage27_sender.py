"""Independent v2 A-interaction driver. No acceptance_probe or production CCA imported.

Normal clicks always have a 4s maximum; scheduled new clicks cancel the old stream
and start another full cycle. --switch-stop adds optional reason=3 before a switch;
default tests the stricter INFO-only switch boundary. The A APK is read only.

Example (B phone on the LAN):
  python scripts/stage27_sender.py --host 192.168.1.20 --click forward@0 --click left@1.5
  python scripts/stage27_sender.py --host 192.168.1.20 --click right@0 --click backward@0.4 --click left@0.8
"""
import argparse
import json
import socket
import struct
import time
import zipfile
from pathlib import Path
import stage1_packets as wire

DEFAULT_APK=Path('app-debug.apk.1')

class Replay:
    def __init__(self, apk=DEFAULT_APK):
        with zipfile.ZipFile(apk) as z:
            self.meta=json.loads(z.read('assets/ssvep/segments.json'))
            self.data=z.read('assets/ssvep/segments.bin')
        self.rate=self.meta['sampleRate'];self.names=self.meta['channelNames'];self.ch=len(self.names)
        assert self.rate==1000 and self.ch==62
        assert len(self.data)==self.meta['totalSamples']*self.ch*4
    def info(self):
        n=','.join(self.names).encode()
        return wire.packet(1,1,struct.pack('<IHBBH',self.rate,self.ch,1,0,len(n))+n)
    def packets(self,label):
        segment=next(s for s in self.meta['segments'] if s['label']==label)
        assert segment['sampleCount']==4000 and segment['channelCount']==self.ch
        begin=segment['sampleOffset']*self.ch*4
        # Labels select test input only. Only raw floats and frozen v2 fields enter B.
        return [self.data[begin+i*self.ch*4:begin+(i+5)*self.ch*4] for i in range(0,4000,5)]

def run(replay,target,clicks,emit=lambda e:None,switch_stop=False,final_stop=True,late=False,corrupt=None,skip=None):
    origin=time.time_ns()//1000;start=time.perf_counter();sockets=[];previous=None
    def event(kind,**extra):emit(dict(event=kind,hostNs=time.perf_counter_ns(),offsetMs=(time.perf_counter()-start)*1000,**extra))
    try:
        for index,(at,label) in enumerate(clicks):
            delay=start+at-time.perf_counter()
            if delay>0:time.sleep(delay)
            if previous is not None and switch_stop:
                s,seq,first=previous;s.sendto(wire.packet(3,seq,struct.pack('<QB',first,3)),target)
                event('SWITCH_STOP',label=label)
            s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);sockets.append(s);s.sendto(replay.info(),target)
            event('STREAM_INFO',label=label)
            if previous is not None and late:
                old,_,_=previous
                old.sendto(replay.info(),target)  # late old INFO must not take ownership back
                old.sendto(wire.stop(999,4000),target)
                event('late old INFO/STOP injected')
            cycle=time.perf_counter();duration=min(4.0,clicks[index+1][0]-at) if index+1<len(clicks) else 4.0
            frames=replay.packets(label);sent=0;first=0;seq=2
            for k,payload in enumerate(frames):
                if k*.005>=duration:break
                delay=cycle+k*.005-time.perf_counter()
                if delay>0:time.sleep(delay)
                packet=wire.packet(2,seq,struct.pack('<QQHH',first,origin+index*10_000_000+first*1000,5,replay.ch)+payload)
                if corrupt==k:packet=packet[:-1]+bytes([packet[-1]^1])
                if skip!=k:s.sendto(packet,target);sent+=1
                first+=5;seq+=1
            delay=cycle+duration-time.perf_counter()
            if delay>0:time.sleep(delay)
            event('EEG end',label=label,sentBlocks=sent,samples=first)
            previous=(s,seq,first)
        if final_stop:
            s,seq,first=previous;s.sendto(wire.stop(seq,first),target);event('STREAM_STOP',seq=seq,nextIndex=first)
    finally:
        for s in sockets:s.close()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host',required=True);p.add_argument('--port',type=int,default=9000)
    p.add_argument('--apk',type=Path,default=DEFAULT_APK)
    p.add_argument('--click',action='append',default=[],metavar='DIRECTION@SECONDS')
    p.add_argument('--switch-stop',action='store_true');p.add_argument('--no-final-stop',action='store_true')
    p.add_argument('--inject-old',action='store_true');p.add_argument('--corrupt-block',type=int);p.add_argument('--skip-block',type=int)
    a=p.parse_args();clicks=[]
    for item in a.click or ['forward@0']:
        label,at=item.split('@');clicks.append((float(at),label))
    assert clicks[0][0]==0 and all(b[0]>a[0] for a,b in zip(clicks,clicks[1:]))
    assert all(d in ['forward','backward','left','right','stop'] for _,d in clicks)
    assert all(b[0]-a[0]<=4 for a,b in zip(clicks,clicks[1:])), 'For gaps >4s, run separate clicks.'
    run(Replay(a.apk),(a.host,a.port),clicks,lambda e:print(json.dumps(e,ensure_ascii=False),flush=True),
        a.switch_stop,not a.no_final_stop,a.inject_old,a.corrupt_block,a.skip_block)

if __name__=='__main__':main()
