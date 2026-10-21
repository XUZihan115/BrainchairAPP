"""Summarize existing Stage 3 evidence only. Does not build, replay, tune, or alter results."""
import argparse
import csv
import hashlib
import json
import statistics
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def percentile(values, p):
    if not values:return None
    seq=sorted(values);index=(len(seq)-1)*p;lo=int(index);hi=min(lo+1,len(seq)-1)
    return seq[lo]+(seq[hi]-seq[lo])*(index-lo)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,default=ROOT/'output/stage3')
    a=parser.parse_args();out=a.out;units=[]
    for path in sorted((out/'junit').glob('TEST-*.Stage3*Test.xml')):
        suite=ET.parse(path).getroot();units.append(dict(name=suite.attrib['name'],tests=int(suite.attrib['tests']),
            failures=int(suite.attrib['failures']),errors=int(suite.attrib['errors']),skipped=int(suite.attrib['skipped']),seconds=float(suite.attrib['time'])))
    assert len(units)==4 and all(s['failures']==s['errors']==s['skipped']==0 for s in units)
    report=json.loads((out/'emulator/report.json').read_text(encoding='utf-8'))
    assert all(c['passed'] for c in report['checks'])
    assert any(c['check']=='fresh restart safe STOP' for c in report['checks'])
    assert any(c['check']=='actual UDP uses delivery thread' for c in report['checks'])
    receipts=[json.loads(line) for line in (out/'emulator/peer/datagrams.jsonl').read_text().splitlines()]
    assert not any('invalid' in r for r in receipts)
    rows=[];events=report['events'];motion_codes=dict(forward=1,left=3)
    for i,event in enumerate(events):
        if event['event'] not in ('EEG flow start','EEG no-STOP test start'):continue
        end=next(e for e in events[i+1:] if e['event']=='EEG source end');start=event['ns'];stop=end['ns']
        direction=end['label'];code=motion_codes[direction]
        segment=[r for r in receipts if start<=r['ns']<stop]
        correct=[r for r in segment if r.get('command')==code]
        after=next((r for r in receipts if r['ns']>=stop and r.get('command')==5),None)
        rows.append(dict(label=direction,hasStreamStop=end['stop'],firstCorrectPeerMs=(correct[0]['ns']-start)/1e6 if correct else None,
            sourceEndToFirstStopPeerMs=(after['ns']-stop)/1e6 if after else None,
            lastCommandBeforeEnd=segment[-1]['command'] if segment else None,wrongDirectionPackets=sum(r.get('command') in set(range(1,5))-{code} for r in segment)))
    samples=json.loads((out/'emulator/samples.json').read_text(encoding='utf-8'))
    threads=sorted({s['unitySenderThread'] for s in samples if s.get('unitySentPackets',0)>0 and s.get('unitySenderThread')})
    assert threads==['control-delivery'],threads
    measured={}
    for s in samples:
        if s.get('analysisCount',0)>0 and s.get('scores'):measured[(s['generation'],s['analysisCount'])]=s
    analyses=list(measured.values());compute=[s['computeMs'] for s in analyses];cpu=[s['analysisCpuMs'] for s in analyses]
    periods=[s['periodMs'] for s in analyses if 0<s['periodMs']<1000]
    emergency=[r for r in receipts if r.get('command')==6]
    burst_gaps=[(b['ns']-a['ns'])/1e6 for a,b in zip(emergency,emergency[1:])]
    apk=out/'BrainChair-B-stage3.apk'
    result=dict(scope='Actual JVM/Android-emulator UDP against independent peer; no real A/B phones or Unity runtime.',
        unitSuites=units,unitTests=sum(s['tests'] for s in units),emulatorAssertions=len(report['checks']),
        restartValidation='Requires a newly written state file, fresh actual feedback and safe STOP after process restart; no stale-file-only assertion.',
        actualUdpSenderThreads=threads,
        emulatorReceivedDatagrams=len(receipts),emulatorInvalidDatagrams=0,estopPackets=len(emergency),estopBurstGapsMs=burst_gaps,
        emulatorEegTimelines=rows,emulatorUniqueObservedAnalyses=len(analyses),
        computeMedianMs=statistics.median(compute),computeP95Ms=percentile(compute,.95),
        threadCpuMedianMs=statistics.median(cpu),threadCpuP95Ms=percentile(cpu,.95),
        decisionPeriodMedianMs=statistics.median(periods),decisionPeriodP95Ms=percentile(periods,.95),
        sampledJavaHeapMaxBytes=max(s.get('heapBytes',0) for s in samples),
        memoryScope='Sampled Java heap during run; meminfo.txt is a separate post-restart PSS observation, not peak memory.',
        apkBytes=apk.stat().st_size,apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest())
    target=ROOT/'docs/stage3/test-results.json';target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
