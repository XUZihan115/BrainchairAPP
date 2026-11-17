"""Trial-unit safety acceptance. No fitting; historic split reused as regression, not new unseen data."""
import csv,json,collections,hashlib
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'output/stage26';DOC=ROOT/'docs/stage26'
LABELS=['forward','left','right','backward','stop'];SCORES=['rhoForward','rhoLeft','rhoRight','rhoBackward']
def read(path):
    with path.open(encoding='utf-8',newline='') as f:return list(csv.DictReader(f))
def write(path,rows,fields=None):
    with path.open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields or list(rows[0]));w.writeheader();w.writerows(rows)
def matrix(rows,field='output'):
    m={k:{p:0 for p in LABELS} for k in LABELS}
    for r in rows:m[r['truth']][r[field].lower()]+=1
    return m
def table(m):
    return '\n'.join(['|真实/输出|前|左|右|后|停止|','|---|---:|---:|---:|---:|---:|']+['|'+k+'|'+'|'.join(str(m[k][p]) for p in LABELS)+'|' for k in LABELS])
def main():
    DOC.mkdir(parents=True,exist_ok=True)
    versions={k:read(OUT/k/'trial-windows.csv') for k in ['before','after']}
    # Normal-waveform before/after output must not be advertised as improved by unchanged CCA.
    comparable=['episode','profile','trial','split','truth','ms','event','windowEnd',*SCORES,'winner','raw','stable','output','reason']
    same=len(versions['before'])==len(versions['after']) and all(all(a[k]==b[k] for k in comparable if k!='reason') for a,b in zip(*versions.values()))
    trials=[];errors=[];transitions=[];summary={}
    for version,allrows in versions.items():
        rows=[r for r in allrows if r['event']=='window'];groups=collections.defaultdict(list)
        for r in rows:groups[(r['profile'],r['episode'])].append(r)
        for (profile,episode),g in groups.items():
            if episode.startswith(('single-','repeat-')):
                truth=g[0]['truth'];wrong=[r for r in g if r['output']!='STOP' and r['output'].lower()!=truth]
                correct=[r for r in g if truth!='stop' and r['output'].lower()==truth]
                outcome=wrong[0]['output'].lower() if wrong else truth if correct else 'stop'
                first=lambda field:next((int(r['ms']) for r in g if truth!='stop' and r[field].lower()==truth),None)
                valid=[r for r in g if r['rhoForward']]
                trials.append(dict(version=version,profile=profile,mode=episode.split('-')[0],trial=g[0]['trial'],split=g[0]['split'],truth=truth,output=outcome,
                     ticks=len(g),eligible=len(valid),firstGatedMs=first('raw'),firstStableMs=first('stable'),firstOutputMs=first('output'),
                     correctTicks=len(correct),wrongTicks=len(wrong),stopTicks=sum(r['output']=='STOP' for r in g),
                     eligibleStopTicks=sum(r['output']=='STOP' for r in valid),restMotion=truth=='stop' and bool(wrong),everWrong=bool(wrong)))
            if episode in ['switch-without-stop','active-to-rest']:
                for boundary,truth in ([(8000,'stop')] if episode=='active-to-rest' else [(8000,'left'),(16000,'right'),(24000,'backward'),(32000,'stop')]):
                    sub=[r for r in g if boundary<int(r['ms'])<=boundary+8000]
                    firststop=next((int(r['ms'])-boundary for r in sub if r['output']=='STOP'),None)
                    firstcorrect=next((int(r['ms'])-boundary for r in sub if r['output'].lower()==truth),None)
                    lastmove=max([int(r['ms'])-boundary for r in sub if r['output']!='STOP'] or [0])
                    sustained=next((int(r['ms'])-boundary for r in sub if all(t['output']=='STOP' for t in sub if int(t['ms'])>=int(r['ms']))),None) if truth=='stop' else None
                    prior=next(r['output'] for r in g if int(r['ms'])==boundary)
                    transitions.append(dict(version=version,profile=profile,scenario=episode,to=truth,beforeOutput=prior,firstStopMs=firststop,firstCorrectStableMs=firstcorrect,
                          sustainedStopMs=sustained,lastMotionMs=lastmove if truth=='stop' else '',wrongTicks=sum(r['output']!='STOP' and r['output'].lower()!=truth for r in sub)))
        errors += [dict(version=version,**r) for r in rows if r['output']!='STOP' and r['output'].lower()!=r['truth']]
        summary[version]={}
        for profile in ['default','candidate']:
            summary[version][profile]={}
            for mode in ['single','repeat']:
                t=[r for r in trials if r['version']==version and r['profile']==profile and r['mode']==mode and r['split']=='evaluation']
                motion=[r for r in t if r['truth']!='stop'];rest=[r for r in t if r['truth']=='stop']
                summary[version][profile][mode]=dict(matrix=matrix(t),units=len(t),motionUnits=len(motion),
                    rejectedMotionUnits=sum(r['output']=='stop' for r in motion),wrongMotionUnits=sum(r['everWrong'] for r in motion),
                    restUnits=len(rest),restFalseMotionUnits=sum(r['restMotion'] for r in rest),
                    eligibleMotionTicks=sum(r['eligible'] for r in motion),eligibleRejectedTicks=sum(r['eligibleStopTicks'] for r in motion))
            mixed=[r for r in rows if r['profile']==profile and r['episode'].startswith('mixed-') and r['rhoForward']]
            switched=[r for r in rows if r['profile']==profile and r['episode']=='switch-without-stop' and r['rhoForward']]
            activeRest=[r for r in rows if r['profile']==profile and r['episode']=='active-to-rest' and r['rhoForward']]
            summary[version][profile]['mixedWindowMatrix']=matrix(mixed)
            summary[version][profile]['switchWindowMatrix']=matrix(switched)
            # Hard failure criteria fixed: any rest movement or wrong direction fails safety acceptance.
            summary[version][profile]['activeToRestMatrix']=matrix(activeRest)
            summary[version][profile]['safetyAccepted']=not any(r['output']!='STOP' and r['output'].lower()!=r['truth'] for r in mixed+switched+activeRest)
    write(DOC/'trials.csv',trials);write(DOC/'errors.csv',errors);write(DOC/'transitions.csv',transitions)
    before=read(OUT/'before/safety-probe.csv');after=read(OUT/'after/safety-probe.csv')
    probes=[dict(case=a['case'],beforeStored=a['beforePoll'],beforeRead=a['afterPoll'],afterStored=b['beforePoll'],afterRead=b['afterPoll'],expected=b['expected']) for a,b in zip(before,after)]
    write(DOC/'safety-probes.csv',probes)
    summary['normalWaveformOutputsIdentical']=same
    layers={}
    for profile in ['default','candidate']:
        eligible=[r for r in versions['after'] if r['profile']==profile and r['episode'].startswith('repeat-') and r['split']=='evaluation' and r['rhoForward'] and r['event']=='window']
        layers[profile]={field:matrix(eligible,field) for field in ['winner','raw','stable','output']}
    (DOC/'layer-matrices.json').write_text(json.dumps(layers,indent=2),encoding='utf-8')
    aligned=read(OUT/'after/aligned-filter.csv');diag={}
    for truth in LABELS[:-1]:
        col=SCORES[LABELS.index(truth)];s=[r for r in aligned if r['truth']==truth]
        init=[float(r[col]) for r in s if r['windowEnd']=='4000'];later=[float(r[col]) for r in s if r['windowEnd']=='12000']
        mixed=[r for r in versions['after'] if r['profile']=='default' and r['episode']=='mixed-'+truth and r['rhoForward']]
        vals=[float(r[col]) for r in mixed]
        diag[truth]=dict(initialAlignedMean=float(np.mean(init)),laterAlignedMean=float(np.mean(later)),maxAlignedStateDelta=max(abs(a-b) for a,b in zip(init,later)),mixedSlidingTrueRhoMinMedianMax=np.percentile(vals,[0,50,100]).tolist())
    sos=np.loadtxt(OUT/'after/filter-sos.csv',delimiter=',');gain={}
    for f in [12,8.57,6.67,5.45]:
        z=np.exp(-2j*np.pi*f/1000);h=np.prod((sos[:,0]+sos[:,1]*z+sos[:,2]*z*z)/(sos[:,3]+sos[:,4]*z+sos[:,5]*z*z));gain[str(f)]=float(abs(h))
    summary['filterDiagnostics']=diag;summary['fundamentalFilterGain']=gain
    summary['newIndependentEvaluationData']=False;summary['thresholdsRetuned']=False
    (DOC/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    text=['# Stage 2.6 可复核数值结果','',f'正常波形下修复前后 CCA、门控、稳定和输出逐记录相同：**{same}**。不将停止逻辑修复描述为分类准确性提高。','',
          '## 原始试次为统计单位','', '历史保留评价集8个运动试次、1个静息片段；此前已查看过，不称新独立测试集。以下为修复后；修复前相同。','']
    for profile in ['default','candidate']:
        for mode in ['single','repeat']:
            s=summary['after'][profile][mode]
            text += [f'### {profile} / {mode}','',table(s['matrix']),'',f"运动试次无输出：{s['rejectedMotionUnits']}/{s['motionUnits']}；有错误运动的试次：{s['wrongMotionUnits']}；静息误动片段：{s['restFalseMotionUnits']}/{s['restUnits']}。",
                     f"有效运动滑窗中停止次数：{s['eligibleRejectedTicks']}/{s['eligibleMotionTicks']}（仅补充诊断，不当独立样本）。",'']
    text+=['single=每个原始4秒片段单独开始/停止，均需如实计入无法启动；repeat=同一试次硬回绕3次提供12秒输入，只算该原始试次1个单位。repeat矩阵取保守类别：若出现任何错方向，以首次错方向计；否则至少一次正确输出计正确；完全不运动计停止。正确格不代表全程持续正确输出。','',
           '完整四层窗口混淆矩阵（CCA候选、门控、稳定、最终输出）见 layer-matrices.json；窗口仅为诊断单位，候选argmax本身没有静息拒识能力，不等同于控制输出。','',
           '## 全部试次拼接与无STOP切换（窗口诊断）','']
    for profile in ['default','candidate']:
        text += [f'### {profile} 全部试次拼接','',table(summary['after'][profile]['mixedWindowMatrix']),'',f'### {profile} 无STOP切换','',table(summary['after'][profile]['switchWindowMatrix']),'',f'### {profile} 正在前进转静息（无STOP）','',table(summary['after'][profile]['activeToRestMatrix']),'']
    text+=['## 无STOP切换延迟','', '|配置/场景|目标|切换前输出|首次停止ms|首次正确稳定ms|静息持续停止ms|错误运动窗|','|---|---|---|---:|---:|---:|---:|']
    for r in transitions:
        if r['version']=='after':text.append('|'+r['profile']+'/'+r['scenario']+'|'+r['to']+'|'+r['beforeOutput']+'|'+'|'.join(str(r[k]) for k in ['firstStopMs','firstCorrectStableMs','sustainedStopMs','wrongTicks'])+'|')
    text+=['','None=本段8秒观测内未出现；停止延迟按200ms采样记录，非真机上限。持续停止指此后直到该段结束均停止，不能证明之后永不再误动。','',
           '## 状态机修复前后','', '|场景|旧存储状态|旧读取后|新存储状态|新读取后|','|---|---|---|---|---|']
    for p in probes:text.append('|'+p['case']+'|'+'|'.join(p[k] for k in ['beforeStored','beforeRead','afterStored','afterRead'])+'|')
    text+=['','旧版没有独立最终输出，表中用其stable作对照；新版本记录control.output。旧版snapshot读取已有STOP兜底，存储状态滞后不等同于已向真实设备发送旧指令。迟到窗口测试注入固定分数和一次1.2秒计算延迟，只用于隔离生命周期问题，不用于EEG准确性。','',
           '## 识别质量诊断','', '|方向|首次4秒对齐真类ρ均值|重复第三遍对齐均值|初始化状态差异最大值|混合滑窗真类ρ min/median/max|','|---|---:|---:|---:|---|']
    for k,v in diag.items():text.append(f"|{k}|{v['initialAlignedMean']:.4f}|{v['laterAlignedMean']:.4f}|{v['maxAlignedStateDelta']:.4f}|"+' / '.join(f'{n:.4f}' for n in v['mixedSlidingTrueRhoMinMedianMax'])+'|')
    text+=['','四个基频的滤波器稳态幅值增益：'+json.dumps(gain),'','完整逐试次、错误与延迟记录见同目录 CSV。`safetyAccepted=false` 为验收失败，不能用单元测试运行通过覆盖此结果。']
    (DOC/'RESULTS.md').write_text('\n'.join(text)+'\n',encoding='utf-8')
    print(json.dumps(dict(normalSame=same,after=summary['after'],filterDiagnostics=diag),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
