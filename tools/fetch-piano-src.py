#!/usr/bin/env python3
"""从 VCSL（github.com/sgossner/VCSL，CC0）拉取 JHPiano Sus Close 源采样。

0.7.16 钢琴锚点加密：5 锚点（C1/C3/E4/G#5/C7，高音区最远拉满 ±12 半音 → 高音
chipmunk 失真）→ 14 锚点纯四度网格（每采样最大 ±3~4 半音）。本脚本下载新增
12 锚点 × 3 力度层（vl2/vl3/vl4，rr1）的源 wav；C3/C7 复用现有成品 ogg 不重下。

输出：downloads/piano-src/JHPiano_Sus_Close_{NOTE}_{vl}_rr1.wav
用法：python tools/fetch-piano-src.py   （需 gh CLI 已登录）
"""
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "downloads/piano-src"
REPO = "repos/sgossner/VCSL/contents/"
DIR = "Chordophones/Zithers/Grand Piano, Steinway B/Sus"

# 新网格锚点音名（源文件命名）；C3/C7 已有成品不取
NOTES = ["A#0", "F#1", "C2", "F#2", "F#3", "C4", "F#4", "C5", "F#5", "C6", "F#6", "F#7"]
VLS = ["vl2", "vl3", "vl4"]


def expected_size(path: str) -> int:
    api = f"{REPO}{quote(path)}"
    r = subprocess.run(["gh", "api", api, "--jq", ".size"],
                       capture_output=True, text=True)
    return int(r.stdout.strip())


def fetch(path: str, out: Path, size: int) -> bool:
    api = f"{REPO}{quote(path)}"
    r = subprocess.run(["gh", "api", api, "-H", "Accept: application/vnd.github.raw"],
                       capture_output=True)
    if r.returncode != 0 or len(r.stdout) != size:
        print(f"  失败 {out.name}: rc={r.returncode} got={len(r.stdout)} want={size}",
              file=sys.stderr)
        return False
    out.write_bytes(r.stdout)
    return True


def main() -> int:
    SRC.mkdir(parents=True, exist_ok=True)
    todo = [(f"{DIR}/JHPiano_Sus_Close_{n}_{v}_rr1.wav",
             SRC / f"JHPiano_Sus_Close_{n}_{v}_rr1.wav") for n in NOTES for v in VLS]
    failed = []
    for i, (path, out) in enumerate(todo, 1):
        if out.exists() and out.stat().st_size == expected_size(path):
            print(f"[{i}/{len(todo)}] 已存在 {out.name}")
            continue
        size = expected_size(path)
        print(f"[{i}/{len(todo)}] 下载 {out.name} ({size // 1024 // 1024} MB)…", flush=True)
        if fetch(path, out, size):
            print(f"  ok {len(out.read_bytes())} bytes")
        else:
            failed.append(path)
    if failed:
        print("失败清单:", *failed, sep="\n  ", file=sys.stderr)
        return 1
    print(f"完成：{len(todo)} 个源采样就绪 → {SRC}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
