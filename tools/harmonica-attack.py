#!/usr/bin/env python3
"""口琴起振优化（0.7.8b）：快速演奏的「按下→发声」体感。

实测当前采样的起振斜坡：低/中音 100~150ms、高音 60~80ms 才爬到稳定电平
（吹气建立过程）。对快速演奏，这段斜坡就是迟滞的主体（V1 演奏者自听走
服务端广播，网络往返是架构固有，采样头部是唯一可砍的部分）。

处理：切到「音已成形」处（首个 5ms 窗 ≥ 峰值−12dB），4ms 淡入防咔哒，
其余管线与既有口径一致（highpass 40 / −45dB 静音去除 / 3.0s 限长 /
0.15s 淡出 / −3dBFS / vorbis q5 mono 44.1k）。

输入：downloads/harm-src/*.wav（gh api 重取，见 README 口琴章）
输出：assets/resourcepack/assets/chord/sounds/harmonica/*.ogg
"""
import math
import struct
import subprocess
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "downloads/harm-src"
OUT = ROOT / "assets/resourcepack/assets/chord/sounds/harmonica"
FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")
SR = 22050
THRESHOLD_DB = -12.0  # 起振裁切点：相对峰值
FADE_IN_S = 0.004

FILES = [
    ("Hohner-Super64_Normal _C2.wav", "harmonica_low_c3.ogg"),
    ("Hohner-Super64_Normal _E3.wav", "harmonica_mid_e4.ogg"),
    ("Hohner-Super64_Normal _G4.wav", "harmonica_high_g5.ogg"),
]


def run(args, out_file=None):
    cmd = [str(FF), "-hide_banner", "-y", *[str(a) for a in args]]
    if out_file:
        cmd.append(str(out_file))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg 失败: {' '.join(cmd)}\n{r.stderr[-400:]}")


def load(path):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-ac", "1", "-ar", str(SR),
                        "-f", "s16le", "-"], capture_output=True)
    n = len(r.stdout) // 2
    return struct.unpack(f"<{n}h", r.stdout[: n * 2])


def peak_dbfs(path):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-af", "volumedetect",
                        "-f", "null", "-"], capture_output=True, text=True)
    for line in r.stderr.splitlines():
        if "max_volume:" in line:
            return float(line.split("max_volume:")[1].replace("dB", "").strip())
    raise RuntimeError("测不到峰值")


def norm_to(path, target):
    cur = peak_dbfs(path)
    tmp = path.with_suffix(".norm.ogg")
    run(["-i", path, "-af", f"volume={target - cur:.2f}dB", "-c:a", "libvorbis", "-q:a", "5",
         "-ar", "44100", "-ac", "1"], tmp)
    tmp.replace(path)


def attack_cut_s(path):
    """找起振裁切点：首个 5ms 窗 RMS ≥ 峰值−12dB 的时刻（去掉静音后计）。"""
    x = load(path)
    win = int(0.005 * SR)
    peak = max(abs(v) for v in x) or 1
    floor = peak * 10 ** (THRESHOLD_DB / 20)
    for i in range(0, min(len(x) - win, int(1.0 * SR)), win):
        rms = math.sqrt(sum(v * v for v in x[i : i + win]) / win)
        if rms >= floor:
            return i / SR
    return 0.0


def process(src, out):
    tmp1 = out.with_suffix(".s.wav")
    tmp2 = out.with_suffix(".c.wav")
    strip = ("silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.005,"
             "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,areverse")
    run(["-i", src, "-af", f"highpass=f=40,{strip}", "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp1)
    cut = attack_cut_s(tmp1)
    run(["-i", tmp1, "-af",
         f"atrim={cut:.3f},asetpts=PTS-STARTPTS,atrim=0:3.0,"
         f"afade=t=in:st=0:d={FADE_IN_S},afade=t=out:st=2.85:d=0.15",
         "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp2)
    run(["-i", tmp2, "-af", "anull", "-c:a", "libvorbis", "-q:a", "5", "-ar", "44100", "-ac", "1"], out)
    tmp1.unlink()
    tmp2.unlink()
    norm_to(out, -3.0)
    print(f"  {out.name}: 裁切 {cut*1000:.0f}ms 起振  peak {peak_dbfs(out):.1f} dBFS")


def main():
    print("== 口琴起振裁切 ==")
    for src_name, out_name in FILES:
        process(SRC / src_name, OUT / out_name)
    # 起振验证：新文件前 60ms 每 10ms 窗相对电平
    print("== 起振验证（相对峰值 dB）==")
    for _s, out_name in FILES:
        x = load(OUT / out_name)
        peak = max(abs(v) for v in x) or 1
        win = int(0.01 * SR)
        line = []
        for i in range(6):
            rms = math.sqrt(sum(v * v for v in x[i * win : (i + 1) * win]) / win) or 1
            line.append(f"{i*10}ms:{20*math.log10(rms/peak):4.0f}")
        print(f"  {out_name}: " + "  ".join(line))


if __name__ == "__main__":
    sys.exit(main())
