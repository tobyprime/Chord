#!/usr/bin/env python3
"""口琴采样网格重建（0.7.14）：3 锚点 ±12 半音拉伸 → 12 锚点 ≤±2 拉伸。

「电子感」的根因是变速重采样：旧版 C3/E4/G5 三锚点各拉伸 ±1 个八度覆盖
C2~G6，共振峰整体被拉飞。VCSL Hohner Super 64（同 patch、CC0）还有
C/E/G × 各八度共 13 个录音（标称低一个八度，实际 C3~C7），取 12 个把网格
加密到 3~4 半音间距，拉伸降到 ≤±2（透明）。

管线沿用 0.7.8b 口径：highpass 40 / −45dB 静音去除 / 起振裁切（首个 5ms 窗
RMS ≥ 峰值−12dB）/ 4ms 淡入 / 限长 / 0.15s 淡出 / −3dBFS / vorbis q5 mono 44.1k。
逐音 FFT 实测基频（VCSL 八度标注偏差必须实测，不能照抄文件名），并产出
instruments.yml voices 片段与 README 表格行。

用法：python tools/harmonica-rebuild.py [限长秒数，默认 3.0]
"""
import math
import subprocess
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "downloads/harm-src"
OUT = ROOT / "assets/resourcepack/assets/chord/sounds/harmonica"
FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")
VCSL_DIR = "Aerophones/Free%20Aerophones/Harmonica-Hohner-Super64/Sustains/Normal"
VCSL_REF = "c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e"
SR = 22050
THRESHOLD_DB = -12.0
FADE_IN_S = 0.004
MAX_S = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0
# 响度对齐（0.7.14）：12 个相邻音的 RMS 散布 −10.6~−15.8dB（纯峰值归一会让人
# 觉得音阶一响一轻）。目标 = 旧三层成品的平均 RMS，保持与钢琴/鼓的混音平衡；
# 峰值用 alimiter 钳 −2.5dBFS（高波峰因子文件少量瞬态增益折减，持续音无感）
TARGET_RMS_DB = -11.3
PEAK_CEIL_DB = -2.5

# (VCSL 文件名, 输出音名, 相对 C4 半音)。标称比科学音名低一个八度。
FILES = [
    ("Hohner-Super64_Normal _C2.wav", "c3", -12),
    ("Hohner-Super64_Normal _E2.wav", "e3", -8),
    ("Hohner-Super64_Normal _G2.wav", "g3", -5),
    ("Hohner-Super64_Normal _C3.wav", "c4", 0),
    ("Hohner-Super64_Normal _E3.wav", "e4", 4),
    ("Hohner-Super64_Normal _G3.wav", "g4", 7),
    ("Hohner-Super64_Normal _C4.wav", "c5", 12),
    ("Hohner-Super64_Normal _E4.wav", "e5", 16),
    ("Hohner-Super64_Normal _G4.wav", "g5", 19),
    ("Hohner-Super64_Normal _C5.wav", "c6", 24),
    ("Hohner-Super64_Normal _E5.wav", "e6", 28),
    ("Hohner-Super64_Normal _G5.wav", "g6", 31),
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
    return struct_unpack(r.stdout)


def struct_unpack(buf):
    import struct
    n = len(buf) // 2
    return struct.unpack(f"<{n}h", buf[: n * 2])


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


def rms_db(path):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-af", "astats=metadata=0",
                        "-f", "null", "-"], capture_output=True, text=True)
    import re
    m = re.findall(r"RMS level dB: ([-\d.]+)", r.stderr)
    if not m:
        raise RuntimeError("测不到 RMS")
    return float(m[-1])


