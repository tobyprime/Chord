#!/usr/bin/env python3
"""钢琴采样重建（0.7.16 锚点加密）：

背景：0.7.7 版只有 5 锚点（C1/C3/E4/G#5/C7），high 锚点（G#5）向上拉满 12 半音
（B5~G#6 一带 pitch 1.2~2.0）产生 chipmunk 失真，B5/C6/D6 最严重。
0.7.16 改为 14 锚点纯四度网格（A#0~F#7，源 VCSL JHPiano 每全音一个采样），
每个采样最大变调 ±3 半音（仅 D1/D#1 拉 -4、顶键 C8 拉 +6）。

1. 主采样：Sus Close 源（vl2 柔/vl3 常规/vl4 强），静音门控自然衰减（-45dB），
   上限 6s，-3dBFS 归一——与 0.7.7 管线一致。
2. 制音尾音：从主采样自适应位置切 0.65s + 陡淡出（比例 12%/30%/50%，上限
   0.5/1.5/2.5s——低音锚点与旧 TAIL_POSITIONS 全等，高音自动前移），不做归一，
   保留天然电平（0.7.8 教训）。
3. 基频实测：vl3 层逐锚点测主导频率（<C4 自相关 / ≥C4 FFT+抛物线插值），
   偏差 >5 音分时打印 base-pitch 校正值，供 instruments.yml 采用。
4. 旧 5 锚点产物：C3/C7 主采样改名复用（byte 级），其余（C1/E4/G#5 及旧 rel）
   归档 downloads/piano-old/，不删除（项目无 git，留回滚）。

输入：downloads/piano-src/JHPiano_Sus_Close_{NOTE}_{vl}_rr1.wav（tools/fetch-piano-src.py）
输出：assets/resourcepack/assets/chord/sounds/piano/piano[_soft|_loud]_{key}.ogg、
      piano_rel_{key}_{a|b|c}.ogg
用法：python tools/piano-endings.py
"""
import json
import math
import shutil
import struct
import subprocess
import os
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "downloads/piano-src"
OUT = ROOT / "assets/resourcepack/assets/chord/sounds/piano"
OLD = ROOT / "downloads/piano-old"
FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")

MAIN_CAP_S = 6.0
MAIN_TARGET_DBFS = -3.0

# (源音名, ogg 键, MIDI 号)；A440 标称基频由 MIDI 推出
ANCHORS = [
    ("A#0", "as0", 22), ("F#1", "fs1", 30), ("C2", "c2", 36), ("F#2", "fs2", 42),
    ("C3", "c3", 48), ("F#3", "fs3", 54), ("C4", "c4", 60), ("F#4", "fs4", 66),
    ("C5", "c5", 72), ("F#5", "fs5", 78), ("C6", "c6", 84), ("F#6", "fs6", 90),
    ("C7", "c7", 96), ("F#7", "fs7", 102),
]
LAYERS = [("", "vl3"), ("soft_", "vl2"), ("loud_", "vl4")]

# 旧 5 锚点产物 → 新命名的复用映射（参数未变的 byte 级拷贝）
REUSE = {
    "piano_low_c3.ogg": "piano_c3.ogg",
    "piano_soft_low_c3.ogg": "piano_soft_c3.ogg",
    "piano_loud_low_c3.ogg": "piano_loud_c3.ogg",
    "piano_top_c7.ogg": "piano_c7.ogg",
    "piano_soft_top_c7.ogg": "piano_soft_c7.ogg",
    "piano_loud_top_c7.ogg": "piano_loud_c7.ogg",
}
# 旧锚点中不再被新网格引用的主采样 + 旧 rel（rel_c3/c7 名字不变继续有效）
STALE = [f"piano_{p}{k}.ogg" for p in ("", "soft_", "loud_")
         for k in ("bottom_c1", "low_c3", "mid_e4", "high_gs5", "top_c7")
         if f"piano_{p}{k}.ogg" not in REUSE] + \
        [f"piano_rel_{k}_{g}.ogg" for k in ("c1", "e4", "gs5") for g in "abc"]


def run(args, out_file=None):
    cmd = [str(FF), "-hide_banner", "-y", *[str(a) for a in args]]
    if out_file:
        cmd.append(str(out_file))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg 失败: {' '.join(cmd)}\n{r.stderr[-500:]}")


