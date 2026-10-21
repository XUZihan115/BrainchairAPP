"""Summarize recorded production-code outputs only; no alternate EEG classifier."""
import csv,json,hashlib,collections
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'output/stage25';DOC=ROOT/'docs/stage25';DOC.mkdir(parents=True,exist_ok=True)
LABELS=['forward','left','right','backward','stop']
SCORES=['rhoForward','rhoLeft','rhoRight','rhoBackward']
def read(name):
    with (OUT/name).open(encoding='utf-8',newline='') as f:return list(csv.DictReader(f))
def writecsv(path,rows,fields):
    with path.open('w',encoding='utf-8',newline='') as f:w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
def matrix(rows,field='stable'):
    m={label:{p:0 for p in LABELS} for label in LABELS}
    for r in rows:m[r['truth']][r[field].lower()]+=1
    return m
def stats(rows):
    eligible=[r for r in rows if r['rhoForward']];motions=[r for r in eligible if r['truth']!='stop'];rest=[r for r in eligible if r['truth']=='stop']
    return dict(totalTicks=len(rows),eligible=len(eligible),warming=len(rows)-len(eligible),motionWindows=len(motions),
                correct=sum(r['stable'].lower()==r['truth'] for r in motions),wrong=sum(r['stable']!='STOP' and r['stable'].lower()!=r['truth'] for r in motions),
                rejected=sum(r['stable']=='STOP' for r in motions),restWindows=len(rest),restMotion=sum(r['stable']!='STOP' for r in rest),
                stableMatrix=matrix(eligible),rawMatrix=matrix(eligible,'raw'))
def table(m):
    return '\n'.join(['|真实\\输出|前进|左转|右转|后退|停止|','|---|---:|---:|---:|---:|---:|']+['|'+label+'|'+'|'.join(str(m[label][p]) for p in LABELS)+'|' for label in LABELS])