def rms_align(path):
    """整文件 RMS 对齐到目标响度；峰值超限走 alimiter（少量瞬态折减）。"""
    cur_rms = rms_db(path)
    cur_peak = peak_dbfs(path)
    gain = TARGET_RMS_DB - cur_rms
    limit = 10 ** (PEAK_CEIL_DB / 20)
    af = f"volume={gain:.2f}dB"
    if cur_peak + gain > PEAK_CEIL_DB:
        # level=false：关 alimiter 默认的自动电平提升（否则输出被推到 0dBFS）
        af += f",alimiter=limit={limit:.4f}:attack=1:release=50:level=false"
    tmp = path.with_suffix(".rms.ogg")
    run(["-i", path, "-af", af, "-c:a", "libvorbis", "-q:a", "5", "-ar", "44100", "-ac", "1"], tmp)
    tmp.replace(path)
    return rms_db(path), peak_dbfs(path)


def duration_s(path):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-f", "null", "-"],
                       capture_output=True, text=True)
    for line in r.stderr.splitlines():
        if "Duration:" in line:
            t = line.split("Duration:")[1].split(",")[0].strip()
            h, m, s = t.split(":")
            return int(h) * 3600 + int(m) * 60 + float(s)
    return 0.0


def attack_cut_s(path):
    """起振裁切点：首个 5ms 窗 RMS ≥ 峰值−12dB（静音去除后计）。"""
    x = load(path)
    win = int(0.005 * SR)
    peak = max(abs(v) for v in x) or 1
    floor = peak * 10 ** (THRESHOLD_DB / 20)
    for i in range(0, min(len(x) - win, int(1.0 * SR)), win):
        rms = math.sqrt(sum(v * v for v in x[i : i + win]) / win)
        if rms >= floor:
            return i / SR
    return 0.0


def measure_f0(path, expected_hz):
    """稳态窗（0.3~1.0s）FFT 峰 + 抛物线插值；限频段 ±25% 防泛音/次谐波带偏。"""
    import cmath
    x = load(path)
    seg = x[int(0.3 * SR) : int(1.0 * SR)]
    n = 1
    while n < len(seg) * 2:
        n <<= 1
    buf = [complex(v * (0.5 - 0.5 * math.cos(2 * math.pi * i / (len(seg) - 1))), 0) for i, v in enumerate(seg)]
    while len(buf) < n:
        buf.append(0j)
    # 基-2 FFT
    bits = n.bit_length() - 1
    rev = [0] * n
    for i in range(n):
        rev[i] = (rev[i >> 1] >> 1) | ((i & 1) << (bits - 1))
    a = buf
    for i in range(n):
        if i < rev[i]:
            a[i], a[rev[i]] = a[rev[i]], a[i]
    length = 2
    while length <= n:
        ang = -2 * math.pi / length
        wl = cmath.exp(1j * ang)
        for i in range(0, n, length):
            w = 1 + 0j
            half = length // 2
            for k in range(i, i + half):
                u = a[k]
                v = a[k + half] * w
                a[k] = u + v
                a[k + half] = u - v
                w *= wl
        length <<= 1
    lo = int(expected_hz / 1.25 / SR * n)
    hi = int(expected_hz * 1.25 / SR * n) + 1
    best = max(range(lo, hi), key=lambda k: abs(a[k]))
    l_mag, m_mag, r_mag = abs(a[best - 1]), abs(a[best]), abs(a[best + 1])
    denom = l_mag + m_mag + r_mag or 1
    delta = 0.5 * (l_mag - r_mag) / (l_mag - 2 * m_mag + r_mag) if (l_mag - 2 * m_mag + r_mag) != 0 else 0
    bin_hz = SR / n
    return (best + delta) * bin_hz


def download():
    SRC.mkdir(parents=True, exist_ok=True)
    for src_name, _, _ in FILES:
        target = SRC / src_name
        if target.exists() and target.stat().st_size > 10000:
            continue
        print(f"下载 {src_name} …")
        url = (f"repos/sgossner/VCSL/contents/{VCSL_DIR}/{src_name.replace(' ', '%20')}"
               f"?ref={VCSL_REF}")
        r = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github.raw", url],
                           capture_output=True)
        if r.returncode != 0 or len(r.stdout) < 10000:
            raise RuntimeError(f"下载失败 {src_name}: {r.stderr[:200] or r.stdout[:80]}")
        target.write_bytes(r.stdout)
        print(f"  {target.stat().st_size // 1024} KB")


