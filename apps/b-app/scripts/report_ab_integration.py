"""Generate checked evidence tables, keeping original-A, contract-sender, and phone scopes distinct."""
import json,hashlib,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'output/ab-integration';DOC=ROOT/'docs/ab-integration'
def get(path):return json.loads(path.read_text(encoding='utf-8'))
def main():
    DOC.mkdir(parents=True,exist_ok=True)
    before=get(OUT/'before-peer-fix/cases.json');fixed=get(OUT/'actual-a-fixed/cases.json');contract=get(OUT/'contract-sender/summary.json')
    old=next(x for x in before if x['case']=='rapid-direction-new-stream');new=next(x for x in fixed if x['case']=='rapid-direction-new-stream')
    tests=[ET.parse(p).getroot().attrib for p in sorted((OUT/'junit').glob('*.xml'))]
    summary=dict(beforePeerFixCases=before,afterPeerFixCases=fixed,contractSender=contract,junit=tests,
        bApkSha256=hashlib.sha256((OUT/'BrainChair-B-ab-check.apk').read_bytes()).hexdigest(),
        beforePeerFixBApkSha256=hashlib.sha256((OUT/'before-peer-fix/BrainChair-B-ab-check.apk').read_bytes()).hexdigest(),
        aApkSha256=hashlib.sha256((OUT/'A-original.apk').read_bytes()).hexdigest(),physicalPhonesTested=False,
        actualAMatchesHoldReleaseContract=False,recognitionSafetyAccepted=False,BCProtocolFrozen=False)
    (DOC/'evidence.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    text=['# A→B 联调证据（2026-10-20）','', '所有Android操作均在同一个模拟器。原A APK测试、独立发送器测试与真机未验证三者分开；未复标阈值。','',
          '## 原版A APK实际行为','', '|操作|INFO|有效采样包|STOP|计算窗口|最终输出|','|---|---:|---:|---:|---:|---|']
    for c in before:
        text.append('|'+c['case']+'|'+'|'.join(str(c[k]) for k in ['infoDelta','blocksDelta','stopDelta','computedWindows'])+'|'+','.join(c['outputs'])+'|')
    text+=['','五个单次按钮均发送4000点后停止发送，靠B超时清理；0个有效分析窗口，不能称四方向识别通过。长按7秒也未延长4秒发送；A的“停”按钮发送静息波形，不是STREAM_STOP或人工急停。','',
           '上表是本轮源端口修复前的双App运行记录；修复后定向复测快速新流与提前松手。下述独立发送器与35项回归测试使用最终B构建，两个构建的SHA256分别保存在evidence.json。','',
           '## 同IP换源端口的新INFO兼容修复','', '|版本|INFO|有效采样包|STOP|拒收|','|---|---:|---:|---:|---|']
    for label,c in [('修复前',old),('修复后',new)]:text.append(f"|{label}|{c['infoDelta']}|{c['blocksDelta']}|{c['stopDelta']}|"+json.dumps(c['rejected'],ensure_ascii=False)+'|')
    text+=['','修复后仍未改变A的4秒/无STOP交互，不能因此宣称完整正常路径已通过。短触摸约1.2秒后松开，原A仍继续补满4秒、STOP计数不增加。具体时序见本地actual-a-fixed/observations.json。','',
           '## 独立发送器：冻结正常STOP路径','', '|数据方向|发送秒数|采样到的最终输出集合|B内部接收至同步失效ms|','|---|---:|---|---:|']
    for c in contract['cases']:text.append(f"|{c['label']}|{c['seconds']}|{','.join(c['outputs'])}|{c['stopAudit']['receiveToInvalidationMs']:.6f}|")
    audits=get(OUT/'contract-sender/final-status.json')['stopAudits'];values=[a['receiveToInvalidationMs'] for a in audits]
    text+=['',f"共{contract['checks']}项通信/清理检查，全部通过={contract['allChecksPassed']}。STOP计时样本{len(values)}个，范围{min(values):.6f}–{max(values):.6f}ms。样本很少，只是本轮观察，不是响应上限或真机保证。",
           '', '边界是B的socket.receive返回，到同步回调全部完成（本地最终STOP已写入）；不包含A触摸/网络、日志读取或Unity停车。', '',
           '这一路使用PC独立发送器，数据取自A资产，不是原A APK的松手行为。检查还覆盖短流反复启动和已处于运动时的静默超时；原始checks.json有逐项记录。','',
           '## 构建与未验证','',f"选定回归测试 {sum(int(t['tests']) for t in tests)} 项；失败/错误/跳过合计 {sum(int(t['failures'])+int(t['errors'])+int(t['skipped']) for t in tests)}。",
           '', '未完成A/B两手机网络、正常松手STOP版本兼容、原A单4秒的四方向识别和B→Unity联动。Stage2.6静息拼接2个误动、无STOP切换1.2～1.6秒残留继续未解决。', '',
           '详细源端口、报文数、STOP边界、测试版本和范围见同目录evidence.json；完整日志在本地output/ab-integration/，大型APK和波形不提交Git。']
    (DOC/'RESULTS.md').write_text('\n'.join(text)+'\n',encoding='utf-8')
    print(json.dumps(dict(tests=sum(int(t['tests']) for t in tests),stopCount=len(values),minStopMs=min(values),maxStopMs=max(values),before=old,after=new),ensure_ascii=False,indent=2))
if __name__=='__main__':main()
