#!/usr/bin/env python3
"""唢呐采样合成器（0.9.0）：11 锚点 C/E/G 网格，100% 原创合成、授权干净。

无现成 CC0 唢呐采样库（商业 Kong Audio 不免费；freesound 有登录墙；
GM 音源 Shanai 补丁采样来源不清），故本地合成——物理依据：唢呐 = 锥管 +
双簧，全谐波亮谱（近似锯齿波、上谐波段偏强）+ 喇叭口/锥管共振峰，标志性的
打音起振（音高上滑）与迟起颤音。合成配方：
  带限锯齿加法合成（1/n^tilt 谱 + 三共振峰 boost + 高频滚降，逐谐波随机初相）
  + 18ms 起振 / 6% 过冲 + 25ms 气息噪声瞬态 + 250ms 后渐入的 5.4Hz 颤音
  + 缓慢音高漂移，逐锚点换随机相位（固定种子，可复现）。
成品规格沿用口琴口径：vorbis q5 mono 44.1k / 峰值 −3dBFS / RMS −11.3dB
/ 限长 3.0s + 0.15s 淡出。逐音 FFT 实测基频（流程与真采样一致，不信任标称
值），产出 instruments.yml voices 片段、BASE_OGG 行与实测汇总。

日后拿到真人录音：照 tools/harmonica-rebuild.py 写 suona-rebuild.py 替换
本脚本产物即可，voices / base-pitch 由脚本重新生成，其余管线不变。

用法：python tools/suona-synth.py [限长秒数，默认 3.0]
"""
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets/resourcepack/assets/chord/sounds/suona"
FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")
MAX_S = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0

SR = 44100
TAU = 2 * math.pi
C4_HZ = 261.626
FADE_IN_S = 0.004
FADE_OUT_S = 0.15
PEAK_DB = -3.0
# 响度对齐（与口琴/钢琴同口径）：整文件 RMS −11.3dB，alimiter 钳 −2.5dBFS
TARGET_RMS_DB = -11.3
PEAK_CEIL_DB = -2.5
ANALYSIS_SR = 22050  # f0 测量解码率（与 harmonica-rebuild 一致）

# ---- 合成音色参数（集中在此，重渲染即调音，不动其他管线）----
SCOOP_DEPTH = 0.034       # 打音起振深度（≈ −60 音分，唢呐「打音」标志）
SCOOP_TAU_S = 0.020       # 上滑时间常数
ATTACK_S = 0.018          # 振幅起振
OVERSHOOT = 0.05          # 起振过冲（簧片咬合瞬态）
OVERSHOOT_TAU_S = 0.12
VIB_DELAY_S = 0.25        # 颤音延迟出现（直音起奏，颤音后至）
VIB_RAMP_S = 0.35
VIB_HZ = 5.4
VIB_CENTS = 13
DRIFT_CENTS = 2.5         # 缓慢音高漂移（organic，避免合成感）
FLUTTER = 0.015           # 振幅气颤
FLUTTER_HZ = 6.8
BREATH = 0.10             # 起振气息噪声（相对满幅，800~6000Hz 带通）
TILT_BASE = 0.85          # 谐波幅度 1/n^tilt：C4 口径（略亮于锯齿的 1.0）
TILT_PER_SEMI = 0.006     # 每半音略压谱（低音更亮、高音不过分刺耳）
FORMANTS = [              # 喇叭口/锥管共振峰 (Hz, Q, boost)
    (1050.0, 1.1, 1.4),
    (2500.0, 1.4, 0.7),
    (4800.0, 1.5, 0.15),
]
ROLLOFF_HZ = 7000.0
BANDLIMIT_HZ = 16000.0

# (音名 stem, 相对 C4 半音)。C/E/G 网格覆盖 [0, 31]，每采样最大拉伸 ≤±2 半音
ANCHORS = [
    ("c4", 0), ("e4", 4), ("g4", 7), ("a4", 9),
    ("c5", 12), ("e5", 16), ("g5", 19), ("a5", 21),
    ("c6", 24), ("e6", 28), ("g6", 31),
]
RANGE = (0, 31)