NOTE_NAMES = {0: "C", 1: "C♯", 2: "D", 3: "D♯", 4: "E", 5: "F", 6: "F♯",
              7: "G", 8: "G♯", 9: "A", 10: "A♯", 11: "B"}


def note_name(semitone):
    octave = 4 + (semitone + 120) // 12 - 10  # semitone 相对 C4
    return f"{NOTE_NAMES[(semitone + 120) % 12]}{octave}"


def main():
    download()
    print(f"\n== 加工（限长 {MAX_S}s）==")
    results = []
    for src_name, stem, semi in FILES:
        src = SRC / src_name
        out = OUT / f"harmonica_{stem}.ogg"
        tmp1 = OUT / f".{stem}.s.wav"
        strip = ("silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.005,"
                 "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,areverse")
        run(["-i", src, "-af", f"highpass=f=40,{strip}", "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp1)
        avail = duration_s(tmp1)
        cut = attack_cut_s(tmp1)
        cap = min(MAX_S, max(0.5, avail - cut))
        run(["-i", tmp1, "-af",
             f"atrim={cut:.3f},asetpts=PTS-STARTPTS,atrim=0:{cap:.2f},"
             f"afade=t=in:st=0:d={FADE_IN_S},afade=t=out:st={cap - 0.15:.2f}:d=0.15",
             "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp1.with_suffix(".c.wav"))
        run(["-i", tmp1.with_suffix(".c.wav"), "-af", "anull", "-c:a", "libvorbis", "-q:a", "5",
             "-ar", "44100", "-ac", "1"], out)
        norm_to(out, -3.0)
        final_rms, final_peak = rms_align(out)
        expected = 261.626 * 2 ** (semi / 12)
        f0 = measure_f0(out, expected)
        cents = 1200 * math.log2(f0 / expected)
        if abs(cents) > 50:
            raise RuntimeError(f"{stem}: 实测 {f0:.1f}Hz 偏离标称 {cents:+.0f} 音分，中止（防八度误判）")
        base_pitch = 261.626 / f0
        results.append((stem, semi, f0, cents, base_pitch, cut, avail, out.stat().st_size))
        tmp1.unlink(missing_ok=True)
        tmp1.with_suffix(".c.wav").unlink(missing_ok=True)
        print(f"  {out.name}: 源 {avail:.1f}s 裁起振 {cut*1000:.0f}ms → {cap:.1f}s  "
              f"f0={f0:.1f}Hz({cents:+.0f}¢)  base-pitch={base_pitch:.7f}  "
              f"RMS={final_rms:.1f} peak={final_peak:.1f}")

    # 逐半音就近路由 → 每层 min/max
    semis = [r[1] for r in results]
    assign = {}
    for s in range(-24, 32):
        best = min(semis, key=lambda a: (abs(a - s), a))
        assign.setdefault(best, []).append(s)

    print("\n== instruments.yml voices 片段（实测 base-pitch）==")
    for stem, semi, f0, cents, base_pitch, _, _, _ in results:
        rng = assign.get(semi)
        print(f"    - {{ min: {rng[0]:>3}, max: {rng[-1]:>3}, sound: \"chord:harmonica.{stem}.%d\",  base-pitch: {base_pitch:.7f} }}  # {note_name(semi)} 实测")

    print("\n== 实测汇总 ==")
    for stem, semi, f0, cents, base_pitch, cut, avail, size in results:
        print(f"  {stem:3} {note_name(semi):4}  f0={f0:7.1f}Hz ({cents:+3.0f}¢)  "
              f"源{avail:4.1f}s 裁{cut*1000:3.0f}ms  {size//1024}KB")


if __name__ == "__main__":
    sys.exit(main())
