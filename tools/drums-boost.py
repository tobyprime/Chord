#!/usr/bin/env python3
"""架子鼓响度对齐（0.7.11c）：文件电平是唯一响度杠杆。

**关键事实（0.7.11b 实测视频纠错）**：原版 playSound 的 volume>1 只扩大可听距离
（16×volume 格），**不增加响度**（增益钳制在 1.0）——instruments.yml 的
drums volume 1.6 从未带来实际增益；响度对齐必须落在文件 RMS 上。

目标（前 200ms RMS，文件级）：
- 主鼓件（kick/snare/toms/hat_open/hat_pedal/ride/crash）→ −9dB
  （新钢琴起振 −11.5~−12.4dB，打击乐应明显盖过持续乐）
- 角色件（hat_closed/rim，天然细碎）→ −13dB

增益后统一 alimiter(limit=0.891≈−1dBFS, attack=3ms, release=40ms) 重限幅
（限幅抬持续段，感知响度还会再涨一点）。在 0.7.2e 母带成品（含大鼓合成
鼓槌层）基础上逐次处理，不回源重建。脚本按「目标−当前」算增益，可重复跑。
"""
import shutil
import os
import math
import struct
import subprocess
from pathlib import Path

FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")
DIR = Path("assets/resourcepack/assets/chord/sounds/drums")
SR = 44100
TARGET_MAIN = -9.0
TARGET_SOFT = -13.0
SOFT = {"drums_hat_closed.ogg", "drums_rim.ogg"}


def run(args, out):
    cmd = [str(FF), "-hide_banner", "-y", *[str(a) for a in args], str(out)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr[-300:])


def rms200(path):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-ac", "1", "-ar", str(SR),
                        "-f", "s16le", "-"], capture_output=True)
    x = struct.unpack(f"<{len(r.stdout)//2}h", r.stdout)
    w = int(0.2 * SR)
    seg = x[:w]
    return 20 * math.log10(math.sqrt(sum(v * v for v in seg) / len(seg)) / 32768 + 1e-9)


def main():
    for f in sorted(DIR.glob("*.ogg")):
        target = TARGET_SOFT if f.name in SOFT else TARGET_MAIN
        cur = rms200(f)
        gain = round(target - cur, 1)
        tmp = f.with_suffix(".boost.ogg")
        run(["-i", f, "-af",
             f"volume={gain:+.1f}dB,alimiter=limit=0.891:attack=3:release=40",
             "-c:a", "libvorbis", "-q:a", "5", "-ar", "44100", "-ac", "1"], tmp)
        tmp.replace(f)
        print(f"  {f.name:24s} {cur:6.1f} → {rms200(f):6.1f} dB（{gain:+.1f}dB）")


if __name__ == "__main__":
    main()