def run(args, out_file=None, stdin=None):
    cmd = [str(FF), "-hide_banner", "-y", *[str(a) for a in args]]
    if out_file:
        cmd.append(str(out_file))
    r = subprocess.run(cmd, capture_output=True, input=stdin)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg 失败: {' '.join(cmd)}\n{r.stderr[-400:]}")


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


def synthesize(f0, semi, seed, max_s):
    n = int(round(SR * max_s))
    t = np.arange(n) / SR
    rng = np.random.default_rng(20260 + semi)  # 固定种子：重渲染可复现

    # 频率轨迹：打音上滑 → 延迟颤音 → 缓慢漂移（相位积分后按谐波次数倍乘）
    inst = np.full(n, f0)
    inst *= 1.0 - SCOOP_DEPTH * np.exp(-t / SCOOP_TAU_S)
    vib = smoothstep((t - VIB_DELAY_S) / VIB_RAMP_S)
    inst *= 2 ** ((VIB_CENTS / 1200) * vib * np.sin(TAU * VIB_HZ * t))
    drift = (DRIFT_CENTS * np.sin(TAU * 0.31 * t + rng.uniform(0, TAU))
             + 0.6 * DRIFT_CENTS * np.sin(TAU * 0.73 * t + rng.uniform(0, TAU)))
    inst *= 2 ** (drift / 1200)
    phase = TAU * np.cumsum(inst) / SR

    # 谐波加法合成：1/n^tilt 谱 + 共振峰 boost + 高频滚降，逐谐波随机初相
    out = np.zeros(n)
    tilt = TILT_BASE + TILT_PER_SEMI * semi
    h = 1
    while h * f0 <= BANDLIMIT_HZ:
        g = h ** (-tilt)
        for fc, q, boost in FORMANTS:
            x = h * f0 / fc - fc / (h * f0)
            g *= 1.0 + boost / (1.0 + (q * x) ** 2)
        g /= 1.0 + (h * f0 / ROLLOFF_HZ) ** 2
        out += g * np.sin(h * phase + rng.uniform(0, TAU))
        h += 1

    # 振幅包络：起振 + 过冲回落 + 轻微气颤
    env = smoothstep(t / ATTACK_S) * (1.0 + OVERSHOOT * np.exp(-t / OVERSHOOT_TAU_S))
    env *= 1.0 + FLUTTER * np.sin(TAU * FLUTTER_HZ * t + rng.uniform(0, TAU)) \
        * smoothstep((t - 0.08) / 0.4)
    out *= env

    # 起振气息噪声：带通白噪 × 25ms 指数衰减（簧片/气息瞬态）
    noise = rng.standard_normal(n)
    spec = np.fft.rfft(noise)
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    spec[(freqs < 800) | (freqs > 6000)] = 0
    out += BREATH * np.fft.irfft(spec, n) * np.exp(-t / 0.025) * smoothstep(t / 0.002)

    # 4ms 硬淡入（防启动咔哒）+ 尾部余弦淡出
    out *= np.minimum(1.0, t / FADE_IN_S)
    fo = int(FADE_OUT_S * SR)
    out[-fo:] *= 0.5 * (1.0 + np.cos(np.linspace(0, np.pi, fo)))

    out -= out.mean()
    out *= 10 ** (PEAK_DB / 20) / max(abs(out.max()), abs(out.min()), 1e-9)
    return out


def encode(x, out_path):
    pcm = (np.clip(x, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    run(["-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "pipe:0",
         "-c:a", "libvorbis", "-q:a", "5", "-ar", "44100", "-ac", "1"], out_path, stdin=pcm)


def load(path):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-ac", "1", "-ar", str(ANALYSIS_SR),
                        "-f", "s16le", "-"], capture_output=True)
    import struct
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


