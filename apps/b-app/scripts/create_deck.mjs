import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Presentation, PresentationFile } from 'file:///D:/CodexData/ToolRuntimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tmp = path.join(root, 'tmp', 'deck');
const out = path.join(root, 'output');
await fs.mkdir(tmp, {recursive:true});
const skill = 'D:/CodexData/Home/plugins/cache/openai-primary-runtime/presentations/26.1011.11113/skills/presentations';
const deck = Presentation.create({slideSize:{width:1280,height:720}});
const ink='#20343B', teal='#166A75', muted='#586B72', red='#B62635', bg='#F4F7F8';
function text(s, value, x,y,w,h,size=28,color=ink,bold=false) {
  const box=s.shapes.add({geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
  box.text=value; box.text.style={fontFamily:'Microsoft YaHei',fontSize:size,color,bold};
  return box;
}
function slide(title, note='') {
  const s=deck.slides.add();s.background.fill=bg;
  text(s,title,64,48,1140,84,44,ink,true);
  text(s,`${deck.slides.items.length} / 6`,1130,670,90,24,16,muted);
  s.speakerNotes.text=note;
  return s;
}
async function screen(s,name,x=890,y=142,w=250,h=510) {
  const bytes=await fs.readFile(path.join(out,'screenshots',name+'.png'));
  s.images.add({blob:new Uint8Array(bytes),contentType:'image/png',alt:'安卓模拟器实际运行截图：'+name,fit:'contain',position:{left:x,top:y,width:w,height:h}});
}
let s=slide('脑控轮椅安卓上位机', '项目范围依据用户提供的《脑控轮椅App开发实施手册》第1、9、10章。本演示不代表已经完成真实EEG或真实轮椅验证。');
text(s,'教学原型与模拟闭环',64,190,750,78,42,teal,true);
text(s,'连接设备，发送方向指令，接收状态反馈。\n用可运行的软件完成第一阶段验证。',64,302,740,140,31);
text(s,'Kotlin / Android 7.0+\n两个月课程项目 · 五人小组\n软件演示阶段，实机验证待补充',64,516,750,108,23,muted);
await screen(s,'moving',925,128,264,530);

s=slide('第一版的责任边界','依据实施手册第1章。源码：MainActivity.kt、CommManager.kt、IntentGate.kt。蓝牙代码已经实现，但没有安卓手机和SPP硬件，因此不能声称完成真实蓝牙联调。');
text(s,'本版实现',64,175,490,60,32,teal,true);
text(s,'连接、控制、状态三个页面\n内置模拟轮椅与协议收发\n经典蓝牙 SPP 接入入口\n停止、急停锁定与状态日志',64,266,510,290,30);
text(s,'仍需设备与接口',690,175,510,60,32,red,true);
text(s,'真实手机与蓝牙设备联调\n厂家通信协议适配\n真实 EEG 识别结果接入\n真实轮椅与物理急停验证',690,266,510,290,30);

s=slide('固定七字节通信协议','依据项目 docs/通信协议说明书-v1.0.md。手册把四项状态放进单字节ARG存在容量歧义，本版拆分为81、84、85、83四类帧。该协议为教学自定义协议，未获厂家确认。');
text(s,'AA 55  CMD  ARG  XOR  0D 0A',64,188,800,72,38,teal,true);
text(s,'前进 30%：AA 55 01 1E 1F 0D 0A\n\n逐字节解析，处理半帧、粘包和噪声。\n状态拆为电量、速度、运行状态、故障码。\nACK 表示接受指令，不证明物理动作完成。',64,305,785,300,27);
await screen(s,'status');

s=slide('停止规则与恢复条件','依据源码 ChairEngine.kt、CommManager.kt。500ms是下位机逻辑阈值，实际还受调度与通信延迟影响，不是硬实时认证。屏幕急停按钮不能替代物理急停。');
text(s,'按住才动，松手停止',64,178,780,58,34,teal,true);
text(s,'每 200ms 发送心跳与有效运动指令。\n任一超过 500ms 未更新，模拟下位机停车锁定。',64,254,780,114,27);
text(s,'急停后需要明确解除',64,424,780,58,34,red,true);
text(s,'解除后保持停止，再次操作才会移动。\n应用进入后台会断开，重连不恢复旧动作。',64,502,780,114,27);
await screen(s,'emergency');

const checks=JSON.parse(await fs.readFile(path.join(out,'emulator-checks.json'),'utf8'));
const passed=checks.filter(x=>x.passed).length;
s=slide('验证结果与适用范围','实际证据：Kotlin JUnit XML、simulator/chair_sim.py --self-test、output/emulator-checks.json、output/BrainChair-demo.mp4。测试记录中列出详细范围。未测试的硬件场景不能计为通过。');
text(s,'7 项',64,190,340,112,64,teal,true);
text(s,'Kotlin 协议与安全逻辑\n全部通过',64,315,345,120,27);
text(s,'4 项',482,190,340,112,64,teal,true);
text(s,'Python 模拟下位机测试\n全部通过',482,315,345,120,27);
text(s,`${passed} 项`,900,190,320,112,64,teal,true);
text(s,'模拟器操作检查\n详见运行记录',900,315,320,120,27);
text(s,'已生成调试 APK、源码、协议说明和模拟演示录像。\n尚未验证真实蓝牙、真实 EEG、真实轮椅和 Android 7.0 设备。',64,532,1130,106,28,muted);

s=slide('两个月内的后续安排','依据 docs/团队计划.md。先由老师确认仿真验收标准及硬件提供时间。远程私有仓库待组长确定账号，当前仅本地Git仓库。每位成员须能运行和解释自己负责的模块。');
text(s,'第 1–2 周',64,182,270,55,30,teal,true);
text(s,'每人独立运行工程，理解协议和控制流程。',350,182,855,70,30);
text(s,'第 3–4 周',64,300,270,55,30,teal,true);
text(s,'获取安卓手机与 SPP 设备，补充真实连接测试。',350,300,855,85,30);
text(s,'第 5–6 周',64,428,270,55,30,teal,true);
text(s,'有厂家接口后再适配轮椅或 EEG 识别结果。',350,428,855,85,30);
text(s,'第 7–8 周',64,556,270,55,30,teal,true);
text(s,'修复、复测、补录演示与答辩演练。',350,556,855,85,30);

await (await PresentationFile.exportPptx(deck)).save(path.join(tmp,'candidate.pptx'));
for (const [i,sl] of deck.slides.items.entries()) {
  const png=await deck.export({slide:sl,format:'png',scale:1});
  await fs.writeFile(path.join(tmp,`slide-${i+1}.png`),new Uint8Array(await png.arrayBuffer()));
}
const { finalizePresentation } = await import(pathToFileURL(path.join(skill,'container_tools/artifact_tool_utils.mjs')).href);
await finalizePresentation({workspaceDir:root,candidatePath:path.join(tmp,'candidate.pptx'),finalPath:path.join(out,'BrainChair-答辩初稿.pptx'),pythonExecutable:'D:/CodexData/ToolRuntimes/codex-primary-runtime/dependencies/python/python.exe',integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit'],fontPolicy:{basis:'design',families:['Microsoft YaHei']},explicitTotalSlideCount:6,verifyArtifactToolImport:true,receiptPath:path.join(tmp,'validation.json')});
console.log('Presentation ready');
