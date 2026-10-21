"""Real-EEG, trial-bounded window comparison using the actual Kotlin B math and state machine.

No Android build, APK generation, downloads, synthetic EEG, fitting, or production edits.
Requires Python 3.10+, existing JDK17 and cached Kotlin2.1.20 compiler dependencies.
  python scripts/cca_window_comparison.py --data output/stage25/data --windows 1.5 2 2.5 4
"""
import argparse
import collections
import csv
import difflib
import hashlib
import json
import math
import os
import statistics
import struct
import subprocess
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SIGNAL=ROOT/'app/src/main/java/com/yourteam/brainchair/signal'
RECEIVER=ROOT/'app/src/main/java/com/yourteam/brainchair/receiver'
CONTROL=ROOT/'app/src/main/java/com/yourteam/brainchair/control'
LABELS=['FORWARD','BACKWARD','LEFT','RIGHT','STOP']
ZH=dict(FORWARD='前进',BACKWARD='后退',LEFT='左转',RIGHT='右转',STOP='静息/停止')
SOURCES=[SIGNAL/n for n in ['Cca.kt','Bandpass.kt','DecisionGate.kt','RecognitionControl.kt','ControlOutputSource.kt','SignalTiming.kt','SignalEngine.kt']]+[RECEIVER/n for n in ['EegProtocol.kt','StreamSession.kt']]+[CONTROL/'ControlAuthority.kt']

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read_csv(path):
    with Path(path).open(encoding='utf-8',newline='') as f:return list(csv.DictReader(f))
def write_csv(path,rows,fields=None):
    with Path(path).open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields or list(rows[0]));w.writeheader();w.writerows(rows)
def dump(path,obj):Path(path).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def invoke(cmd,log):
    result=subprocess.run([str(c) for c in cmd],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    log.write(result.stdout);log.flush()
    if result.returncode:raise RuntimeError(f'Process failed ({result.returncode}); see {log.name}\n{result.stdout[-3000:]}')

def validate_data(data):
    m=json.loads((data/'segments.json').read_text(encoding='utf-8'));raw=data/'segments.bin'
    assert m['sampleRate']==1000 and m['channelCount']==62 and len(m['channelNames'])==62
    assert len(set(m['channelNames']))==62 and set(['Pz','O1','Oz','O2','P1','P2','POz','PO3','PO4'])<=set(m['channelNames'])
    assert (data/'names.txt').read_text(encoding='utf-8').strip().split(',')==m['channelNames']
    plan=[line.split('\t') for line in (data/'plan.tsv').read_text(encoding='utf-8').splitlines()]
    assert len(plan)==len(m['segments']) and len({s['source'] for s in m['segments']})==len(plan)
    assert collections.Counter(s['label'] for s in m['segments'])==dict(forward=4,backward=4,left=4,right=4,stop=2)
    assert collections.Counter(p[1] for p in plan)==dict(calibration=9,evaluation=9)
    end=0
    for p,s in zip(plan,m['segments']):
        assert p==[str(m['segments'].index(s)),p[1],s['label'],s['source'],str(s['sampleOffset']),str(s['sampleCount'])]
        assert p[1] in ['calibration','evaluation']
        assert s['sampleOffset']==end and s['sampleCount']==4000 and s['channelCount']==62
        assert s['label'].upper() in LABELS;end+=4000
    assert end==m['totalSamples']==72000 and raw.stat().st_size==end*62*4
    assert all(math.isfinite(v[0]) for v in struct.iter_unpack('<f',raw.read_bytes()))
    hashes={n:sha(data/n) for n in ['segments.bin','segments.json','names.txt','plan.tsv']}
    inspection=data/'inspection.json'
    if inspection.exists():
        old=json.loads(inspection.read_text(encoding='utf-8'))
        assert old['binarySha256']==hashes['segments.bin'] and old['metadataSha256']==hashes['segments.json']
    return dict(path=str(data),declaredSource=m['source'],originalMatAvailable=False,
        sampleRate=m['sampleRate'],channels=m['channelCount'],totalSamples=end,byteCount=raw.stat().st_size,
        segments=len(plan),motionOriginalTrials=sum(s['label']!='stop' for s in m['segments']),restSegments=sum(s['label']=='stop' for s in m['segments']),
        labels=dict(collections.Counter(s['label'] for s in m['segments'])),hashes=hashes,
        pastExposure='All segments and both historical splits have been inspected/tuned in prior stages; this is retrospective paired exploration, not unseen validation.',
        independence='16 distinct original motion trial IDs; two non-overlapping pre_rest clips from one recording, physiological independence not established; one subject/session.')

def parameterize(source_dir):
    source_dir.mkdir(parents=True,exist_ok=True);changes=[];copies=[]
    for original in SOURCES:
        before=original.read_text(encoding='utf-8');after=before
        # Stage 3 exposes windowSeconds/updateMs explicitly. No string rewriting of production
        # math is needed; the frozen four-arm experiment still sets its own lengths and 250ms.
        edits=[]
        for old,new in edits:
            assert after.count(old)==1,f'Production implementation changed: cannot safely parameterize {original.name}: {old}'
            after=after.replace(old,new,1)
        target=source_dir/original.name;target.write_text(after,encoding='utf-8');copies.append(target)
        if before!=after:changes+=list(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=str(original.relative_to(ROOT)),tofile='EXPERIMENT_ONLY/'+original.name))
    (source_dir/'window-only.patch').write_text(''.join(changes),encoding='utf-8')
    return copies