def measure_f0(path, expected_hz):
    """稳态窗（0.3~1.0s）FFT 峰 + 抛物线插值；限频段 ±25% 防泛音/次谐波带偏。"""
    import cmath
    x = load(path)
    seg = x[int(0.3 * ANALYSIS_SR): int(1.0 * ANALYSIS_SR)]
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
    lo = int(expected_hz / 1.25 / ANALYSIS_SR * n)
    hi = int(expected_hz * 1.25 / ANALYSIS_SR * n) + 1
    best = max(range(lo, hi), key=lambda k: abs(a[k]))
    l_mag, m_mag, r_mag = abs(a[best - 1]), abs(a[best]), abs(a[best + 1])
    denom = l_mag + m_mag + r_mag or 1
    delta = 0.5 * (l_mag - r_mag) / (l_mag - 2 * m_mag + r_mag) if (l_mag - 2 * m_mag + r_mag) != 0 else 0
    bin_hz = ANALYSIS_SR / n
    return (best + delta) * bin_hz


NOTE_NAMES = {0: "C", 1: "C♯", 2: "D", 3: "D♯", 4: "E", 5: "F", 6: "F♯",
              7: "G", 8: "G♯", 9: "A", 10: "A♯", 11: "B"}


def note_name(semitone):
    octave = 4 + (semitone + 120) // 12 - 10  # semitone 相对 C4
    return f"{NOTE_NAMES[(semitone + 120) % 12]}{octave}"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"== 合成（限长 {MAX_S}s，vorbis q5 mono 44.1k）==")
    results = []
    for stem, semi in ANCHORS:
        f0 = C4_HZ * 2 ** (semi / 12)
        out = OUT / f"suona_{stem}.ogg"
        x = synthesize(f0, semi, 20260 + semi, MAX_S)
        encode(x, out)
        norm_to(out, PEAK_DB)
        final_rms, final_peak = rms_align(out)
        f0_measured = measure_f0(out, f0)
        cents = 1200 * math.log2(f0_measured / f0)
        if abs(cents) > 50:
            raise RuntimeError(f"{stem}: 实测 {f0_measured:.1f}Hz 偏离标称 {cents:+.0f} 音分，中止（防八度误判）")
        base_pitch = C4_HZ / f0_measured
        results.append((stem, semi, f0_measured, cents, base_pitch, final_rms, final_peak, out.stat().st_size))
        print(f"  {out.name}: f0={f0_measured:.1f}Hz({cents:+.0f}¢)  base-pitch={base_pitch:.7f}  "
              f"RMS={final_rms:.1f} peak={final_peak:.1f}  {out.stat().st_size // 1024}KB")

    # 逐半音就近路由 → voices min/max（与 harmonica-rebuild 同法，并列取低锚点）
    semis = [r[1] for r in results]
    assign = {}
    for s in range(RANGE[0], RANGE[1] + 1):
        best = min(semis, key=lambda a: (abs(a - s), a))
        assign.setdefault(best, []).append(s)

    print("\n== instruments.yml voices 片段（实测 base-pitch）==")
    for stem, semi, _, _, base_pitch, _, _, _ in results:
        rng = assign.get(semi)
        print(f"    - {{ min: {rng[0]:>2}, max: {rng[-1]:>2}, sound: \"chord:suona.{stem}.%d\", "
              f"base-pitch: {base_pitch:.7f} }}  # {note_name(semi)} 实测")

    print("\n== gen-sounds-json.py BASE_OGG 行 ==")
    for stem, _, _, _, _, _, _, _ in results:
        print(f'    "suona.{stem}": "suona/suona_{stem}",')

    print("\n== 实测汇总 ==")
    for stem, semi, f0, cents, bp, rms, peak, size in results:
        print(f"  {stem:3} {note_name(semi):4}  f0={f0:7.1f}Hz ({cents:+3.0f}¢)  "
              f"base-pitch={bp:.7f}  RMS={rms:.1f} peak={peak:.1f}  {size // 1024}KB")


if __name__ == "__main__":
    sys.exit(main())
