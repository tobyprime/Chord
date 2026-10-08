#!/usr/bin/env python3
"""从 rel-grades.json（tools/piano-endings.py 产出）重写 instruments.yml 的
钢琴 release-voices 段：等 dB 分档的 held-ms + sounds（t0..tn）+ 主采样实测
电平曲线（level-ms/level-db，平滑+单调化）+ 与 voices 一致的实测 base-pitch。

用法：python tools/emit-release-voices.py   （先跑 tools/piano-endings.py）
"""
import json
import math
import os
import re
import shutil
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
GRADES = ROOT / "downloads/piano-src/rel-grades.json"
YML = ROOT / "plugin/src/main/resources/instruments.yml"
OUT = ROOT / "assets/resourcepack/assets/chord/sounds/piano"
sys_path = ROOT / "tools"


def smooth3_min(dbs):
    out = list(dbs)
    for i in range(1, len(dbs) - 1):
        out[i] = (dbs[i - 1] + dbs[i] + dbs[i + 1]) / 3
    for i in range(1, len(out)):
        out[i] = min(out[i], out[i - 1])
    return [round(v, 2) for v in out]


def level_curve(ogg: Path, step_ms=200):
    """与 piano-endings.level_curve 相同的测量（此处独立实现避免 import 链）。"""
    import struct
    import subprocess
    FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")
    import numpy as np
    sr = 44100
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(ogg), "-ac", "1",
                        "-ar", str(sr), "-f", "s16le", "-"], capture_output=True)
    n = len(r.stdout) // 2
    x = np.frombuffer(r.stdout[: n * 2], dtype=np.int16).astype(np.float64)
    dur = len(x) / sr
    hi = min(3.0, dur - 0.1)
    win = int(0.1 * sr)
    ms, dbs = [], []
    for t_ms in range(0, int(hi * 1000), step_ms):
        t = t_ms / 1000.0
        seg = x[int(t * sr): int(t * sr) + win]
        if len(seg) < win // 2:
            break
        rms = math.sqrt(float(np.dot(seg, seg)) / len(seg)) / 32768.0
        if rms < 1e-6:
            break
        ms.append(t_ms)
        dbs.append(20 * math.log10(rms))
    return ms, smooth3_min(dbs)


def main():
    grades = json.loads(GRADES.read_text(encoding="utf-8"))
    d = yaml.safe_load(YML.read_text(encoding="utf-8"))
    voices = {}
    for v in d["piano"]["voices"]:
        key = re.search(r"piano\.([a-z0-9]+)\.%d", v["sound"]).group(1)
        voices[key] = v

    lines = YML.read_text(encoding="utf-8").splitlines(True)
    start = next(i for i, l in enumerate(lines) if l.strip() == "release-voices:")
    end = next(i for i in range(start + 1, len(lines)) if "CraftEngine" in lines[i])
    block = ["  # 制音尾音（0.7.16f 等 dB 分档）：松键制音/松踏板/闷音时，stopSound 硬切后\n",
             "  # 叠加同源收音。档位由 tools/piano-endings.py 沿主采样实测电平曲线每降 3dB\n",
             "  # 切一档（起点 0.18s 避开 attack 残余区，t0..tn），「素材位置≈保持时长」：\n",
             "  # 服务端 gradeFor 混合规则选档 + tailGain 按曲线插值折算 volume，把尾音的\n",
             "  # 电平/衰减斜率/音色三者接上松键那一刻。level-db 为 100ms 窗 RMS 三点平滑\n",
             "  # + 单调化。改档位/曲线一律重跑 piano-endings.py + 本脚本，勿手改。\n",
             "  release-voices:\n"]
    for key, held in grades.items():
        v = voices[key]
        lms, ldb = level_curve(OUT / f"piano_{key}.ogg")
        n = len(held)
        sounds = ", ".join(f'"chord:piano.rel.{key}.t{i}"' for i in range(n))
        block.append(f'    - {{ min: {v["min"]}, max: {v["max"]}, base-pitch: {v["base-pitch"]}, '
                     f'held-ms: [{", ".join(map(str, held))}],\n')
        block.append(f'        level-ms: [{", ".join(map(str, lms))}],\n')
        block.append(f'        level-db: [{", ".join(map(str, ldb))}],\n')
        block.append(f'        sounds: [{sounds}] }}\n')
    lines[start:end] = block
    YML.write_text("".join(lines), encoding="utf-8")

    dd = yaml.safe_load(YML.read_text(encoding="utf-8"))
    rv = dd["piano"]["release-voices"]
    assert len(rv) == len(grades)
    for row in rv:
        key = re.search(r"rel\.([a-z0-9]+)\.t0", row["sounds"][0]).group(1)
        assert len(row["sounds"]) == len(row["held-ms"]) == len(grades[key])
        assert len(row["level-ms"]) == len(row["level-db"])
        assert row["base-pitch"] == voices[key]["base-pitch"]
    print(f"release-voices 重写完成：{len(rv)} 锚点，档数 "
          f"{ {k: len(v) for k, v in grades.items()} }")
    return 0


if __name__ == "__main__":
    sys.exit(main())
