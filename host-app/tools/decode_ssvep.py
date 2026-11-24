"""
从公开 SSVEP 数据集解出轮椅指令（CCA / FBCCA，无需训练）。

数据来源
--------
Dong2023 —— 59 被试 40 类 SSVEP 数据集，经 NEMAR 分发（BIDS 格式，原始仓库在 Zenodo）。
  Dong, Y. & Tian, S. (2023). A large database towards user-friendly SSVEP-based BCI.
  Brain Science Advances, 9(4), 297-309. DOI: 10.26599/BSA.2023.9050020
  DOI: 10.82901/nemar.nm000128   License: CC BY-NC 4.0（非商用，需署名）

采集条件：NeuSenW (Neuracle)、8 导联（POz PO3 PO4 PO7 PO8 Oz O1 O2）、250 Hz、
参考 Fp1、刺激 8.0–15.8 Hz（步进 0.2 Hz，JFPM）、单试次 4 s、每类 4 次。

用法
----
    python tools/decode_ssvep.py --subjects 1 2 3

输出
----
    data/eeg-track.json     前端回放用的解码轨道
    data/decode-report.md   准确率与相关值分布（可直接贴进 README）
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys

import numpy as np
import scipy.io as sio
from scipy.signal import butter, filtfilt, iirnotch

FS = 250.0
N_HARM = 3          # CCA 参考信号谐波数
SUB_BANDS = [(6, 90), (14, 90), (22, 90), (30, 90), (38, 90)]  # FBCCA 子带

# 选中的 5 个刺激频率 → 轮椅指令。挑得比较开，避开相邻 0.2 Hz 的混淆。
TARGETS = [
    {"hz": 12.0, "key": "forward",  "name": "前进"},
    {"hz": 10.0, "key": "left",     "name": "左转"},
    {"hz": 15.0, "key": "stop",     "name": "停止"},
    {"hz": 13.0, "key": "right",    "name": "右转"},
    {"hz": 9.0,  "key": "backward", "name": "后退"},
]


# ────────────────────────────── 读数据 ──────────────────────────────
def load_subject(path: str) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """返回 (data[ch, n], 事件起始样点[0-based], 通道名)。"""
    f = sio.loadmat(path, squeeze_me=True, struct_as_record=False)
    data = np.asarray(f["data"], dtype=np.float64)
    ev = f["event"]
    lat = np.atleast_1d([int(e.latency) for e in ev]) - 1   # EEGLAB 是 1-based
    labels = [str(e.type) for e in np.atleast_1d(ev)]
    chans = [str(c.labels) for c in np.atleast_1d(f["chanlocs"])]
    return data, lat, labels, chans


# ──────────────────────────── 预处理 ────────────────────────────
def prefilter(x: np.ndarray, fs: float = FS) -> np.ndarray:
    b, a = iirnotch(50.0, 30.0, fs)
    x = filtfilt(b, a, x, axis=-1)
    b, a = butter(4, [6.0 / (fs / 2), 40.0 / (fs / 2)], btype="band")
    return filtfilt(b, a, x, axis=-1)


def bandpass(x: np.ndarray, lo: float, hi: float, fs: float = FS) -> np.ndarray:
    hi = min(hi, fs / 2 - 1)
    b, a = butter(4, [lo / (fs / 2), hi / (fs / 2)], btype="band")
    return filtfilt(b, a, x, axis=-1)


# ─────────────────────────────── CCA ───────────────────────────────
def reference(freq: float, n: int, fs: float = FS, n_harm: int = N_HARM) -> np.ndarray:
    t = np.arange(n) / fs
    rows = []
    for h in range(1, n_harm + 1):
        if h * freq >= fs / 2:
            break
        rows.append(np.sin(2 * np.pi * h * freq * t))
        rows.append(np.cos(2 * np.pi * h * freq * t))
    return np.vstack(rows)


def cca_score(x: np.ndarray, y: np.ndarray) -> float:
    """最大典型相关（用 QR 把两侧投影到列空间再 SVD，数值稳、比求逆快）。"""
    x = x - x.mean(axis=1, keepdims=True)
    y = y - y.mean(axis=1, keepdims=True)
    qx, _ = np.linalg.qr(x.T)
    qy, _ = np.linalg.qr(y.T)
    s = np.linalg.svd(qx.T @ qy, compute_uv=False)
    return float(s[0])


def classify_cca(x: np.ndarray, freqs: list[float]) -> np.ndarray:
    """单频带 CCA：返回每个候选频率的最大相关系数。"""
    n = x.shape[-1]
    return np.array([cca_score(x, reference(f, n)) for f in freqs])


def classify_fbcca(x: np.ndarray, freqs: list[float]) -> np.ndarray:
    """滤波器组 CCA：子带按 (1 - k^-1.25) 加权，取各子带 r² 的加权和。"""
    n = x.shape[-1]
    # Chen et al. 2015, w(k) = k^-1.25 + 0.25
    weights = np.array([k ** -1.25 + 0.25 for k in range(1, len(SUB_BANDS) + 1)])
    weights = weights / weights.sum()
    refs = [reference(f, n, n_harm=5) for f in freqs]

    score = np.zeros(len(freqs))
    for w, (lo, hi) in zip(weights, SUB_BANDS):
        xb = bandpass(x, lo, hi)
        for i, r in enumerate(refs):
            score[i] += w * cca_score(xb, r) ** 2
    return np.sqrt(score)


# ───────────────────────────── 主流程 ─────────────────────────────
def build_trials(data, lat, labels, window_s, offset_s=0.2):
    """切出每个试次的分析窗，返回 [(试次号, 频率标签, 样本[ch, n])]。"""
    n0 = int(round(offset_s * FS))
    n = int(round(window_s * FS))
    trials = []
    for i, (s, lab) in enumerate(zip(lat, labels)):
        seg = data[:, s + n0: s + n0 + n]
        if seg.shape[-1] < n:
            continue
        trials.append((i, float(lab), seg))
    return trials


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="tools/raw", help="原始 .set 所在目录")
    ap.add_argument("--subjects", nargs="+", type=int, default=[1])
    ap.add_argument("--window", type=float, default=3.0, help="分析窗长度（秒）")
    ap.add_argument("--out", default="data/eeg-track.json")
    ap.add_argument("--report", default="data/decode-report.md")
    args = ap.parse_args()

    freqs = [t["hz"] for t in TARGETS]
    key_of = {t["hz"]: t["key"] for t in TARGETS}

    records, snippets = [], []
    per_sub_acc = {}

    for si in args.subjects:
        path = os.path.join(args.raw, f"sub-{si}_eeg.set")
        if not os.path.exists(path):
            print(f"跳过 sub-{si}：找不到 {path}")
            continue
        data, lat, labels, chans = load_subject(path)
        trials = [t for t in build_trials(data, lat, labels, args.window)
                  if round(t[1], 1) in key_of]
        n_ok = 0
        for idx, hz, seg in trials:
            seg = prefilter(seg)
            sc_cca = classify_cca(seg, freqs)
            sc_fb = classify_fbcca(seg, freqs)

            pred_i = int(np.argmax(sc_fb))
            true_i = freqs.index(round(hz, 1))
            rho = float(sc_cca[true_i])                  # 真实目标上的相关值
            rho_pred = float(sc_cca[pred_i])             # 判为目标的那个上的相关值
            second = float(np.partition(sc_cca, -2)[-2]) # 次高
            ok = pred_i == true_i
            n_ok += ok

            records.append({
                "sub": si, "trial": idx, "block": (idx % 4) + 1,
                "hz": round(hz, 1),
                "true": key_of[round(hz, 1)],
                "pred": TARGETS[pred_i]["key"],
                "ok": ok,
                # 前端 ρ 用「判为目标的那一类」的相关值 —— 与文档里「获胜相关」同义
                "rho": round(rho_pred, 4),
                "rho_true": round(rho, 4),
                "rho2": round(second, 4),
                "fb": [round(float(v), 4) for v in sc_fb],
                "cca": [round(float(v), 4) for v in sc_cca],
            })

            # 波形缩略：Oz / POz 两导、降到 100 Hz、int8 量化，用于界面上的实时波形
            if len(snippets) < 400:
                pick = [chans.index(c) for c in ("Oz", "POz") if c in chans]
                w = seg[pick]
                w = w[:, ::5] if w.shape[-1] % 5 == 0 else w
                m = float(np.abs(w).max()) or 1.0
                q = np.round(w / m * 127).astype(np.int8)
                snippets.append({
                    "sub": si, "trial": idx, "fs": 50, "gain": round(m, 2),
                    "b64": base64.b64encode(q.tobytes()).decode(),
                })

        acc = n_ok / max(1, len(trials))
        per_sub_acc[si] = (acc, len(trials))
        print(f"sub-{si}: {len(trials)} 试次 · 准确率 {acc * 100:.1f}%")

    if not records:
        print("没有可用数据", file=sys.stderr)
        return 1

    ok = np.array([r["ok"] for r in records])
    rho = np.array([r["rho"] for r in records])          # 判为目标的那个类的相关值
    rho2 = np.array([r["rho2"] for r in records])
    acc_all = float(ok.mean())

    print(f"\n合计 {len(records)} 试次 · {len(TARGETS)} 类 · 平均准确率 {acc_all * 100:.1f}%")
    print(f"ρ 中位数 {np.median(rho):.3f} · 5% 分位 {np.percentile(rho, 5):.3f}")

    # ── 阈值标定：在「已知误判全部被拦住」的前提下，尽量少拒识 ──
    drho = rho - rho2
    p10 = float(np.percentile(rho[ok], 10))
    worst_err = float(rho[~ok].max()) if (~ok).any() else 0.0
    worst_err_d = float(drho[~ok].max()) if (~ok).any() else 0.0
    theta1_real = round(max(p10, worst_err + 0.02), 2)
    theta2_real = round(max(0.05, worst_err_d + 0.04), 2)
    theta1_real = float(np.clip(theta1_real, 0.30, 0.80))
    theta2_real = float(np.clip(theta2_real, 0.04, 0.30))
    print(f"\nθ₁ 标定：正确试次 ρ 的 10% 分位 {p10:.3f}，最差误判 ρ {worst_err:.3f} → θ₁={theta1_real:.2f}")
    print(f"θ₂ 标定：误判的最大 Δρ {worst_err_d:.3f} + 余量 → θ₂={theta2_real:.2f}")

    print("\n门控扫描（θ₂ 固定在标定值）：")
    sweep = []
    for th1 in (0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.63, 0.70):
        passed = (rho > th1) & (drho > theta2_real)
        prec = float(ok[passed].mean()) if passed.any() else 0.0
        sweep.append((th1, passed.mean(), prec, int(passed.sum())))
        print(f"  θ₁={th1:.2f}  通过 {passed.mean() * 100:5.1f}%  "
              f"通过者准确率 {prec * 100:5.1f}%  ({int(passed.sum())} 个)")

    per_class = {}
    for r in records:
        per_class.setdefault(r["true"], []).append(r["ok"])
    print("\n逐类准确率：")
    for t in TARGETS:
        v = per_class.get(t["key"], [])
        if v:
            print(f"  {t['name']}({t['hz']:>4.1f} Hz)  {sum(v)}/{len(v)}  {np.mean(v) * 100:5.1f}%")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    payload = {
        "meta": {
            "dataset": "Dong2023 · 59-subject 40-class SSVEP",
            "doi": ["10.26599/BSA.2023.9050020", "10.82901/nemar.nm000128"],
            "license": "CC BY-NC 4.0",
            "subjects": args.subjects,
            "fs": FS,
            "window_s": args.window,
            "channels": ["POz", "PO3", "PO4", "PO7", "PO8", "Oz", "O1", "O2"],
            "decoder": "FBCCA 判类 + 单频带 CCA 取相关值（均免训练）",
            "accuracy": round(acc_all, 4),
            "n_windows": len(records),
            "theta1_real": theta1_real,
            "theta2_real": theta2_real,
            "targets": TARGETS,
        },
        "windows": records,
        "snippets": snippets,
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"\n写出 {args.out} ({os.path.getsize(args.out) / 1024:.0f} KB)")

    if args.report:
        os.makedirs(os.path.dirname(args.report), exist_ok=True)
        write_report(args.report, args, records, sweep, theta1_real, theta2_real, acc_all, per_class)
        print(f"写出 {args.report}")
    return 0


def write_report(path, args, records, sweep, theta1_real, theta2_real, acc_all, per_class) -> None:
    ok = np.array([r["ok"] for r in records])
    rho = np.array([r["rho"] for r in records])
    rho2 = np.array([r["rho2"] for r in records])
    lines = [
        "# 真实脑电解码报告",
        "",
        "由 `tools/decode_ssvep.py` 自动生成，请勿手改。",
        "",
        "## 数据",
        "",
        f"- 数据集：Dong2023 · 59 被试 40 类 SSVEP（NEMAR `nm000128`，原始仓库 Zenodo `18847318`）",
        f"- 论文：Dong & Tian, *Brain Science Advances* 9(4):297-309, 2023 · DOI `10.26599/BSA.2023.9050020`",
        f"- 许可：**CC BY-NC 4.0**（非商用，需署名）",
        f"- 被试：{args.subjects}（本报告只用了其中 {len(set(r['sub'] for r in records))} 名）",
        f"- 采集：NeuSenW(Neuracle) · 8 导联枕区（POz PO3 PO4 PO7 PO8 Oz O1 O2）· 250 Hz · 参考 Fp1",
        f"- 刺激：8.0–15.8 Hz 步进 0.2 Hz，JFPM，单试次 4 s，每类 4 次",
        f"- 本系统取其中 {len(TARGETS)} 个频率对应 5 条指令",
        "",
        "## 解码设置",
        "",
        f"- 分析窗：刺激后 0.2 s 起 **{args.window:.1f} s**（对齐文档的 3 s 锚定窗）",
        "- 预处理：50 Hz 陷波 + 6–40 Hz 带通（判类用 FBCCA 自己的子带滤波）",
        "- 判类：FBCCA（5 子带，5 次谐波，权重 k^-1.25+0.25）",
        "- 置信度：单频带 CCA 在获胜目标上的最大典型相关 ρ —— 与文档「CCA 获胜相关」同义",
        "- **免训练**：CCA 系方法不需要训练集，不存在训练/测试泄漏",
        "",
        "## 结果",
        "",
        f"- 试次：**{len(records)}**，类别：{len(TARGETS)}",
        f"- 平均准确率：**{acc_all * 100:.1f} %**",
        "",
        "| 指令 | 刺激频率 | 正确/总数 | 准确率 |",
        "|---|---|---|---|",
    ]
    for t in TARGETS:
        v = per_class.get(t["key"], [])
        if v:
            lines.append(f"| {t['name']} | {t['hz']:.1f} Hz | {sum(v)}/{len(v)} | {np.mean(v) * 100:.0f} % |")
    lines += [
        "",
        f"ρ（获胜相关）中位数 {np.median(rho):.3f}，5 % 分位 {np.percentile(rho, 5):.3f}，"
        f"最大 {rho.max():.3f}。",
        "",
        "## 双门控在真实数据上的表现",
        "",
        "| θ₁ | 通过率 | 通过者准确率 | 通过数 |",
        "|---|---|---|---|",
    ]
    nearest = min(range(len(sweep)), key=lambda i: abs(sweep[i][0] - theta1_real))
    for i, (th1, rate, prec, n) in enumerate(sweep):
        mark = " ← 标定值" if i == nearest else ""
        lines.append(f"| {th1:.2f} | {rate * 100:.0f} % | {prec * 100:.1f} % | {n}{mark} |")
    passed = (rho > theta1_real) & ((rho - rho2) > theta2_real)
    rate, prec = float(passed.mean()), (float(ok[passed].mean()) if passed.any() else 0.0)
    n_err = int((~ok[passed]).sum())
    lines += [
        "",
        f"按「已知误判全部被拦住 + 留余量」标定，得到 **θ₁ = {theta1_real:.2f}、θ₂ = {theta2_real:.2f}**："
        f"通过率 **{rate * 100:.0f} %**，通过者准确率 **{prec * 100:.1f} %**（通过后被判错的 {n_err} 个）。",
        "",
        f"没通过的那 {len(records) - int(passed.sum())} 个窗里，有 "
        f"{int(((~passed) & ok).sum())} 个其实判对了、只是没敢确认 —— 界面上表现为停车，"
        "而不是走错方向。这正是双门控想要的行为。",
        "",
        "> 文档里的 θ₁=0.63 是给我方接收机（另一套滤波器与导联）标定的；"
        "CCA 相关值的绝对刻度随预处理、导联数、参考电极变化，换数据源必须重标。"
        "本报告的阈值由脚本按当前数据重算，多下几个被试重跑就会自动更新。"
        "这条差异在答辩时应主动说明，不要假装两套数字可以直接比。",
        "",
    ]
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


if __name__ == "__main__":
    raise SystemExit(main())
