#!/usr/bin/env python3
"""小提琴采样重建（0.8.0）：VSCO 2.1 CE SViolinVib（独奏小提琴揉弦延音，CC0）。

来源：Versilian Studios VSCO 2 CE（CC0 1.0），官方 S3 分发包
VSCO-2.1-XRNI-20170216.zip（776MB）内 flat 的 SViolinVib.xrni（Renoise 乐器，
嵌套 zip）。15 锚点 G3~C7（g3/a3/c4/e4/g4/a4/c5/e5/g5/a5/c6/e6/g6/a6/c7）×
p/f 双力度层。包太大不整取——本脚本用 HTTP Range 只拉目标条目
（中央目录 + deflate 局部解压），缓存 xrni 与 flac 到 downloads/violin-src/。

键位窗口直接沿用 patch 自带键位表（就近路由，最大拉伸 ±2 半音；
顶部 C7 锚点延到 +40（E7），拉伸 +4 仍在客户端钳制内）。

管线沿用 0.7.14 口琴口径：highpass 40 / −45dB 静音去除 / 起振裁切（首个 5ms 窗
RMS ≥ 峰值−12dB）/ 4ms 淡入 / 限长 / 0.15s 淡出 / −3dBFS / RMS 对齐 / vorbis q5
mono 44.1k。逐音 FFT 实测基频（VSCO 无八度标注坑但一律实测），并产出
instruments.yml voices+layers 片段与 README 表格行。

用法：python tools/violin-rebuild.py [限长秒数，默认 3.0]
"""
import io
import math
import struct
import subprocess
import os
import shutil
import sys
import urllib.request
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "downloads/violin-src"
OUT = ROOT / "assets/resourcepack/assets/chord/sounds/violin"
FF = Path(os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg")
ZIP_URL = "https://s3.amazonaws.com/VersilianStudios/VSCO-2.1-XRNI-20170216.zip"
XRNI_NAME = "SViolinVib.xrni"
SR = 22050
THRESHOLD_DB = -12.0
FADE_IN_S = 0.004
MAX_S = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0
# 响度对齐：目标 = 口琴成品的 −11.3dB（旋律手持乐器同一混音基准）；
# 峰值用 alimiter 钳 −2.5dBFS（必须 level=false，见口琴章踩坑）
TARGET_RMS_DB = -11.3
PEAK_CEIL_DB = -2.5

# (flac 文件名, 输出音名 stem, 相对 C4 半音)。p=柔音层 / f=常规+强音层（VSCO 2.1 只有两层）。
NOTES = [
    ("g3", -5), ("a3", -3), ("c4", 0), ("e4", 4), ("g4", 7),
    ("a4", 9), ("c5", 12), ("e5", 16), ("g5", 19), ("a5", 21),
    ("c6", 24), ("e6", 28), ("g6", 31), ("a6", 33), ("c7", 36),
]
DYNAMICS = [("p", "soft"), ("f", "")]  # (源后缀, 成品文件中缀)


# ---------------------------------------------------------------- 下载（HTTP Range 局部解压）

def fetch(start: int, end: int) -> bytes:
    req = urllib.request.Request(ZIP_URL, headers={"Range": f"bytes={start}-{end}"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except OSError as e:
            if attempt == 2:
                raise
            print(f"  重取（{e}）…")


def zip_total_size() -> int:
    req = urllib.request.Request(ZIP_URL, method="HEAD")
    with urllib.request.urlopen(req, timeout=30) as r:
        return int(r.headers["Content-Length"])


def zip_find_entry(name: str):
    """返回 (压缩大小, method, 本地头偏移)。仅支持无 ZIP64 的常规包。"""
    size = zip_total_size()
    tail = fetch(size - 1_000_000, size - 1)
    eocd = tail.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise RuntimeError("未找到 EOCD")
    _, _, _, _, cd_size, cd_ofs, _ = struct.unpack("<HHHHIIH", tail[eocd + 4 : eocd + 22])
    cd = fetch(cd_ofs, cd_ofs + cd_size - 1)
    pos = 0
    while pos < len(cd) and cd[pos : pos + 4] == b"PK\x01\x02":
        hdr = struct.unpack("<IHHHHHHIIIHHHHHII", cd[pos : pos + 46])
        name_len, extra_len, comment_len = hdr[10], hdr[11], hdr[12]
        if cd[pos + 46 : pos + 46 + name_len].decode() == name:
            return hdr[8], hdr[4], hdr[16]
        pos += 46 + name_len + extra_len + comment_len
    raise RuntimeError(f"zip 内未找到 {name}")


def download_xrni() -> Path:
    SRC.mkdir(parents=True, exist_ok=True)
    target = SRC / XRNI_NAME
    if target.exists() and target.stat().st_size > 1_000_000:
        print(f"已缓存 {target.name}（{target.stat().st_size // 1024 // 1024}MB），跳过下载")
        return target
    comp, method, lho = zip_find_entry(XRNI_NAME)
    print(f"Range 抽取 {XRNI_NAME}（压缩 {comp // 1024 // 1024}MB，method={method}）…")
    head = fetch(lho, lho + 29)
    _, _, _, _, _, _, _, c, _, nl, el = struct.unpack("<IHHHHHIIIHH", head)
    data = fetch(lho + 30 + nl + el, lho + 30 + nl + el + c - 1)
    raw = zlib.decompress(data, -15) if method == 8 else data
    target.write_bytes(raw)
    print(f"  解压 {len(raw) // 1024 // 1024}MB → {target.relative_to(ROOT)}")
    return target


def extract_flacs(xrni: Path) -> dict[tuple[str, str], Path]:
    """从 xrni（嵌套 zip）抽 SampleData flac 到 downloads/violin-src/flac/，返回 {(stem, dyn): path}。"""
    flac_dir = SRC / "flac"
    flac_dir.mkdir(exist_ok=True)
    result = {}
    with zipfile.ZipFile(xrni) as z:
        members = {n: i for n, i in ((m.filename, m) for m in z.infolist())}
        for stem, semi in NOTES:
            for dyn, _ in DYNAMICS:
                hit = [n for n in members if f"llvln_arcovib_{stem}_{dyn}" in n]
                if not hit:
                    raise RuntimeError(f"xrni 内未找到 {stem}_{dyn}")
                out = flac_dir / f"violin_{stem}_{dyn}.flac"
                if not (out.exists() and out.stat().st_size > 100_000):
                    out.write_bytes(z.read(hit[0]))
                result[(stem, dyn)] = out
    print(f"flac 缓存：{len(result)} 个 @ {flac_dir.relative_to(ROOT)}")
    return result


# ---------------------------------------------------------------- 加工与实测（口琴口径）

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
    import struct as _s
    n = len(r.stdout) // 2
    return _s.unpack(f"<{n}h", r.stdout[: n * 2])


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
    """分段 FFT 峰 + 抛物线插值的中位数（音分域）。

    揉弦采样单窗 FFT 会锁到揉弦瞬时相位（G6_f 实测 −42¢~+25¢ 摆动），
    感知音高 = 摆动中心，故把 0.15~2.95s 切 4 窗分别测、取中位数。
    限频段 ±25% 防泛音/次谐波带偏。"""
    import cmath
    import statistics
    x = load(path)
    sr = SR
    bounds = [0.15, 0.85, 1.55, 2.25, 2.95]
    cents_list = []
    for t0, t1 in zip(bounds, bounds[1:]):
        seg = x[int(t0 * sr) : int(t1 * sr)]
        if len(seg) < 1024:
            continue
        n = 1
        while n < len(seg) * 2:
            n <<= 1
        buf = [complex(v * (0.5 - 0.5 * math.cos(2 * math.pi * i / (len(seg) - 1))), 0) for i, v in enumerate(seg)]
        while len(buf) < n:
            buf.append(0j)
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
        lo = int(expected_hz / 1.25 / sr * n)
        hi = int(expected_hz * 1.25 / sr * n) + 1
        best = max(range(lo, hi), key=lambda k: abs(a[k]))
        l_mag, m_mag, r_mag = abs(a[best - 1]), abs(a[best]), abs(a[best + 1])
        delta = 0.5 * (l_mag - r_mag) / (l_mag - 2 * m_mag + r_mag) if (l_mag - 2 * m_mag + r_mag) != 0 else 0
        f0 = (best + delta) * sr / n
        cents_list.append(1200 * math.log2(f0 / expected_hz))
    if not cents_list:
        raise RuntimeError(f"{path.name}: 测不到基频")
    median_cents = statistics.median(cents_list)
    return expected_hz * 2 ** (median_cents / 1200)


NOTE_NAMES = {0: "C", 1: "C♯", 2: "D", 3: "D♯", 4: "E", 5: "F", 6: "F♯",
              7: "G", 8: "G♯", 9: "A", 10: "A♯", 11: "B"}


def note_name(semitone):
    octave = 4 + (semitone + 120) // 12 - 10  # semitone 相对 C4
    return f"{NOTE_NAMES[(semitone + 120) % 12]}{octave}"


def main() -> int:
    xrni = download_xrni()
    flacs = extract_flacs(xrni)
    OUT.mkdir(parents=True, exist_ok=True)

    print(f"\n== 加工（限长 {MAX_S}s，RMS 对齐 {TARGET_RMS_DB}dB）==")
    # results[(stem, dyn)] = dict
    results = {}
    for stem, semi in NOTES:
        for dyn, infix in DYNAMICS:
            src = flacs[(stem, dyn)]
            infix_s = f"{infix}_" if infix else ""
            out = OUT / f"violin_{infix_s}{stem}.ogg"
            tmp1 = OUT / f".{infix_s}{stem}.s.wav"
            strip = ("silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.005,"
                     "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,areverse")
            run(["-i", src, "-af", f"highpass=f=40,{strip}", "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp1)
            avail = duration_s(tmp1)
            cut = attack_cut_s(tmp1)
            cap = min(MAX_S, max(0.5, avail - cut))
            run(["-i", tmp1, "-af",
                 f"atrim={cut:.3f},asetpts=PTS-STARTPTS,atrim=0:{cap:.2f},"
                 f"afade=t=in:st=0:d={FADE_IN_S},afade=t=out:st={max(0.0, cap - 0.15):.2f}:d=0.15",
                 "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "1"], tmp1.with_suffix(".c.wav"))
            run(["-i", tmp1.with_suffix(".c.wav"), "-af", "anull", "-c:a", "libvorbis", "-q:a", "5",
                 "-ar", "44100", "-ac", "1"], out)
            norm_to(out, -3.0)
            final_rms, final_peak = rms_align(out)
            expected = 261.626 * 2 ** (semi / 12)
            f0 = measure_f0(out, expected)
            cents = 1200 * math.log2(f0 / expected)
            if abs(cents) > 50:
                raise RuntimeError(f"{stem}_{dyn}: 实测 {f0:.1f}Hz 偏离标称 {cents:+.0f} 音分，中止（防八度误判）")
            base_pitch = 261.626 / f0
            results[(stem, dyn)] = dict(stem=stem, semi=semi, f0=f0, cents=cents,
                                        base_pitch=base_pitch, cut=cut, avail=avail, cap=cap,
                                        rms=final_rms, peak=final_peak, dur=duration_s(out))
            tmp1.unlink(missing_ok=True)
            tmp1.with_suffix(".c.wav").unlink(missing_ok=True)
            print(f"  {out.name}: 源 {avail:.1f}s 裁起振 {cut*1000:.0f}ms → {cap:.1f}s  "
                  f"f0={f0:.1f}Hz({cents:+.0f}¢)  base-pitch={base_pitch:.7f}  "
                  f"RMS={final_rms:.1f} peak={final_peak:.1f}")

    # 就近路由窗口（含并列取高锚点 = 原 Renoise 键位表口径）；顶部延到 +40（E7）
    semis = [semi for _, semi in NOTES]
    windows = {}
    for s in range(-5, 41):
        best = min(semis, key=lambda a: (abs(a - s), -a))
        windows.setdefault(best, [s, s])
        w = windows[best]
        w[0] = min(w[0], s)
        w[1] = max(w[1], s)

    print("\n== instruments.yml voices / layers 片段（实测 base-pitch）==")
    for dyn, infix in DYNAMICS:
        if dyn == "f":
            print("  常规层 voices：")
        else:
            print("  力度分层 layers（min-velocity 1~63，柔音层）：")
        for stem, semi in NOTES:
            r = results[(stem, dyn)]
            w = windows[semi]
            ev = f"chord:violin.{r['stem']}.%d" if dyn == "f" else f"chord:violin.soft.{r['stem']}.%d"
            ogg = f"violin/{'violin_' + ((infix + '_') if infix else '')}{stem}"
            print(f"    - {{ min: {w[0]:>3}, max: {w[1]:>3}, sound: \"{ev}\",  base-pitch: {r['base_pitch']:.7f} }}  # {note_name(semi)} 实测")

    print("\n== gen-sounds-json.py BASE_OGG 片段 ==")
    for dyn, infix in DYNAMICS:
        for stem, _ in NOTES:
            ev = f"violin.{stem}" if dyn == "f" else f"violin.soft.{stem}"
            ogg = f"violin/violin_{(infix + '_') if infix else ''}{stem}"
            print(f'    "{ev}": "{ogg}",')

    print("\n== README 表格行 ==")
    for dyn, infix in DYNAMICS:
        layer_cn = "柔音 vl-p" if dyn == "p" else "常规 vl-f"
        for stem, semi in NOTES:
            r = results[(stem, dyn)]
            f = f"violin_{infix}_{stem}.ogg" if infix else f"violin_{stem}.ogg"
            ev = f"chord:violin.{stem}" if dyn == "f" else f"chord:violin.soft.{stem}"
            print(f"| `{f}` | `{ev}` | **{note_name(semi)}**（{r['cents']:+.0f}¢） | {r['f0']:.1f} Hz | {r['dur']:.1f} s | {layer_cn}")

    print("\n== 实测汇总 ==")
    for dyn, infix in DYNAMICS:
        for stem, semi in NOTES:
            r = results[(stem, dyn)]
            print(f"  {stem}_{dyn} {note_name(semi):4}  f0={r['f0']:7.1f}Hz ({r['cents']:+3.0f}¢)  "
                  f"源{r['avail']:4.1f}s 裁{r['cut']*1000:3.0f}ms → {r['cap']:.1f}s  {r['rms']:.1f}dB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