def peak_dbfs(path: Path) -> float:
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-af", "volumedetect",
                        "-f", "null", "-"], capture_output=True, text=True)
    for line in r.stderr.splitlines():
        if "max_volume:" in line:
            return float(line.split("max_volume:")[1].replace("dB", "").strip())
    raise RuntimeError(f"测不到峰值: {path}")


def duration_s(path: Path) -> float:
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-f", "null", "-"],
                       capture_output=True, text=True)
    for line in r.stderr.splitlines():
        if line.startswith("  Duration:"):
            t = line.split()[1].rstrip(",")
            h, m, s = t.split(":")
            return int(h) * 3600 + int(m) * 60 + float(s)
    raise RuntimeError(f"测不到时长: {path}")


def norm_to(path: Path, target: float):
    cur = peak_dbfs(path)
    tmp = path.with_suffix(".norm.ogg")
    run(["-i", path, "-af", f"volume={target - cur:.2f}dB", "-c:a", "libvorbis", "-q:a", "5",
         "-ar", "44100", "-ac", "1"], tmp)
    tmp.replace(path)


def strip_and_prep(src: Path, tmp: Path):
    """归一 -1dBFS（让绝对 -45dB 阈值≈相对阈值）→ highpass 40 → 首尾静音去除。
    中间文件走 pcm_s16le，避免多次有损编码。"""
    cur = peak_dbfs(src)
    strip = ("silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,"
             "areverse,"
             "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,"
             "areverse")
    run(["-i", src, "-af", f"volume={-1.0 - cur:.2f}dB,highpass=f=40,{strip}",
         "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp)


def build_main(src: Path, out: Path):
    tmp = out.with_suffix(".tmp.wav")
    strip_and_prep(src, tmp)
    dur = duration_s(tmp)
    capped = dur > MAIN_CAP_S
    if capped:
        dur = MAIN_CAP_S
    fade_start = max(0.0, dur - (0.4 if capped else 0.15))
    run(["-i", tmp, "-af",
         f"atrim=0:{dur:.3f},asetpts=PTS-STARTPTS,"
         f"afade=t=in:st=0:d=0.01,afade=t=out:st={fade_start:.3f}:d={dur - fade_start:.3f}",
         "-c:a", "libvorbis", "-q:a", "5", "-ar", "44100", "-ac", "1"], out)
    tmp.unlink()
    norm_to(out, MAIN_TARGET_DBFS)
    print(f"  {out.name}: {duration_s(out):.2f}s{'（触顶截断）' if capped else '（自然衰减尽）'}"
          f" peak {peak_dbfs(out):.1f} dBFS")


def build_tail(main_ogg: Path, out: Path, seg_start: float, seg_len=0.65, fade_out=0.4,
               duck_db=0.0, duck_s=0.1):
    """制音尾音 = 主采样指定位置段 + 可选压头 + 陡淡出，不做归一（0.7.8：保留天然电平衔接）。

    压头（duck，0.8.1 试验后默认关闭）：起始压低 duck_db dB、duck_s 秒线性升回天然电平。
    它只对「尾音先播、主音延迟停」的交叠方案有意义；direct（同刻切）方案下压头把
    尾音入场压低 6dB，切口台阶从 −1dB 恶化到 −4.25dB（2026-10-09 本地复现实测），
    故默认 0。若回到交叠类方案再启用。参数保留以便 A/B。
    """
    sr = 44100
    x = load_pcm(main_ogg, sr)
    i0 = int(seg_start * sr)
    seg = x[i0: i0 + int(seg_len * sr)].copy()
    n = len(seg)
    fade_in = min(int(0.01 * sr), n)  # 10ms 防爆音（压头已把起始压低，此淡入只是保险）
    if fade_in:
        seg[:fade_in] *= np.linspace(0.0, 1.0, fade_in, endpoint=False)
    nd = min(int(duck_s * sr), n)
    seg[:nd] *= np.linspace(10 ** (-duck_db / 20.0), 1.0, nd)
    fo = min(int(fade_out * sr), n)
    if fo:
        seg[n - fo:] *= np.linspace(1.0, 0.0, fo)
    tmp = out.with_suffix(".tmp.wav")
    import wave
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        # load_pcm 返回的是 int16 刻度浮点（未归一化，level_curve 自行 /32768），直接取整回写
        w.writeframes(np.clip(np.round(seg), -32768, 32767).astype(np.int16).tobytes())
    run(["-i", tmp, "-c:a", "libvorbis", "-q:a", "5", "-ar", "44100", "-ac", "1"], out)
    tmp.unlink()
    print(f"  {out.name}: 源位置 {seg_start:.2f}s 长 {len(seg) / sr:.2f}s"
          f" 压头 -{duck_db:.0f}dB/{duck_s * 1000:.0f}ms  peak {peak_dbfs(out):.1f} dBFS")


def tail_positions(dur: float, start_s=0.18, cap_s=2.8):
    """4 档非均匀布局（0.7.16g，降采样数）：短按区 3 档密置（0.18/0.35/0.55s
    ——绝大多数演奏保持时长落在 0.1~0.6s，斜率陡（~20dB/s）需要密档），
    长按区单档 1.1s（中按 0.7~1.1s 贴合、长按在 -30dB 以下安静段，服务端电平补偿+掩蔽
    掩盖斜率差）。电平由 tailGain 曲线插值兜底，档位只负责音色/斜率贴近。
    起点 0.18s 避开 attack 残余区（0.7.16e 教训）。hi 截断+去重使短采样
    锚点（fs6/fs7 等）自然收缩到 1~3 档。"""
    hi = max(0.25, min(cap_s, dur - 1.05))
    pos = []
    for p in (start_s, 0.35, 0.55, 1.1):
        if p > hi:
            break
        if pos and p - pos[-1] < 0.1:
            continue
        pos.append(round(p, 3))
    if not pos:
        pos.append(round(min(start_s, hi), 3))
    return tuple(pos)


def load_pcm(path: Path, sr: int = 44100):
    r = subprocess.run([str(FF), "-hide_banner", "-i", str(path), "-ac", "1",
                        "-ar", str(sr), "-f", "s16le", "-"], capture_output=True)
    n = len(r.stdout) // 2
    return np.frombuffer(r.stdout[: n * 2], dtype=np.int16).astype(np.float64)


def measure_f0(ogg: Path, expect_hz: float) -> float:
    """主导频率：取 0.3~1.3s 中段。低音（<300Hz）自相关（基频可弱于二次谐波），
    高音 FFT 主峰 + 抛物线插值（整数 lag 精度不够）。"""
    sr = 44100
    x = load_pcm(ogg, sr)
    seg = x[int(0.3 * sr): int(1.3 * sr)]
    if len(seg) < 4096:
        seg = x[-40960:]
    seg = seg - seg.mean()
    if expect_hz < 300:
        lo, hi = int(sr / 320), int(sr / 20)
        energy = np.dot(seg, seg)
        lags = np.arange(lo, hi)
        ac = np.array([np.dot(seg[: len(seg) - l], seg[l:]) / (len(seg) - l)
                       for l in lags])
        f0 = sr / lags[int(np.argmax(ac))]
        quality = ac.max() / (energy / len(seg))
        if quality > 0.1:
            # 自相关整数倍周期伪峰（octave error）：结果明显低于标称时取半 lag
            if f0 < expect_hz * 0.75:
                f0 *= 2
            return round(f0, 2)
        return 0.0
    win = np.hanning(len(seg))
    spec = np.abs(np.fft.rfft(seg * win))
    freqs = np.fft.rfftfreq(len(seg), 1 / sr)
    band = (freqs > 20) & (freqs < 4300)
    k = int(np.argmax(spec * band))
    if k <= 0 or k >= len(spec) - 1:
        return 0.0
    a, b, c = spec[k - 1], spec[k], spec[k + 1]
    delta = 0.5 * (a - c) / (a - 2 * b + c)
    return round(freqs[k] + delta * (freqs[1] - freqs[0]), 2)


def level_curve(ogg: Path, step_ms=200):
    """主采样电平曲线：0 起每 step_ms 一个点（100ms 窗 RMS，dBFS），
    到 min(3.0, dur-0.1) 为止。服务端按曲线插值算
    gain = level(素材位置) - level(松键时刻)——比单一斜率精确（钢琴衰减
    先快后慢，全段回归的单一斜率在早段误差 3~4dB）。"""
    sr = 44100
    x = load_pcm(ogg, sr)
    dur = len(x) / sr
    hi = min(3.0, dur - 0.1)
    win = int(0.1 * sr)
    ms, dbs = [], []
    for t_ms in range(0, int(hi * 1000), step_ms):
        t = t_ms / 1000.0
        seg = x[int(t * sr): int(t * sr) + win]
        if len(seg) < win // 2:
            break
        rms = math.sqrt(np.dot(seg, seg) / len(seg)) / 32768.0
        if rms < 1e-6:
            break
        ms.append(t_ms)
        dbs.append(round(20 * math.log10(rms), 2))
    return ms, dbs


def pitch_report():
    """vl3 层逐锚点测基频，打印 cents 偏差与 yml base-pitch 建议。"""
    print("== 基频实测（vl3 常规层，A440 标称）==")
    lines = []
    for _note, key, midi in ANCHORS:
        f_ref = 440.0 * 2 ** ((midi - 69) / 12)
        f0 = measure_f0(OUT / f"piano_{key}.ogg", f_ref)
        cents = 1200 * math.log2(f0 / f_ref)
        corr = 2 ** (-cents / 1200)
        base_pitch = 2 ** ((60 - midi) / 12) * corr
        flag = "  ← 需校正" if abs(cents) > 5 else ""
        lines.append((key, base_pitch))
        print(f"  {key:4s} 标称 {f_ref:7.2f} Hz  实测 {f0:7.2f} Hz  "
              f"偏差 {cents:+6.1f}c  base-pitch {base_pitch:.4f}{flag}")
    print("== yml base-pitch 建议（直接粘贴用）==")
    for key, bp in lines:
        print(f"  {key}: {bp:.4f}")


def main():
    if not SRC.exists():
        print(f"源目录不存在: {SRC}（先跑 tools/fetch-piano-src.py）", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)

    print("== 旧产物迁移 ==")
    OLD.mkdir(parents=True, exist_ok=True)
    for old, new in REUSE.items():
        src, dst = OUT / old, OUT / new
        if src.exists() and not dst.exists():
            shutil.move(src, dst)
            print(f"  复用 {old} → {new}")
        elif src.exists():
            src.unlink()
    for name in STALE:
        src = OUT / name
        if src.exists():
            shutil.move(src, OLD / name)
            print(f"  归档 {name} → downloads/piano-old/")

    print("== 主采样（14 锚点 × 3 力度层）==")
    for note, key, _midi in ANCHORS:
        for prefix, vl in LAYERS:
            out = OUT / f"piano_{prefix}{key}.ogg"
            if out.exists():
                print(f"  {out.name}: 已存在，跳过")
                continue
            build_main(SRC / f"JHPiano_Sus_Close_{note}_{vl}_rr1.wav", out)

    print("== 制音尾音（等 dB 分档「素材位置≈保持时长」，天然电平）==")
    grades = {}
    for _note, key, _midi in ANCHORS:
        main_ogg = OUT / f"piano_{key}.ogg"
        for old in OUT.glob(f"piano_rel_{key}_*.ogg"):  # 档数可变，旧档全清重切
            old.unlink()
        pos = tail_positions(duration_s(main_ogg))
        grades[key] = [int(p * 1000) for p in pos]
        for i, p in enumerate(pos):
            build_tail(main_ogg, OUT / f"piano_rel_{key}_t{i}.ogg", seg_start=p)
        # 短保持专用短尾（0.8.1）：heldMs < 服务端 SHORT_TAIL_HELD_MS 时改播 ts——
        # 断奏物理 = 制音器快速掐弦，不该让 0.65s 的近全电平尾音盖过 0.1~0.3s 的
        # 短按（基线实测此区间「再弹一下」感最重）。切点同 t0、0.3s + 0.18s 陡淡出。
        build_tail(main_ogg, OUT / f"piano_rel_{key}_ts.ogg",
                   seg_start=pos[0], seg_len=0.3, fade_out=0.18)

    (SRC / "rel-grades.json").write_text(
        json.dumps(grades, ensure_ascii=False), encoding="utf-8")
    print(f"== rel 档位表已写 {SRC / 'rel-grades.json'}（tools/emit-release-voices.py 用）==")

    pitch_report()
    return 0


if __name__ == "__main__":
    sys.exit(main())