def main():
    rows=read('windows.csv');aligned=read('aligned.csv');selected=json.loads((OUT/'selected.json').read_text())
    scenarios=collections.defaultdict(list)
    for r in rows:scenarios[r['scenario']].append(r)
    profiles={p:stats([r for r in rows if r['scenario'].startswith(p+'-')]) for p in ['cal-default','cal-selected','eval-default','eval-selected','eval-reference']}
    errors=[r for r in rows if r['rhoForward'] and r['stable']!='STOP' and r['stable'].lower()!=r['truth']]
    writecsv(OUT/'errors.csv',errors,rows[0].keys());writecsv(DOC/'errors.csv',errors,rows[0].keys())
    rejections=[r for r in rows if r['rhoForward'] and r['stable']=='STOP'];writecsv(OUT/'rejections.csv',rejections,rows[0].keys())
    segments=[]
    for a in aligned:
        sid=a['segment'];group=[r for r in rows if r['segment']==sid and r['scenario'].startswith(('cal-selected-','eval-selected-')) and r['rhoForward']]
        rho=sorted([float(a[k]) for k in SCORES],reverse=True)
        s=dict(a,alignedArgmax=LABELS[max(range(4),key=lambda k:float(a[SCORES[k]]))],alignedGap=rho[0]-rho[1],
               streamWindows=len(group),correct=sum(r['truth']!='stop' and r['stable'].lower()==r['truth'] for r in group),wrong=sum(r['stable']!='STOP' and r['stable'].lower()!=r['truth'] for r in group),stopped=sum(r['stable']=='STOP' for r in group))
        segments.append(s)
    writecsv(DOC/'segments.csv',segments,segments[0].keys())
    scorestats={}
    for label in LABELS:
        s=[r for r in rows if r['scenario']=='eval-selected-'+label and r['rhoForward']]
        maxes=[];gaps=[]
        for r in s:
            rho=sorted([float(r[k]) for k in SCORES],reverse=True);maxes.append(rho[0]);gaps.append(rho[0]-rho[1])
        scorestats[label]=dict(maxRhoMinMedianMax=np.percentile(maxes,[0,50,100]).tolist(),gapMinMedianMax=np.percentile(gaps,[0,50,100]).tolist())
    first={}
    for name,s in scenarios.items():
        good=[int(r['ms']) for r in s if r['truth']!='stop' and r['stable'].lower()==r['truth']]
        first[name]=min(good) if good else None
    summary=dict(selected=selected,profiles=profiles,scenarioStats={k:stats(v) for k,v in scenarios.items() if not k.startswith('single')},scoreStats=scorestats,firstCorrectMs=first,
                 sourceInputHash=json.loads((OUT/'data/inspection.json').read_text(encoding='utf-8'))['apkSha256'],
                 windowsSha256=hashlib.sha256((OUT/'windows.csv').read_bytes()).hexdigest(),errorWindows=len(errors),realDeviceValidated=False)
    (DOC/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    text=['# Stage 2.5 数值结果（脚本生成）','', '所有滑动窗口高度重叠，循环重复不增加独立试次数；以下不是跨被试准确率。','',
          '候选参数：'+json.dumps(selected,ensure_ascii=False),'', '## 分离标定与评价','', '|配置|运动有效窗|正确运动|错误运动|拒识停止|静息窗|静息误动|','|---|---:|---:|---:|---:|---:|---:|']
    for p,v in profiles.items():text.append('|'+p+'|'+'|'.join(str(v[k]) for k in ['motionWindows','correct','wrong','rejected','restWindows','restMotion'])+'|')
    text+=['','## 候选参数评价集：稳定判决混淆矩阵','',table(profiles['eval-selected']['stableMatrix']),'','## 候选参数评价集：原始门控判决混淆矩阵','',table(profiles['eval-selected']['rawMatrix']),'',
           '## 评价集 CCA 分布','', '|真实标签|最高相关 min/median/max|差值 min/median/max|','|---|---|---|']
    for k,v in scorestats.items():text.append('|'+k+'|'+' / '.join(f'{x:.4f}' for x in v['maxRhoMinMedianMax'])+'|'+' / '.join(f'{x:.4f}' for x in v['gapMinMedianMax'])+'|')
    text+=['','## 每段数据','', '对齐分数仅调用同一 Bandpass/Cca 做独立4秒诊断，无0.5秒预热，不能充当完整识别链路成绩。最后三列来自候选参数连续回放，按窗口结束所在段归属，跨试次窗口仍保留。','',
           '|ID|划分|标签|前/左/右/后 CCA|对齐 argmax|连续正确运动/错误运动/停止|','|---|---|---|---|---|---|']
    for s in segments:text.append(f"|{s['segment']}|{s['split']}|{s['truth']}|"+' / '.join(f'{float(s[k]):.4f}' for k in SCORES)+f"|{s['alignedArgmax']}|{s['correct']}/{s['wrong']}/{s['stopped']}|")
    text+=['','## 边界和降质场景','', '|场景|有效窗|运动正确|错误运动|运动拒识|静息误动|首次正确ms|','|---|---:|---:|---:|---:|---:|---:|']
    for k,v in summary['scenarioStats'].items():
        if k.startswith(('a-wrap','continuous','noise','degraded','flat','released','gap')):
            text.append('|'+k+'|'+'|'.join(str(v[x]) for x in ['eligible','correct','wrong','rejected','restMotion'])+'|'+str(first[k])+'|')
    text+=['','首次正确判决（模拟采样时钟，不是实际运行墙钟）：','', '|评价方向|默认ms|候选ms|','|---|---:|---:|']
    for label in LABELS[:-1]:text.append(f"|{label}|{first['eval-default-'+label]}|{first['eval-selected-'+label]}|")
    (DOC/'RESULTS.md').write_text('\n'.join(text)+'\n',encoding='utf-8')
    print(json.dumps(dict(profiles=profiles,first=first,errors=len(errors)),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