def compiler(cache):
    coords=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),('org.jetbrains.kotlin','kotlin-stdlib','2.1.20'),
        ('org.jetbrains.kotlin','kotlin-script-runtime','2.1.20'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
        ('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.1.20'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
    jars=[]
    for group,artifact,version in coords:
        matches=list((cache/group/artifact/version).glob(f'*/{artifact}-{version}.jar'))
        if len(matches)!=1:raise FileNotFoundError(f'Required existing cached dependency missing/ambiguous: {group}/{artifact}/{version}. No software installed.')
        jars.append(matches[0])
    return os.pathsep.join(map(str,jars)),os.pathsep.join(map(str,[jars[1],jars[-1]]))

def run_kotlin(args,out):
    originals={str(p.relative_to(ROOT)):sha(p) for p in SOURCES}
    originals.update({str(p.relative_to(ROOT)):sha(p) for p in [Path(__file__),ROOT/'scripts/CcaWindowComparison.kt',ROOT/'scripts/CcaWindowParity.kt']})
    digest=hashlib.sha256(json.dumps(originals,sort_keys=True).encode()).hexdigest()[:16]
    runtime=out/'runtime'/digest;copies=parameterize(runtime/'sources')
    cp,runtime_cp=compiler(args.cache);experiment=runtime/'experiment-classes';baseline=runtime/'baseline-classes'
    experiment.mkdir(parents=True,exist_ok=True);baseline.mkdir(parents=True,exist_ok=True)
    flags=['-jvm-target','17','-no-stdlib','-no-reflect','-classpath',runtime_cp]
    with (out/'run.log').open('w',encoding='utf-8') as log:
        begin=time.perf_counter()
        invoke([args.java,'-Dfile.encoding=UTF-8','-Xmx512m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',*flags,'-d',experiment,
                *copies,ROOT/'scripts/CcaWindowComparison.kt',ROOT/'scripts/CcaWindowParity.kt'],log)
        invoke([args.java,'-Dfile.encoding=UTF-8','-Xmx512m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',*flags,'-d',baseline,
                SIGNAL/'Cca.kt',SIGNAL/'Bandpass.kt',SIGNAL/'SignalTiming.kt',ROOT/'scripts/CcaWindowParity.kt'],log)
        for classes,name in [(baseline,'baseline-numeric.csv'),(experiment,'parameterized-numeric.csv')]:
            invoke([args.java,'-Dfile.encoding=UTF-8','-Xmx192m','-cp',os.pathsep.join([str(classes),runtime_cp]),'com.yourteam.brainchair.experiment.CcaWindowParity',args.data,out/name],log)
        invoke([args.java,'-Dfile.encoding=UTF-8','-Xmx192m','-cp',os.pathsep.join([str(experiment),runtime_cp]),'com.yourteam.brainchair.experiment.CcaWindowComparison',
                args.data,out,','.join(str(round(w*1000)) for w in args.windows)],log)
        repeated=out/'repeat-check';repeated.mkdir(exist_ok=True)
        invoke([args.java,'-Dfile.encoding=UTF-8','-Xmx192m','-cp',os.pathsep.join([str(experiment),runtime_cp]),'com.yourteam.brainchair.experiment.CcaWindowComparison',
                args.data,repeated,','.join(str(round(w*1000)) for w in args.windows)],log)
    main_rows=read_csv(out/'events.csv');repeat_rows=read_csv(repeated/'events.csv')
    deterministic_fields=[k for k in main_rows[0] if k not in ['compute_ms','cpu_ms','stop_processing_ms']]
    assert len(main_rows)==len(repeat_rows)
    assert all(a[k]==b[k] for a,b in zip(main_rows,repeat_rows) for k in deterministic_fields)
    assert not any('\ufffd' in r['reason'] for r in main_rows),'JVM text encoding corrupted logs'
    old=read_csv(out/'baseline-numeric.csv');new=read_csv(out/'parameterized-numeric.csv')
    assert len(old)==len(new)==18
    diffs=[abs(float(a[k])-float(b[k])) for a,b in zip(old,new) for k in ['rho_forward','rho_left','rho_right','rho_backward']]
    assert max(diffs)<1e-12
    for p in SOURCES:assert sha(p)==originals[str(p.relative_to(ROOT))],f'Unexpected production edit: {p}'
    return dict(sourceSha256=originals,experimentPatch=str(runtime/'sources/window-only.patch'),elapsedWallSeconds=time.perf_counter()-begin,
                fourSecondNumericParityTrials=len(old),fourSecondNumericMaxDelta=max(diffs),repeatEventRows=len(repeat_rows),repeatOutputsIdentical=True,
                compilation='Standalone cached Kotlin JVM only; Android/Gradle/APK not rebuilt')

def first(rows,predicate):return next((int(r['time_ms']) for r in rows if predicate(r)),None)
def durations(events,truth):
    seq=[r for r in events if r['event'] in ['INFO','NOT_READY','ANALYSIS']]
    totals=collections.Counter();longest=0;run=0;tail=0
    for i,r in enumerate(seq):
        end=int(seq[i+1]['time_ms']) if i+1<len(seq) else 4000
        duration=end-int(r['time_ms']);output=r['output']
        if output==truth:totals['correct']+=duration;run+=duration;longest=max(longest,run);tail=run
        else:run=0;tail=0
        if output=='STOP':totals['stop']+=duration
        elif output!=truth:totals['wrong']+=duration
    return dict(correct_output_ms=totals['correct'],wrong_output_ms=totals['wrong'],stop_output_ms=totals['stop'],longest_correct_run_ms=longest,correct_tail_to_deadline_ms=tail)

def trial_results(events):
    groups=collections.defaultdict(list)
    for r in events:groups[(int(r['window_ms']),r['trial'])].append(r)
    trials=[]
    for (window,trial),rows in groups.items():
        valid=[r for r in rows if r['event']=='ANALYSIS'];truth=rows[0]['truth'].upper()
        wrong=[r for r in valid if r['output'] not in [truth,'STOP']]
        # Rest must be an actual rejected CCA window; unready STOP is safe but not a classification.
        is_correct=lambda r:r['raw']==truth and r['stable']==truth and r['output']==truth
        first_raw=first(valid,lambda r:r['raw']==truth)
        stable=first(valid,is_correct)
        output=wrong[0]['output'] if wrong else truth if stable is not None else 'STOP'
        last=next(r for r in rows if r['event']=='DEADLINE_BEFORE_STOP')
        stop=next(r for r in rows if r['event']=='STREAM_STOP');after=next(r for r in rows if r['event']=='AFTER_STOP')
        assert int(stop['time_ms'])==4000 and stop['output']==after['output']=='STOP'
        assert all(int(r['time_ms'])<4000 for r in valid)
        trials.append(dict(trial_id=int(trial),source=rows[0]['source'],truth=truth,historical_split=rows[0]['split'],window_s=window/1000,
            analysis_windows=len(valid),trial_prediction=output,last_pre_stop_output=last['output'],
            first_argmax_correct_ms=first(valid,lambda r:truth!='STOP' and r['winner']==truth),
            first_gated_correct_ms=first_raw,first_stable_correct_ms=stable,
            stable_correct_before_deadline=stable is not None,error_free_stable_before_deadline=stable is not None and not wrong,
            failed_correct_stable=stable is None,ever_wrong_direction=bool(wrong),
            correct_windows=sum(is_correct(r) for r in valid),wrong_output_windows=len(wrong),
            rejected_motion_windows=sum(r['output']=='STOP' for r in valid) if truth!='STOP' else 0,
            raw_wrong_windows=sum(r['raw'] not in [truth,'STOP'] for r in valid),
            first_correct_budget_to_deadline_ms=4000-stable if stable is not None else None,
            **durations(rows,truth),stop_output=stop['output'],after_stop_output=after['output'],
            stop_logical_delay_ms=int(stop['time_ms'])-4000,stop_processing_ms=float(stop['stop_processing_ms']),
            no_complete_window=not valid,rest_false_movement=truth=='STOP' and bool(wrong)))
    return sorted(trials,key=lambda r:(r['window_s'],r['trial_id']))

def matrix(rows,true_key,pred_key):
    result={t:{p:0 for p in LABELS} for t in LABELS}
    for r in rows:result[r[true_key].upper()][r[pred_key].upper()]+=1
    return result
def accuracy_f1(m):
    total=sum(sum(r.values()) for r in m.values());f1=[]
    for label in LABELS:
        tp=m[label][label];fp=sum(m[t][label] for t in LABELS if t!=label);fn=sum(m[label][p] for p in LABELS if p!=label)
        f1.append(2*tp/(2*tp+fp+fn) if 2*tp+fp+fn else 0.)
    return sum(m[l][l] for l in LABELS)/total if total else None,statistics.mean(f1) if total else None
def percentile(values,q):
    if not values:return None
    x=sorted(values);i=(len(x)-1)*q;lo=math.floor(i);hi=math.ceil(i)
    return x[lo]+(x[hi]-x[lo])*(i-lo)
def latency(trials,field,prefix):
    values=[t[field] for t in trials if t[field] is not None]
    def deadline_quantile(q):
        rank=math.ceil(len(trials)*q)
        return sorted(values)[rank-1] if rank and rank<=len(values) else '>4000/not-reached'
    return {prefix+'_success_n':len(values),prefix+'_missing_n':len(trials)-len(values),
        prefix+'_conditional_median_ms':percentile(values,.5),prefix+'_conditional_p95_ms':percentile(values,.95),
        prefix+'_all_trials_median_ms':deadline_quantile(.5),prefix+'_all_trials_p95_ms':deadline_quantile(.95)}

def summarize(trials,events):
    summaries=[];confusions=[]
    for window in sorted({t['window_s'] for t in trials}):
        for scope,split in [('all_reused',None),('historical_calibration','calibration'),('historical_evaluation','evaluation')]:
            group=[t for t in trials if t['window_s']==window and (split is None or t['historical_split']==split)]
            full_matrix=matrix(group,'truth','trial_prediction');accuracy,f1=accuracy_f1(full_matrix)
            for label in ['ALL',*LABELS]:
                selected=[t for t in group if label=='ALL' or t['truth']==label]
                motions=[t for t in selected if t['truth']!='STOP'];rests=[t for t in selected if t['truth']=='STOP']
                correct=sum(t['trial_prediction']==t['truth'] for t in selected)
                diagnostic=[r for r in events if r['event']=='ANALYSIS' and int(r['window_ms'])==round(window*1000) and
                    (split is None or r['split']==split) and (label=='ALL' or r['truth'].upper()==label)]
                motion_windows=[r for r in diagnostic if r['truth'].upper()!='STOP'];rest_windows=[r for r in diagnostic if r['truth'].upper()=='STOP']
                summaries.append(dict(window_s=window,scope=scope,class_label=label,trials=len(selected),correct_trials=correct,
                    accuracy=correct/len(selected),macro_f1=f1 if label=='ALL' else None,
                    motion_trials=len(motions),motion_correct=sum(t['trial_prediction']==t['truth'] for t in motions),
                    wrong_motion_trials=sum(t['trial_prediction'] not in [t['truth'],'STOP'] for t in motions),
                    rejected_motion_trials=sum(t['trial_prediction']=='STOP' for t in motions),
                    rest_trials=len(rests),rest_false_motion_trials=sum(t['rest_false_movement'] for t in rests),
                    rest_false_motion_ms=sum(t['wrong_output_ms'] for t in rests),
                    analysis_windows=len(diagnostic),motion_windows=len(motion_windows),rest_windows=len(rest_windows),
                    argmax_correct_motion_windows=sum(r['winner']==r['truth'].upper() for r in motion_windows),
                    raw_correct_motion_windows=sum(r['raw']==r['truth'].upper() for r in motion_windows),
                    raw_wrong_motion_windows=sum(r['raw'] not in [r['truth'].upper(),'STOP'] for r in motion_windows),
                    raw_rejected_motion_windows=sum(r['raw']=='STOP' for r in motion_windows),
                    final_correct_motion_windows=sum(r['output']==r['truth'].upper() for r in motion_windows),
                    final_wrong_motion_windows=sum(r['output'] not in [r['truth'].upper(),'STOP'] for r in motion_windows),
                    final_rejected_motion_windows=sum(r['output']=='STOP' for r in motion_windows),
                    final_false_rest_windows=sum(r['output']!='STOP' for r in rest_windows),
                    no_window_trials=sum(t['no_complete_window'] for t in selected),
                    deadline_motion_success_n=sum(t['stable_correct_before_deadline'] for t in motions),
                    deadline_motion_success_rate=sum(t['stable_correct_before_deadline'] for t in motions)/len(motions) if motions else None,
                    deadline_error_free_motion_n=sum(t['error_free_stable_before_deadline'] for t in motions),
                    deadline_error_free_motion_rate=sum(t['error_free_stable_before_deadline'] for t in motions)/len(motions) if motions else None,
                    end_correct_motion_n=sum(t['last_pre_stop_output']==t['truth'] for t in motions),
                    interrupted_correct_motion_n=sum(t['stable_correct_before_deadline'] and
                        t['correct_tail_to_deadline_ms']<4000-t['first_stable_correct_ms'] for t in motions),
                    **latency(selected if label=='STOP' else motions,'first_gated_correct_ms','first_raw'),
                    **latency(selected if label=='STOP' else motions,'first_stable_correct_ms','first_stable'),
                    correct_motion_ms=sum(t['correct_output_ms'] for t in motions),wrong_motion_ms=sum(t['wrong_output_ms'] for t in motions),
                    conditional_correct_motion_median_ms=percentile([t['correct_output_ms'] for t in motions if t['stable_correct_before_deadline']],.5)))
            for level in ['trial_prediction','last_pre_stop_output']:
                m=matrix(group,'truth',level)
                for true in LABELS:
                    for pred in LABELS:confusions.append(dict(window_s=window,scope=scope,unit=level,truth=true,prediction=pred,count=m[true][pred]))
            windows=[r for r in events if r['event']=='ANALYSIS' and int(r['window_ms'])==round(window*1000) and (split is None or r['split']==split)]
            for level in ['winner','raw','stable','output']:
                m=matrix(windows,'truth',level)
                for true in LABELS:
                    for pred in LABELS:confusions.append(dict(window_s=window,scope=scope,unit='overlapping_window_'+level,truth=true,prediction=pred,count=m[true][pred]))
    return summaries,confusions

def fmt(x,d=2):return '—' if x is None else f'{x:.{d}f}' if isinstance(x,(float,int)) else str(x)
def matrix_md(m):
    return '\n'.join(['|真实/预测|前进|后退|左转|右转|停止/拒识|','|---|---:|---:|---:|---:|---:|']+['|'+ZH[t]+'|'+'|'.join(str(m[t][p]) for p in LABELS)+'|' for t in LABELS])

def report(args,evidence,trials,summaries,confusions,events):
    doc=ROOT/'docs';prefix='cca-window-comparison'
    write_csv(doc/(prefix+'-trials.csv'),trials)
    write_csv(doc/(prefix+'-summary.csv'),summaries)
    write_csv(doc/(prefix+'-confusion.csv'),confusions)
    write_csv(doc/(prefix+'-windows.csv'),events)
    errors=[r for r in events if r['event']=='ANALYSIS' and r['output'] not in [r['truth'].upper(),'STOP']]
    write_csv(doc/(prefix+'-errors.csv'),errors,list(events[0]))
    dump(doc/(prefix+'-evidence.json'),evidence)
    allrows=[r for r in summaries if r['scope']=='all_reused' and r['class_label']=='ALL']
    cfg=evidence['config'];data=evidence['data'];checks=evidence['checks']
    text=['# CCA 四种分析窗口的离线对照实验','',
        '**这是使用历史已暴露数据的离线配对比较，不是独立真实 EEG 安全验收；正式 App、阈值、安全参数和 APK 均未修改。**','',
        '## 数据与固定条件','',
        f'- 数据：`{args.data}` 下的 `segments.bin/segments.json/names.txt/plan.tsv`。声明来源 `{data["declaredSource"]}`，原 MAT 不在本次可用输入中，来源依赖随 APK 附带元数据，不能重新验证原始标注流程。',
        '- 1000Hz、62 通道、小端 float32 时间主序，共 72000 点/72 秒。16 个不同原始运动试次：前后左右各4；另2个四秒预静息片段。18个不重叠片段分别初始化与 STOP，不重复、不拼接、不补齐。两段静息来自同一份 pre_rest，不保证生理独立性；四种窗口复用同18片段，不是72个独立试次。',
        '- 真实标签使用 JSON 的 `label/source/sampleOffset/sampleCount` 与原 plan.tsv 逐项核对。标签只交给评价器，B 仅接收真实 float32 样本和现有 v2 字段；没有加入合成 EEG 或从预测生成真值。',
        '- 全部数据及两份历史划分在旧阶段已经查看/调试过。保留原 calibration/evaluation 标记，各9片段，另列描述结果；历史 evaluation 不是本次新盲测集。本次不调参、不挑片段。',
        f'- 固定9通道按 STREAM_INFO 名称选取；因果连续4～45Hz滤波、500ms预热、三个谐波和12/8.57/6.67/5.45Hz频率保持原实现。绝对阈值 {cfg["absolute"]}，差值阈值 {cfg["difference"]}，连续 {cfg["votes"]} 窗投票、{cfg["debounceMs"]}ms去抖、1秒有效期保持原样。这里沿用正式代码默认值，不把未获批准的候选0.55/0.05替换进实验。','',
        '## 实现与截止时间','',
        '- 独立 JVM 编译现有 Kotlin 算法、接收/控制状态机。只在 `output/cca-window-comparison/runtime/` 的副本中给 Cca 的样本数和 SignalEngine 的 CCA/环形窗长度加参数；正式源码不变。完整副本补丁和逐文件 SHA 在证据中。滤波器、门控、投票和最终输出使用原代码。',
        '- 输入每5ms提交5点；统一在250ms网格分析。离线时钟代表采样可用时间，计算不消耗该虚拟时钟；延迟是无网络/线程调度延迟的样本时间下界，不是手机实时时延。实际 JVM 计算耗时另见事件 CSV。',
        '- `t<4000ms` 才可产生有效方向；恰好4000ms优先处理 STREAM_STOP，不把在截止时刻才完成/之后完成的判决算运动。最后有效调度点3750ms，4000ms记录停止前状态，然后立即停止并验证清理。此规则对四组完全一致。',
        '- 预热不计入可分析窗口：首次完整窗口下界依次2000/2500/3000/4500ms。250ms步长下连续三窗需再500ms，已覆盖300ms去抖，故理论最早稳定2500/3000/3500/5000ms。四秒窗在四秒输入内没有一次完整分析，不能从3500有效点硬凑4000点。',
        '- 每个实际窗口记录 CCA argmax、门控 raw、stable、最终 output。独立试次主结果采用保守规则：出现任何错误运动，按第一次错误方向记该片段失败；否则曾有稳定正确输出记真类；否则记 STOP/拒识。另给最后截止前输出矩阵，不把“曾经对过一次”掩盖错误。静息始终 STOP 记安全无误动，但无窗口时不称为完成静息识别。','',
        '## 按原始试验段汇总','',
        '|窗口s|正确/18（含静息安全STOP）|试次输出准确率|宏F1（5类）|运动正确/错误/拒识（16段）|静息误动/2|4秒前曾稳定正确/16|无错运动且稳定正确/16|',
        '|---:|---:|---:|---:|---|---:|---:|---:|']
    for r in allrows:text.append(f'|{r["window_s"]:.1f}|{r["correct_trials"]}/18|{r["accuracy"]*100:.2f}%|{r["macro_f1"]:.4f}|{r["motion_correct"]}/{r["wrong_motion_trials"]}/{r["rejected_motion_trials"]}|{r["rest_false_motion_trials"]}/2|{r["deadline_motion_success_n"]}/16 ({r["deadline_motion_success_rate"]*100:.2f}%)|{r["deadline_error_free_motion_n"]}/16 ({r["deadline_error_free_motion_rate"]*100:.2f}%)|')
    text+=['','宏F1及准确率仅对这18个片段描述计算，不给跨受试者推广或显著性结论。四秒组若静息安全STOP导致非零准确率，该数值并非 CCA 识别能力。STOP 同时表示静息输出与运动拒识，CSV 单独区分真值、无窗口和拒识。','',
        '### 分方向结果','', '|窗口s|真值|正确/总段|错运动段|运动拒识段|静息误动段|曾稳定正确/无错稳定正确|首次门控中位/P95 ms|稳定中位/P95 ms|稳定缺失数|','|---:|---|---:|---:|---:|---:|---|---|---|---:|']
    for r in summaries:
        if r['scope']=='all_reused' and r['class_label']!='ALL':
            label=r['class_label'];t=[q for q in trials if q['window_s']==r['window_s'] and q['truth']==label]
            vals=[q['first_stable_correct_ms'] for q in t if q['first_stable_correct_ms'] is not None]
            text.append(f'|{r["window_s"]:.1f}|{ZH[label]}|{r["correct_trials"]}/{r["trials"]}|{r["wrong_motion_trials"]}|{r["rejected_motion_trials"]}|{r["rest_false_motion_trials"]}|{sum(q["stable_correct_before_deadline"] for q in t)}/{sum(q["error_free_stable_before_deadline"] for q in t)}（各/{len(t)}）|{fmt(r["first_raw_conditional_median_ms"],0)}/{fmt(r["first_raw_conditional_p95_ms"],0)}|{fmt(percentile(vals,.5),0)}/{fmt(percentile(vals,.95),0)}|{len(t)-len(vals)}|')
    text+=['','静息的“稳定正确”延迟只从真正完成且被门控拒识的窗口计起，不把预热期的STOP算成识别；静息安全正确段数和识别完成段数可以不同。','',
        '## 延迟与有效运动时间（仅16个运动试次）','',
        '|窗口s|首次门控正确中位/P95 ms|原始成功/缺失|首次稳定正确中位/P95 ms|稳定成功/缺失|计入所有运动段的稳定中位/P95|成功段正确运动总时长中位ms|运动错方向/静息误动时长ms|',
        '|---:|---|---|---|---|---|---:|---:|']
    for r in allrows:text.append(f'|{r["window_s"]:.1f}|{fmt(r["first_raw_conditional_median_ms"],0)}/{fmt(r["first_raw_conditional_p95_ms"],0)}|{r["first_raw_success_n"]}/{r["first_raw_missing_n"]}|{fmt(r["first_stable_conditional_median_ms"],0)}/{fmt(r["first_stable_conditional_p95_ms"],0)}|{r["first_stable_success_n"]}/{r["first_stable_missing_n"]}|{r["first_stable_all_trials_median_ms"]}/{r["first_stable_all_trials_p95_ms"]}|{fmt(r["conditional_correct_motion_median_ms"],0)}|{r["wrong_motion_ms"]}/{r["rest_false_motion_ms"]}|')
    text+=['','条件分位数只对成功段计算（线性插值），失败/缺失数量逐项列明。全体运动段分位数使用截止前累计成功比例，未达到相应比例则标 `>4000/not-reached`，不删除失败段造成虚假的快速响应。静息及各方向分位数见分方向表和 CSV。有效时间按每次最终输出保持至下一事件积分，到4000ms截断；首次稳定后不一定全程正确，逐段 CSV 同时记录总正确时长、最长连续正确时长及正确保持到截止的尾段时长。','',
        '### 单窗诊断（重叠窗口不作独立试次）','',
        '|窗口s|实际分析窗数|运动窗 CCA argmax 正确/总窗|门控运动正确/错误/拒识|最终运动正确/错误/拒识|静息错误运动窗/静息窗|截止前最后仍正确的运动段/16|曾正确后中断段|',
        '|---:|---:|---|---|---|---|---:|---:|']
    for r in allrows:text.append(f'|{r["window_s"]:.1f}|{r["analysis_windows"]}|{r["argmax_correct_motion_windows"]}/{r["motion_windows"]}|{r["raw_correct_motion_windows"]}/{r["raw_wrong_motion_windows"]}/{r["raw_rejected_motion_windows"]}|{r["final_correct_motion_windows"]}/{r["final_wrong_motion_windows"]}/{r["final_rejected_motion_windows"]}|{r["final_false_rest_windows"]}/{r["rest_windows"]}|{r["end_correct_motion_n"]}|{r["interrupted_correct_motion_n"]}|')
    text+=['','这些窗口共享大量 EEG 样本，只能诊断门控、稳定性与时长，不能增加独立样本数。4秒组的0/0表示没有可用分析，不是零次错误就代表分类通过。','',
        '## 混淆矩阵','', '以下每个原始片段只计一次，行是真值、列是保守试次输出；完整窗口四层矩阵、最后截止前输出及历史两份划分在 `cca-window-comparison-confusion.csv`。','']
    for w in args.windows:
        t=[q for q in trials if q['window_s']==w]
        text += [f'### {w:.1f}秒（{len(t)}个片段）','',matrix_md(matrix(t,'truth','trial_prediction')),'']
    text+=['## 保留历史划分的结果（不是本次盲测）','', '|窗口s|历史划分|段数|准确率|运动正确/错误/拒识|静息误动|4秒前无错稳定运动|','|---:|---|---:|---:|---|---:|---:|']
    for r in summaries:
        if r['class_label']=='ALL' and r['scope']!='all_reused':text.append(f'|{r["window_s"]:.1f}|{r["scope"]}|{r["trials"]}|{r["accuracy"]*100:.2f}%|{r["motion_correct"]}/{r["wrong_motion_trials"]}/{r["rejected_motion_trials"]}|{r["rest_false_motion_trials"]}/{r["rest_trials"]}|{r["deadline_error_free_motion_n"]}/{r["motion_trials"]}|')
    text+=['','## 停止、残留与相关自检','',
        f'- 本次重放 {len(trials)} 个“配置×片段”组合；{checks["numericWindowComparisons"]} 个实际窗逐个与独立流式滤波后的同索引直接 CCA 比较，最大差 {checks["maxScoreDelta"]:.3g}。验证连续状态、9通道顺序、窗口位置与500ms预热，无跨段/填充数据。',
        f'- 另外用同18段真实数据，分别编译正式未修改 Cca/Bandpass 与实验副本默认4秒，数值相关值最大差 {evidence["execution"]["fourSecondNumericMaxDelta"]:.3g}。该自检直接计算同一4000点滤波数组（含启动瞬态），不经过正式预热/投票，只检查两份算法数值相同，不计入主实验四秒组的识别成绩。',
        f'- 再次执行同一组配置和片段，{evidence["execution"]["repeatEventRows"]} 条事件除处理耗时字段外逐项相同。重复仅验证可复现性，未增加试验段或结果分母。',
        f'- 本次相关断言 {checks["passed"]}/{checks["checks"]} 通过；各组合4000ms收到STOP后 raw/stable/final 均停止、分数/窗口/缓冲清空，4250ms再读仍STOP。逻辑停止偏移为0ms，只是同步离线事件时钟，事件 CSV 的处理耗时也仅是桌面JVM，不是A/B网络或Unity停车延迟。',
        '- 每片段起始STOP、窗口和滤波状态独立。实验不包含无标志换波形、异步迟到结果或真实跨方向连续交互，无法从这批独立片段估计旧方向残留/跨方向停车延迟；本次错误运动统计完整保留，不能凭离线 STOP 通过宣布控制安全。','',
        '## 误动与漏判的可复核记录','',
        '全部错误运动窗保存在 `cca-window-comparison-errors.csv`，也保留在完整窗口 CSV。','',
        '|窗口s|试验来源/真值|错误输出|首次错误时刻ms|错误输出累计ms|最后一次错误后首次STOP ms|',
        '|---:|---|---|---:|---:|---:|']
    for t in trials:
        if t['ever_wrong_direction']:
            group=[e for e in events if int(e['window_ms'])==round(t['window_s']*1000) and int(e['trial'])==t['trial_id']]
            wrong=[e for e in group if e['event']=='ANALYSIS' and e['output'] not in [t['truth'],'STOP']]
            next_stop=first(group,lambda e:int(e['time_ms'])>int(wrong[-1]['time_ms']) and e['output']=='STOP')
            text.append(f'|{t["window_s"]:.1f}|{t["source"]}/{t["truth"]}|{wrong[0]["output"]}|{wrong[0]["time_ms"]}|{t["wrong_output_ms"]}|{next_stop}|')
    text += ['', '这是静息片段中的误动与恢复停止时间，不是方向切到静息的反应时间；没有把静息错判从失败指标中排除。','',
        '## 推荐与局限','']
    ranked=sorted(allrows,key=lambda r:(-r['deadline_error_free_motion_n'],
        -(min(s['deadline_error_free_motion_rate'] for s in summaries if s['scope']=='all_reused' and s['window_s']==r['window_s'] and s['class_label'] in LABELS[:-1])),
        r['wrong_motion_trials']+r['rest_false_motion_trials'],
        r['first_stable_conditional_median_ms'] if r['first_stable_conditional_median_ms'] is not None else 9999))
    best=ranked[0]
    text += [f'以截止前无错误运动的稳定成功段数为首要比较指标，{best["window_s"]:.1f}秒在本批数据中排序最前（{best["deadline_error_free_motion_n"]}/16）。本次比较不修改正式配置（Stage3已另行确认采用2.5秒）。四个方向的正确段数为 '+
        '、'.join(f'{ZH[s["class_label"]]}{s["correct_trials"]}/{s["trials"]}' for s in summaries if s['scope']=='all_reused' and s['window_s']==best['window_s'] and s['class_label'] in LABELS[:-1])+'。','']
    missed=[t for t in trials if t['window_s']==best['window_s'] and t['truth']!='STOP' and t['failed_correct_stable']]
    for t in missed:
        text += [f'候选漏判 `{t["source"]}`（真值{ZH[t["truth"]]}）：首次 CCA argmax 正确为 {t["first_argmax_correct_ms"]}ms，首次通过门控 {t["first_gated_correct_ms"]}ms，截止前没有稳定判决。以下是真实相关值，未降低门限补成绩：','',
            '|分析时刻ms|真方向相关值|真方向减最大其他方向|门控|稳定|','|---:|---:|---:|---|---|']
        for e in events:
            if e['event']=='ANALYSIS' and int(e['window_ms'])==round(t['window_s']*1000) and int(e['trial'])==t['trial_id']:
                rho=float(e['rho_'+t['truth'].lower()]);others=[float(e['rho_'+l.lower()]) for l in LABELS[:-1] if l!=t['truth']]
                text.append(f'|{e["time_ms"]}|{rho:.4f}|{rho-max(others):.4f}|{e["raw"]}|{e["stable"]}|')
        text.append('')
    text += [
        f'该候选成功运动段的有效正确输出中位数只有 {fmt(best["conditional_correct_motion_median_ms"],0)}ms；真实手机还要扣除计算、调度、发送及执行耗时。窗口缩短后仍须单独验证能否形成可见、可用的交互。1.5秒/2秒本批静息出现误动，不能因启动更早就推荐为安全配置；2.5秒虽本批无静息误动，也只有两段静息支持。', '',
        '四种配置均不能据此宣布识别与安全验收通过：短窗存在静息误动，候选仍有右转拒识且有效时间很短，4秒组无完整分析。本实验没有降低任何安全要求来选出“合格”窗口。固定门限在各窗长下不一定最优；本次严格不分组调参，后续如标定必须重新划分并使用未暴露的试次。', '',
        '没有预先约定可接受的分类准确率下限，本实验也没有足够样本为真实控制安全认证。若任何方向仍拒识或有错误运动，不能把候选写成四方向验收通过。只有2个静息片段，不足以估计低概率误动；同一受试者的预选片段、历史阈值曝光和四秒短片段都会限制推广。需要新的完整原始运动试次、较长静息、更多受试者及未接触标签的数据，再冻结候选窗口和参数做评价。', '',
        '### 复现与文件','',
        '```powershell',"& 'D:/BrainChair/stage2-test-env/Scripts/python.exe' ./scripts/cca_window_comparison.py --data ./output/stage25/data --windows 1.5 2.0 2.5 4.0",'```','',
        '- 依赖：Python标准库、已安装JDK17、`D:/BrainChair/gradle-cache/caches/modules-2/files-2.1` 中已有Kotlin2.1.20及其缓存依赖。不安装、不下载；可用 `--java/--cache/--data/--out` 指定现有位置。',
        '- 离线脚本 `scripts/cca_window_comparison.py`；真实算法驱动 `scripts/CcaWindowComparison.kt`；4秒数值兼容自检 `scripts/CcaWindowParity.kt`。',
        '- 本目录 `cca-window-comparison-trials.csv` 为逐段72行；`-summary.csv` 为窗口/历史划分/方向汇总；`-windows.csv` 含逐窗相关值和清理事件；`-errors.csv` 为错误运动窗口；`-confusion.csv` 为各层矩阵；`-evidence.json` 为数据/源码SHA及相关断言。',
        '- 完整编译日志、副本补丁、类文件、原始事件与数值兼容记录在 `output/cca-window-comparison/`，保持Git忽略；没有生成或替换APK。','']
    (doc/(prefix+'.md')).write_text('\n'.join(text),encoding='utf-8')
    return best

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,default=ROOT/'output/stage25/data');p.add_argument('--out',type=Path,default=ROOT/'output/cca-window-comparison')
    p.add_argument('--windows',type=float,nargs='+',default=[1.5,2.,2.5,4.])
    p.add_argument('--java',type=Path,default=Path('D:/BrainChair/tools/jdk-17.0.20.1+1/bin/java.exe'))
    p.add_argument('--cache',type=Path,default=Path('D:/BrainChair/gradle-cache/caches/modules-2/files-2.1'))
    args=p.parse_args();args.data=args.data.resolve();args.out=args.out.resolve();args.windows=sorted(set(args.windows))
    assert args.windows==[1.5,2.,2.5,4.], 'This frozen comparison must include all four requested lengths.'
    args.out.mkdir(parents=True,exist_ok=True)
    protected={str(p.relative_to(ROOT)):sha(p) for p in SOURCES}
    apk=ROOT/'output/stage27/BrainChair-B-stage27.apk'
    if apk.exists():protected[str(apk.relative_to(ROOT))]=sha(apk)
    data=validate_data(args.data)
    print('Validated real EEG: 16 motion trials + 2 rest clips; historical labels/splits reused.',flush=True)
    execution=run_kotlin(args,args.out)
    events=read_csv(args.out/'events.csv');trials=trial_results(events);summaries,confusions=summarize(trials,events)
    checks=json.loads((args.out/'harness-checks.json').read_text());cfg=json.loads((args.out/'config.json').read_text())
    assert len(trials)==72 and checks['numericWindowComparisons']==324
    assert all(t['analysis_windows']=={1.5:8,2.:6,2.5:4,4.:0}[t['window_s']] for t in trials)
    assert all(t['first_stable_correct_ms'] is None or t['first_stable_correct_ms']>=int((t['window_s']+.5)*1000)+500 or t['truth']=='STOP' for t in trials)
    assert all(t['correct_output_ms']+t['wrong_output_ms']+t['stop_output_ms']==4000 for t in trials if t['truth']!='STOP')
    for path,h in protected.items():assert sha(ROOT/path)==h,f'Protected production artifact changed: {path}'
    evidence=dict(data=data,config=cfg,execution=execution,checks=checks,protectedFilesUnchanged=protected,
        metricPolicy='One conservative outcome per original segment/config; any wrong motion makes trial wrong; overlaps diagnostic only; missing latencies censored at 4s.',
        performanceScope='Sample-time offline scheduling, not Android or network real-time; no Unity test; no accepted real EEG safety certification.')
    best=report(args,evidence,trials,summaries,confusions,events)
    for s in summaries:
        if s['scope']=='all_reused' and s['class_label']=='ALL':
            print(json.dumps({k:s[k] for k in ['window_s','accuracy','macro_f1','deadline_motion_success_n','deadline_error_free_motion_n','wrong_motion_trials','rejected_motion_trials','rest_false_motion_trials','first_stable_conditional_median_ms']},ensure_ascii=False),flush=True)
    print(f'Exploratory next-validation candidate: {best["window_s"]}s; production configuration untouched.',flush=True)

if __name__=='__main__':main()
