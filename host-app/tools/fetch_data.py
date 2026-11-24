"""
下载公开 SSVEP 数据集的被试数据（NEMAR 直链，无需注册）。

数据集：Dong2023 · 59 被试 40 类 SSVEP
  DOI 10.82901/nemar.nm000128   License: CC BY-NC 4.0（非商用，需署名）
  原始仓库：Zenodo 18847318     论文 DOI: 10.26599/BSA.2023.9050020

每个被试一个 EEGLAB `.set`（约 6.9 MB，8 导联 · 250 Hz · 864 s · 160 试次），
下载到 tools/raw/ 下，随后由 decode_ssvep.py 读取。

用法：
    python tools/fetch_data.py                 # 默认下被试 1 2 3
    python tools/fetch_data.py --subjects 1 5 12
"""
from __future__ import annotations

import argparse
import os
import sys
import urllib.request

BASE = "https://data.nemar.org/nm000128/v1.0.3"
UA = {"User-Agent": "Mozilla/5.0 (compatible; brainchair-demo/1.0)"}


def url_for(sub: int, kind: str = "eeg") -> str:
    stem = f"sub-{sub}/ses-0/eeg/sub-{sub}_ses-0_task-ssvep_run-0"
    suffix = {"eeg": "_eeg.set", "events": "_events.tsv", "channels": "_channels.tsv"}[kind]
    return f"{BASE}/{stem}{suffix}"


def fetch(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
        print(f"  已存在，跳过 {os.path.basename(dest)}")
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r, open(tmp, "wb") as fh:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while chunk := r.read(1 << 16):
            fh.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                print(f"\r  {os.path.basename(dest)}  {done / 1048576:5.1f}/{total / 1048576:.1f} MB  {pct:3d}%",
                      end="", flush=True)
    print()
    os.replace(tmp, dest)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--subjects", nargs="+", type=int, default=[1, 2, 3])
    ap.add_argument("--raw", default=os.path.join("tools", "raw"))
    args = ap.parse_args()

    print(f"来源 {BASE}")
    print("许可 CC BY-NC 4.0 —— 仅限非商业用途，转载/展示需署名\n")
    for sub in args.subjects:
        print(f"sub-{sub}")
        fetch(url_for(sub, "eeg"), os.path.join(args.raw, f"sub-{sub}_eeg.set"))
        for kind in ("events", "channels"):
            try:
                fetch(url_for(sub, kind), os.path.join(args.raw, f"sub-{sub}_{kind}.tsv"))
            except Exception as e:  # 元数据缺失不影响解码
                print(f"  {kind} 下载失败（可忽略）：{e}")
    print("\n完成。接着跑：python tools/decode_ssvep.py --subjects", *args.subjects)
    return 0


if __name__ == "__main__":
    sys.exit(main())
